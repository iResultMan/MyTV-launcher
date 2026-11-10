package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import com.gathin.tvhome.family.KidsClock;
import com.gathin.tvhome.family.KidsPolicy;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The kids' schedule at work on the TV, with no network: every few seconds it asks KidsPolicy whether the TV is allowed,
 * keeps today's use (the time the screen is on), and turns the answer into the lock (KidsLock.setScheduled), so the
 * lock screen, the key filter and the accessibility helper treat "the time is up" like a lock from the phone. Ahead of
 * the end it tells the listeners, which show the warnings. One instance per process, started from Home and from the
 * helper; it keeps going after a restart because everything it needs is in KidsStore.
 */
final class KidsGuard {
    interface Listener {
        /** The TV will stop being allowed in this many minutes. */
        void onWarning(int minutes);
    }

    private static final long TICK_MS = 20_000L;
    /** A gap longer than this between two checks (standby, a stall) is not counted as use. */
    private static final long MAX_COUNTED_MS = 2 * 60_000L;
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static KidsGuard instance;
    /** What the phone on the home network can ask of the helper that draws over the apps; set while the helper runs. */
    interface Messenger {
        void message(String text);

        void pause();
    }

    private static volatile Messenger messenger;

    static void setMessenger(Messenger m) {
        messenger = m;
    }

    static Messenger messenger() {
        return messenger;
    }

    /** A code pad is open: the helper lets the keys through, they are for the pad. */
    private static volatile boolean pinEntry;

    private final Context ctx;
    private final KidsStore store;
    private final KidsClock clock;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PowerManager power;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            check();
            main.postDelayed(this, TICK_MS);
        }
    };
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            main.removeCallbacks(tick);
            main.post(tick);
        }
    };
    private long lastCheck;
    private long prevLeft = -1;

    private KidsGuard(Context app) {
        ctx = app;
        store = new KidsStore(app);
        clock = new KidsClock(System::currentTimeMillis, SystemClock::elapsedRealtime, store);
        power = app.getSystemService(PowerManager.class);
    }

    static synchronized void start(Context context) {
        if (instance != null) return;
        instance = new KidsGuard(context.getApplicationContext());
        instance.ctx.registerReceiver(instance.screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        instance.main.post(instance.tick);
    }

    static void setPinEntry(boolean open) {
        pinEntry = open;
    }

    static boolean pinEntry() {
        return pinEntry;
    }

    static void addListener(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    /** Checks at once, after the schedule or the bonus changed. */
    static synchronized void recheck() {
        if (instance != null) instance.main.post(instance::check);
    }

    /** The believed time, for the parts that show or save times. */
    static synchronized long now() {
        return instance != null ? instance.clock.now() : System.currentTimeMillis();
    }

    private void check() {
        com.gathin.tvhome.family.KidsSchedule schedule = store.schedule();
        long now = clock.now();
        KidsPolicy policy = new KidsPolicy(schedule);
        long dayStart = policy.dayStartMs(now);
        long used = store.usedMs(dayStart);
        // Use counts while the screen is on, between two checks that are close together.
        if (schedule.enabled && lastCheck != 0 && power != null && power.isInteractive()) {
            long delta = now - lastCheck;
            if (delta > 0 && delta <= MAX_COUNTED_MS) {
                used += delta;
                store.setUsedMs(dayStart, used);
            }
        }
        lastCheck = now;
        if (schedule.enabled) FamilyHooks.heal(ctx, store);
        if (!schedule.enabled) {
            prevLeft = -1;
            KidsLock.setScheduled(false, "");
            return;
        }
        KidsPolicy.Decision d = policy.evaluate(now, used, store.bonusUntil());
        if (d.allowed) {
            int due = KidsPolicy.warningDue(prevLeft, d.leftMs, schedule.warnMin);
            if (due > 0) for (Listener l : LISTENERS) l.onWarning(due);
            KidsLock.setScheduled(false, "");
        } else {
            KidsLock.setScheduled(true, blockedText(d, schedule.zone));
        }
        prevLeft = d.allowed ? d.leftMs : -1;
    }

    private String blockedText(KidsPolicy.Decision d, java.time.ZoneId zone) {
        int base = d.reason == KidsPolicy.Reason.LIMIT_REACHED ? R.string.kids_sched_limit : R.string.kids_sched_outside;
        String hint = store.hasPin() ? " " + ctx.getString(R.string.kids_lock_pin_hint) : "";
        if (d.opensAtMs == 0) return ctx.getString(base) + hint;
        long dayStart = new KidsPolicy(store.schedule()).dayStartMs(clock.now());
        SimpleDateFormat f = new SimpleDateFormat(d.opensAtMs - dayStart < 24L * 60 * 60 * 1000 ? "HH:mm" : "EEE HH:mm", Locale.getDefault());
        f.setTimeZone(java.util.TimeZone.getTimeZone(zone));
        return ctx.getString(base) + " " + ctx.getString(R.string.kids_sched_opens, f.format(new Date(d.opensAtMs))) + hint;
    }
}
