package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.gathin.tvhome.family.LanPairing;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Children's mode on the TV. A small server (KidsLockServer) takes the lock and unlock straight from an authorised phone,
 * so nothing polls and no other machine is needed. The switch is kept on the TV and survives a restart. While it is on,
 * Home shows the lock in the message card and takes every key, and HomeKeyService sends any app back to Home.
 *
 * It is off unless the theme has kids_phones (name to token) and kids_pin_salt and kids_pin_hash (tools/lock-setup.py makes
 * them); kids_lock_port is 8787 unless set. Taking those keys out of the theme (apply.sh with the old theme) turns it
 * off and clears the switch: that is the way out if the phones or the code are lost.
 */
final class KidsLock {
    interface Listener {
        void onChanged(boolean locked);
    }

    private static final String TAG = "KidsLock";
    private static final String PREFS = "kids_lock";
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean locked;
    /** The schedule on the TV says the time is up (KidsGuard); counts as a lock, with its own text. */
    private static volatile boolean scheduled;
    private static volatile String scheduledNote = "";
    private static String signature = "";
    private static KidsLockServer server;
    /** How long a lock may last before it lifts itself (theme key kids_lock_max_hours, 12 by default, 0 = never). */
    private static volatile long maxMs = 12L * 60 * 60 * 1000;
    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            expireIfDue();
            if (server != null) MAIN.postDelayed(this, 60_000L);
        }
    };
    private static SharedPreferences prefs;
    /** The pairing of phones on the home network; null while that mode is off. */
    private static volatile LanPairing pairing;
    private static volatile int lanPort = 8787;

    private KidsLock() { }

    /**
     * The way out when the code set on the TV is lost while the TV is locked (the remote is taken, so nothing on the TV
     * can enter it): put an empty file named reset-kids-lock into the app's folder on shared storage over adb
     * (/sdcard/Android/data/com.gathin.tvhome/files/) and restart the helper. The lock is lifted and the code set on
     * the TV is removed, so the code in the theme (tools/lock-setup.py) unlocks again; the schedule, the paired phones
     * and the journal stay. The file is deleted as it is used.
     */
    private static void recoverIfAsked(Context ctx) {
        java.io.File dir = ctx.getExternalFilesDir(null);
        java.io.File marker = dir == null ? null : new java.io.File(dir, "reset-kids-lock");
        if (marker == null || !marker.exists()) return;
        marker.delete();
        prefs.edit().putBoolean("locked", false).putString("by", "reset").remove("since").remove("note").apply();
        KidsStore store = new KidsStore(ctx);
        store.clearPin();
        store.journal("pin_reset", "lock lifted and the code set on the TV removed over adb");
        Log.w(TAG, "recovery: lock lifted, the code set on the TV removed");
    }

    /** Reads the theme and starts or stops the server; safe to call again with the same theme. */
    static synchronized void configure(Context ctx, JSONObject theme) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        recoverIfAsked(ctx);
        JSONObject phones = theme == null ? null : theme.optJSONObject("kids_phones");
        String salt = theme == null ? "" : theme.optString("kids_pin_salt", "");
        String hash = theme == null ? "" : theme.optString("kids_pin_hash", "");
        // Two ways in: the phones named in the theme (a token in the address), and phones paired on the TV (a token in a header).
        boolean themePhones = phones != null && phones.length() > 0 && !salt.isEmpty() && !hash.isEmpty();
        KidsStore store = new KidsStore(ctx);
        boolean paired = store.lanEnabled() && store.hasPin();
        if (!themePhones && !paired) {
            MAIN.removeCallbacks(TICK);
            if (server != null) server.stop();
            server = null;
            pairing = null;
            LanDiscovery.stop();
            signature = "";
            prefs.edit().clear().apply();
            set(false, "");
            return;
        }
        // The code set on the TV is the one that unlocks, when there is one.
        if (store.hasPin()) {
            salt = store.pinSalt();
            hash = store.pinHash();
        }
        int port = theme == null ? 8787 : theme.optInt("kids_lock_port", 8787);
        int hours = theme == null ? 12 : theme.optInt("kids_lock_max_hours", 12);
        maxMs = hours > 0 ? hours * 60L * 60 * 1000 : 0;
        String now = (themePhones ? phones.toString() : "") + salt + hash + port + "/" + hours + (paired ? "/paired" : "");
        if (now.equals(signature) && server != null) return;
        if (server != null) server.stop();
        Map<String, String> map = new LinkedHashMap<>();
        for (Iterator<String> it = themePhones ? phones.keys() : java.util.Collections.<String>emptyIterator(); it.hasNext(); ) {
            String name = it.next();
            map.put(name, phones.optString(name));
        }
        locked = prefs.getBoolean("locked", false);
        KidsLockCore core = new KidsLockCore(map, salt, hash, new KidsLockCore.State() {
            @Override
            public boolean isLocked() {
                KidsLock.expireIfDue();
                return locked;
            }

            @Override
            public String by() {
                return prefs.getString("by", "");
            }

            @Override
            public String note() {
                return prefs.getString("note", "");
            }

            @Override
            public void setNote(String note) {
                prefs.edit().putString("note", note).apply();
                if (locked) refresh();
            }

            @Override
            public void set(boolean value, String by) {
                applyLock(value, by);
            }
        }, System::currentTimeMillis);
        core.setPowerOff(seconds -> SleepTimer.powerOff(ctx, seconds));
        core.setUsage(UsageLog::json);
        core.setScreenTime(UsageLog::daysJson);
        core.setLicense(() -> Entitlements.summary(ctx), key -> Entitlements.submit(ctx, key).reason == com.gathin.tvhome.family.LicenseKey.Reason.OK ? Entitlements.summary(ctx) : null);
        core.setSettings(() -> SettingsFiles.export(ctx), json -> SettingsFiles.restore(ctx, json));
        core.setText(() -> webText(ctx));
        core.setHealth(() -> {
            boolean on = FamilyHooks.serviceEnabled(ctx);
            store.serviceSeen(on);
            return "\"service\":" + on + ",\"journal\":" + store.journalJson(10);
        }, store::journal);
        if (paired) {
            pairing = new LanPairing(store, System::currentTimeMillis);
            core.setLan(pairing, new KidsLockCore.LanActions() {
                @Override
                public boolean message(String text) {
                    KidsGuard.Messenger m = KidsGuard.messenger();
                    if (m != null) m.message(text);
                    return m != null;
                }

                @Override
                public boolean pause() {
                    KidsGuard.Messenger m = KidsGuard.messenger();
                    if (m != null) m.pause();
                    return m != null;
                }
            });
        } else {
            pairing = null;
        }
        try {
            server = new KidsLockServer(port, core);
            server.start();
            lanPort = port;
            if (paired) LanDiscovery.start(ctx, port);
            else LanDiscovery.stop();
            signature = now;
            MAIN.removeCallbacks(TICK);
            MAIN.post(TICK);
        } catch (java.io.IOException e) {
            Log.w(TAG, "cannot listen on " + port + ": " + e.getMessage());
            server = null;
            signature = "";
        }
    }

    /** The pairing of phones on the home network, null while the TV does not answer them. */
    static LanPairing pairing() {
        return pairing;
    }

    static int lanPort() {
        return lanPort;
    }

    /** The text the phone sent with the lock, or the schedule's reason, empty for none. */
    static String note() {
        if (!locked && scheduled) return scheduledNote;
        return prefs == null || !locked ? "" : prefs.getString("note", "");
    }

    /** True when only the schedule blocks the TV (a parent can add time at the TV), not a lock from the phone. */
    static boolean scheduledOnly() {
        return scheduled && !locked;
    }

    /** The schedule on the TV (KidsGuard) blocks the TV or stops blocking it; listeners hear of a change of either. */
    static void setScheduled(boolean blocked, String note) {
        boolean before = isLocked();
        String oldNote = scheduledNote;
        scheduled = blocked;
        scheduledNote = blocked ? note : "";
        if (before != isLocked() || (blocked && !note.equals(oldNote))) refresh();
    }

    /** Tells the listeners again, for a note that changed while the TV was already locked. */
    private static void refresh() {
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onChanged(isLocked());
        });
    }

    static boolean isLocked() {
        expireIfDue();
        return locked || scheduled;
    }

    /** Lifts a lock that has lasted longer than the limit (also right after a restart, when the clock kept running). */
    static synchronized void expireIfDue() {
        if (!locked || prefs == null) return;
        long since = prefs.getLong("since", 0);
        long now = System.currentTimeMillis();
        if (since == 0) {
            prefs.edit().putLong("since", now).apply();
            return;
        }
        if (KidsLockCore.expired(since, now, maxMs)) {
            prefs.edit().putBoolean("locked", false).putString("by", "auto").remove("since").remove("note").apply();
            set(false, "auto");
        }
    }

    static void add(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    static void remove(Listener l) {
        LISTENERS.remove(l);
    }

    /** The manual lock on or off with who did it: the preferences, the time it started and the listeners. */
    private static void applyLock(boolean value, String by) {
        android.content.SharedPreferences.Editor e = prefs.edit().putBoolean("locked", value).putString("by", by);
        if (!value) e.remove("note");
        if (value && !locked) e.putLong("since", System.currentTimeMillis());
        else if (!value) e.remove("since");
        e.apply();
        set(value, by);
    }

    /** A lock from the cloud link, with the parent's note, as the phone pages' lock does it; false before the lock is configured. */
    static synchronized boolean lockFromCloud(String note, String by) {
        if (prefs == null) return false;
        prefs.edit().putString("note", note).apply();
        applyLock(true, by);
        return true;
    }

    /** Lifts a manual lock from the cloud link; false while only the schedule blocks the TV (time is given instead). */
    static synchronized boolean unlockFromCloud(String by) {
        if (prefs == null || (scheduled && !locked)) return false;
        if (locked) applyLock(false, by);
        return true;
    }

    /** True while a phone (or the cloud) has locked the TV by hand. */
    static boolean lockedByHand() {
        return locked;
    }

    private static void set(boolean now, String by) {
        boolean before = locked;
        locked = now;
        if (before != now) MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onChanged(now || scheduled);
        });
    }

    /** The words of the phone pages, in the launcher's language (the TV's, or the theme's locale key). */
    private static final int[] WEB_TEXT = {
            R.string.web_title, R.string.web_kids_title, R.string.web_locale, R.string.web_pair_code_hint, R.string.web_pair_name_hint,
            R.string.web_pair_button, R.string.web_unlock_hint, R.string.web_message_hint, R.string.web_send_message, R.string.web_pause,
            R.string.web_power_off, R.string.web_power_off_30, R.string.web_screentime, R.string.web_backup, R.string.web_restore,
            R.string.web_license_hint, R.string.web_license_button, R.string.web_connect_phone, R.string.web_locked, R.string.web_kids_on,
            R.string.web_unlocked, R.string.web_service_off, R.string.web_locked_by, R.string.web_by_auto, R.string.web_by_reset, R.string.web_by_cloud,
            R.string.web_j_service_off, R.string.web_j_service_on, R.string.web_j_pin_fail, R.string.web_j_unlock_fail,
            R.string.web_j_pair_fail, R.string.web_j_paired, R.string.web_j_poweroff, R.string.web_j_poweroff_cancel, R.string.web_lock,
            R.string.web_unlock, R.string.web_no_answer, R.string.web_phone_name, R.string.web_pair_bad, R.string.web_wrong_pin,
            R.string.web_not_sent, R.string.web_confirm_off, R.string.web_off_failed, R.string.web_off_in_30, R.string.web_license_bad,
            R.string.web_restored, R.string.web_not_a_backup, R.string.web_min, R.string.web_hour};

    private static java.util.Map<String, String> webText(Context ctx) {
        Context localized = LauncherLocale.wrap(ctx);
        java.util.Map<String, String> table = new java.util.HashMap<>();
        for (int id : WEB_TEXT) table.put(localized.getResources().getResourceEntryName(id), localized.getString(id));
        return table;
    }
}
