package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import com.gathin.tvhome.family.KidsClock;
import com.gathin.tvhome.family.KidsJournal;
import com.gathin.tvhome.family.KidsSchedule;
import com.gathin.tvhome.family.LanPairing;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where the kids' schedule lives on the TV: the windows, the limit, the parents' code (as a PBKDF2 hash, never the code),
 * today's use, the bonus and the last believed time. Plain preferences in the launcher's own storage; nothing leaves the TV.
 */
final class KidsStore implements KidsClock.Store, LanPairing.Store {
    private static final String PREFS = "kids_schedule";
    /** The default windows when a parent turns the schedule on: the TV is for the day, not the night. */
    static final String DEFAULT_WEEKDAYS = "07:00-20:30";
    static final String DEFAULT_WEEKENDS = "08:00-21:30";

    private final SharedPreferences prefs;

    KidsStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    KidsSchedule schedule() {
        ZoneId zone;
        try {
            zone = ZoneId.of(prefs.getString("tz", ZoneId.systemDefault().getId()));
        } catch (RuntimeException e) {
            zone = ZoneId.systemDefault();
        }
        return new KidsSchedule(prefs.getBoolean("enabled", false), zone, windows("weekdays"), windows("weekends"),
                prefs.getInt("limit", 0), new int[] {10, 5, 1});
    }

    private List<KidsSchedule.Window> windows(String key) {
        String text = prefs.getString(key, null);
        if (text == null) return null;
        List<KidsSchedule.Window> out = new ArrayList<>();
        for (String part : text.split(";")) {
            KidsSchedule.Window w = KidsSchedule.parseWindow(part);
            if (w != null) out.add(w);
        }
        return out;
    }

    boolean enabled() {
        return prefs.getBoolean("enabled", false);
    }

    /** The windows as text ("07:00-20:30;22:00-23:00"), null for none set, "" for no allowed time. */
    String windowsText(boolean weekend) {
        return prefs.getString(weekend ? "weekends" : "weekdays", null);
    }

    int limitMin() {
        return prefs.getInt("limit", 0);
    }

    /** Turning it on fixes the schedule's time zone to the TV's now: the TV's own zone setting cannot move the windows after. */
    void setEnabled(boolean on) {
        SharedPreferences.Editor e = prefs.edit().putBoolean("enabled", on);
        if (on) {
            if (!prefs.contains("tz")) e.putString("tz", ZoneId.systemDefault().getId());
            if (!prefs.contains("weekdays")) e.putString("weekdays", DEFAULT_WEEKDAYS);
            if (!prefs.contains("weekends")) e.putString("weekends", DEFAULT_WEEKENDS);
        }
        e.apply();
    }

    void setWindows(boolean weekend, String text) {
        SharedPreferences.Editor e = prefs.edit();
        if (text == null) e.remove(weekend ? "weekends" : "weekdays");
        else e.putString(weekend ? "weekends" : "weekdays", text);
        e.apply();
    }

    void setLimitMin(int min) {
        prefs.edit().putInt("limit", Math.max(0, min)).apply();
    }

    // ---- the parents' code ------------------------------------------------------------------------

    boolean hasPin() {
        return !prefs.getString("pin_hash", "").isEmpty();
    }

    void setPin(String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        StringBuilder hex = new StringBuilder();
        for (byte b : salt) hex.append(String.format(Locale.ROOT, "%02x", b));
        prefs.edit().putString("pin_salt", hex.toString()).putString("pin_hash", KidsLockCore.hashPin(pin, hex.toString())).apply();
    }

    void clearPin() {
        prefs.edit().remove("pin_salt").remove("pin_hash").apply();
    }

    /** PIN attempts are counted: five wrong ones in five minutes refuse every try until the window passes. */
    boolean checkPin(String pin, long nowMs) {
        String hash = prefs.getString("pin_hash", "");
        if (hash.isEmpty()) return true;
        long windowStart = prefs.getLong("pin_fail_start", 0);
        int fails = prefs.getInt("pin_fails", 0);
        if (nowMs - windowStart > KidsLockCore.FAIL_WINDOW_MS) {
            fails = 0;
            windowStart = nowMs;
        }
        if (fails >= KidsLockCore.MAX_FAILS) return false;
        boolean ok = java.security.MessageDigest.isEqual(KidsLockCore.hashPin(pin, prefs.getString("pin_salt", "")).getBytes(),
                hash.getBytes());
        SharedPreferences.Editor e = prefs.edit();
        if (ok) e.remove("pin_fails").remove("pin_fail_start");
        else e.putInt("pin_fails", fails + 1).putLong("pin_fail_start", windowStart);
        e.apply();
        if (!ok) journal("pin_fail", fails + 1 >= KidsLockCore.MAX_FAILS ? "wrong code on the TV, locked for 5 min" : "wrong code on the TV");
        return ok;
    }

    /** True while the code is refused because of too many wrong tries. */
    boolean pinLocked(long nowMs) {
        return prefs.getInt("pin_fails", 0) >= KidsLockCore.MAX_FAILS
                && nowMs - prefs.getLong("pin_fail_start", 0) <= KidsLockCore.FAIL_WINDOW_MS;
    }

    /** "Erase everything": the schedule, the code, the paired phones, the journal and bonus time. */
    void eraseAll() {
        synchronized (KidsStore.class) {
            prefs.edit().clear().apply();
        }
    }

    // ---- the journal ----------------------------------------------------------------------------------

    /** Adds an event the parents can read later (see KidsJournal); it also goes to the phone page. */
    void journal(String kind, String text) {
        synchronized (KidsStore.class) {
            prefs.edit().putString("journal", KidsJournal.append(prefs.getString("journal", ""), System.currentTimeMillis(), kind, text)).apply();
        }
    }

    String journalJson(int n) {
        return KidsJournal.json(prefs.getString("journal", ""), n);
    }

    /**
     * The accessibility helper is what holds the lock, so its going off is written down once per change, and only while the
     * schedule or the phone control is on. Called with the helper's real state from the helper itself and from Home at start.
     */
    void serviceSeen(boolean on) {
        if (!enabled() && !lanEnabled()) return;
        if (prefs.contains("service_on") && prefs.getBoolean("service_on", true) == on) return;
        prefs.edit().putBoolean("service_on", on).apply();
        journal(on ? "service_on" : "service_off", on ? "the helper is on" : "the helper is off: the lock does not work");
    }

    boolean serviceOn() {
        return prefs.getBoolean("service_on", true);
    }

    // ---- phones on the home network ------------------------------------------------------------------

    /** Whether the TV answers a paired phone on the home network (it needs the code too). */
    boolean lanEnabled() {
        return prefs.getBoolean("lan", false);
    }

    void setLanEnabled(boolean on) {
        prefs.edit().putBoolean("lan", on).apply();
    }

    /** The code's salt and hash for KidsLockCore, empty strings when no code is set. */
    String pinSalt() {
        return prefs.getString("pin_salt", "");
    }

    String pinHash() {
        return prefs.getString("pin_hash", "");
    }

    @Override
    public List<LanPairing.Phone> load() {
        List<LanPairing.Phone> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("phones", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                out.add(new LanPairing.Phone(o.getString("id"), o.getString("name"), o.getString("hash"), o.optLong("at")));
            }
        } catch (JSONException e) {
            out.clear();
        }
        return out;
    }

    @Override
    public void save(List<LanPairing.Phone> phones) {
        JSONArray array = new JSONArray();
        try {
            for (LanPairing.Phone p : phones) {
                array.put(new JSONObject().put("id", p.id).put("name", p.name).put("hash", p.tokenHash).put("at", p.pairedAt));
            }
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString("phones", array.toString()).apply();
    }

    // ---- apps closed for the children ---------------------------------------------------------------

    /** True when the schedule is on and a parent closed this app: it is sent back to Home whenever it comes up. */
    boolean appClosed(String pkg) {
        return prefs.getBoolean("enabled", false) && prefs.getStringSet("closed", java.util.Collections.emptySet()).contains(pkg);
    }

    boolean isClosed(String pkg) {
        return prefs.getStringSet("closed", java.util.Collections.emptySet()).contains(pkg);
    }

    void setClosed(String pkg, boolean closed) {
        java.util.Set<String> set = new java.util.HashSet<>(prefs.getStringSet("closed", java.util.Collections.emptySet()));
        if (closed) set.add(pkg);
        else set.remove(pkg);
        prefs.edit().putStringSet("closed", set).apply();
    }

    // ---- state ------------------------------------------------------------------------------------

    long bonusUntil() {
        return prefs.getLong("bonus_until", 0);
    }

    void setBonusUntil(long ms) {
        prefs.edit().putLong("bonus_until", ms).apply();
    }

    /** Use counted for the day that began at dayStartMs; another day starts from nothing. */
    long usedMs(long dayStartMs) {
        return prefs.getLong("used_day", 0) == dayStartMs ? prefs.getLong("used_ms", 0) : 0;
    }

    void setUsedMs(long dayStartMs, long ms) {
        prefs.edit().putLong("used_day", dayStartMs).putLong("used_ms", ms).apply();
    }

    @Override
    public long lastTime() {
        return prefs.getLong("last_time", 0);
    }

    @Override
    public void saveLastTime(long wallMs) {
        prefs.edit().putLong("last_time", wallMs).apply();
    }
}
