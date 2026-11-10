package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import com.gathin.tvhome.family.SleepTimerCore;

/**
 * "Turn the TV off in N minutes", kept by the process so it counts whichever app is in front. The accessibility helper
 * registers what can actually put the TV to sleep (Actor); without it the timer refuses to start, because a timer that
 * cannot fire would only look like it works. One minute before the end the Actor shows a warning that any remote key
 * answers with another stretch. The TV's own screen going off ends the timer.
 */
final class SleepTimer {
    /** What the helper provides: the sleep action itself and the warning over the apps. */
    interface Actor {
        void sleepNow();

        /** Show the warning; extend runs when a remote key answers it. */
        void warn(int extraMinutes, Runnable extend);

        /** Show the value just set; Up and Down on the remote change it while it is on screen. */
        void adjust(int minutes);
    }

    static final int EXTEND_MINUTES = 15;

    private static final SleepTimerCore CORE = new SleepTimerCore();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Actor actor;
    private static Context app;
    private static boolean receiving;

    private static final BroadcastReceiver SCREEN_OFF = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            cancel();
        }
    };

    private static final Runnable TICK = SleepTimer::tick;

    private SleepTimer() {
    }

    static void setActor(Actor a) {
        actor = a;
        if (a == null) cancel();
    }

    static boolean canFire() {
        return actor != null;
    }

    static synchronized int minutes() {
        return CORE.minutes();
    }

    static synchronized int remainingMinutes() {
        return CORE.remainingMinutes(SystemClock.elapsedRealtime());
    }

    /** Starts the timer; false when nothing could put the TV to sleep (the helper is not running). 0 turns it off. */
    static boolean set(Context ctx, int minutes) {
        if (minutes <= 0) {
            cancel();
            return true;
        }
        if (actor == null) return false;
        synchronized (SleepTimer.class) {
            app = ctx.getApplicationContext();
            CORE.arm(SystemClock.elapsedRealtime(), minutes);
            Log.i(TAG, "armed for " + minutes + " min");
        }
        listenForScreenOff();
        reschedule();
        return true;
    }

    /**
     * A parent's command from the phone or the bot: now (0) or after a delay in seconds; a negative number cancels the
     * timer. False when the helper is not running.
     */
    static boolean powerOff(Context ctx, int seconds) {
        if (seconds < 0) {
            cancel();
            return true;
        }
        Actor a = actor;
        if (a == null) return false;
        if (seconds == 0) {
            cancel();
            MAIN.post(a::sleepNow);
            return true;
        }
        synchronized (SleepTimer.class) {
            app = ctx.getApplicationContext();
            CORE.armSeconds(SystemClock.elapsedRealtime(), seconds);
        }
        MAIN.post(() -> {
            listenForScreenOff();
            reschedule();
        });
        return true;
    }

    /** The remote key's step through the choices; returns the new minutes (0 = off), -1 when it cannot start. */
    static int cycle(Context ctx) {
        int next;
        synchronized (SleepTimer.class) {
            if (actor == null) return -1;
            app = ctx.getApplicationContext();
            next = CORE.cycle(SystemClock.elapsedRealtime());
        }
        Log.i(TAG, next > 0 ? "armed for " + next + " min" : "turned off");
        if (next == 0) cancelSchedule();
        else {
            listenForScreenOff();
            reschedule();
        }
        return next;
    }

    static void cancel() {
        Log.i(TAG, "cancelled");
        synchronized (SleepTimer.class) {
            CORE.cancel();
        }
        cancelSchedule();
    }

    /** What the user is told after a change: the toast text for the new state. */
    static void tell(Context ctx, int minutes) {
        Actor a = actor;
        if (a != null) {
            a.adjust(minutes);
            return;
        }
        Toast.makeText(ctx, text(ctx, minutes), Toast.LENGTH_LONG).show();
    }

    static String text(Context ctx, int minutes) {
        return minutes > 0 ? ctx.getString(R.string.sleep_set, minutes) : ctx.getString(R.string.sleep_off_done);
    }

    /** Up (+1) or Down (-1) on the value on screen; returns the new minutes, 0 when the timer is now off. */
    static int step(int direction) {
        int next;
        synchronized (SleepTimer.class) {
            next = CORE.step(SystemClock.elapsedRealtime(), direction);
        }
        Log.i(TAG, next > 0 ? "set to " + next + " min" : "turned off");
        if (next == 0) cancelSchedule();
        else {
            listenForScreenOff();
            reschedule();
        }
        return next;
    }

    private static void listenForScreenOff() {
        if (receiving || app == null) return;
        app.registerReceiver(SCREEN_OFF, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        receiving = true;
    }

    private static void cancelSchedule() {
        MAIN.removeCallbacks(TICK);
        if (receiving && app != null) {
            try {
                app.unregisterReceiver(SCREEN_OFF);
            } catch (RuntimeException ignored) {
            }
            receiving = false;
        }
    }

    private static void reschedule() {
        MAIN.removeCallbacks(TICK);
        long delay;
        synchronized (SleepTimer.class) {
            delay = CORE.nextDelay(SystemClock.elapsedRealtime());
        }
        if (delay >= 0) MAIN.postDelayed(TICK, delay);
    }

    private static final String TAG = "SleepTimer";

    private static void tick() {
        SleepTimerCore.Event event;
        synchronized (SleepTimer.class) {
            event = CORE.poll(SystemClock.elapsedRealtime());
        }
        Actor a = actor;
        if (event != SleepTimerCore.Event.NONE) Log.i(TAG, event + (a == null ? " (no actor)" : ""));
        if (event == SleepTimerCore.Event.WARN && a != null) {
            a.warn(EXTEND_MINUTES, () -> {
                Log.i(TAG, "extended by " + EXTEND_MINUTES + " min");
                set(app, EXTEND_MINUTES);
            });
        } else if (event == SleepTimerCore.Event.FIRE) {
            cancelSchedule();
            if (a != null) a.sleepNow();
            return;
        }
        reschedule();
    }
}
