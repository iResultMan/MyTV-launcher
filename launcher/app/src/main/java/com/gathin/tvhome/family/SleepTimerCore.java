package com.gathin.tvhome.family;

/**
 * The sleep timer's arithmetic with no Android in it: "turn the TV off in N minutes", a warning one minute before, and a
 * cycle for a single remote key (off, 15, 30, 60, 90, off). The caller passes a monotonic clock in milliseconds.
 */
public final class SleepTimerCore {
    public static final int[] CHOICES = {15, 30, 60, 90};
    public static final long WARN_MS = 60_000L;

    public enum Event { NONE, WARN, FIRE }

    private long fireAt = -1;
    private int minutes;
    private boolean warned;

    public boolean armed() {
        return fireAt >= 0;
    }

    /** The minutes chosen last, 0 when the timer is off or was set in seconds. */
    public int minutes() {
        return armed() ? minutes : 0;
    }

    /** Starts (or restarts) the countdown from now; a value that is not a positive number of minutes turns it off. */
    public void arm(long now, int minutes) {
        if (minutes <= 0) {
            cancel();
            return;
        }
        this.minutes = minutes;
        fireAt = now + minutes * 60_000L;
        warned = false;
    }

    /** The same for a delay in seconds (a parent's command); the remote key's cycle then starts over from off. */
    public void armSeconds(long now, int seconds) {
        if (seconds <= 0) {
            cancel();
            return;
        }
        this.minutes = 0;
        fireAt = now + seconds * 1000L;
        warned = false;
    }

    public void cancel() {
        fireAt = -1;
        minutes = 0;
        warned = false;
    }

    /** Whole minutes left, rounded up, 0 when off. */
    public int remainingMinutes(long now) {
        if (!armed()) return 0;
        long left = Math.max(0, fireAt - now);
        return (int) ((left + 59_999L) / 60_000L);
    }

    /** What is due now: the warning once, then the fire once (which also turns the timer off). */
    public Event poll(long now) {
        if (!armed()) return Event.NONE;
        if (now >= fireAt) {
            cancel();
            return Event.FIRE;
        }
        if (!warned && fireAt - now <= WARN_MS) {
            warned = true;
            return Event.WARN;
        }
        return Event.NONE;
    }

    /** Milliseconds until poll() has something to report, -1 when the timer is off. */
    public long nextDelay(long now) {
        if (!armed()) return -1;
        long toFire = Math.max(0, fireAt - now);
        if (warned) return toFire;
        return Math.max(0, Math.min(toFire, fireAt - WARN_MS - now));
    }

    /**
     * Up (+1) or Down (-1) while the time left is on screen: the next choice above or below the minutes left (the
     * number shown), round a ring that has "off" between the highest choice and the lowest (Up from the highest turns
     * the timer off, Up from off starts the lowest, Down from off starts the highest). The countdown starts over from
     * that choice. Returns the minutes.
     */
    public int step(long now, int direction) {
        int current = remainingMinutes(now);
        int last = CHOICES[CHOICES.length - 1];
        int next;
        if (direction > 0) {
            next = current >= last ? 0 : last;
            for (int c : CHOICES) {
                if (c > current) {
                    next = c;
                    break;
                }
            }
        } else {
            next = current == 0 ? last : 0;
            for (int c : CHOICES) {
                if (c < current) next = c;
            }
        }
        arm(now, next);
        return next;
    }

    /** The key's cycle: off, then each choice in turn, then off again; the first choice from anything else. */
    public int cycle(long now) {
        int next = 0;
        if (!armed()) {
            next = CHOICES[0];
        } else {
            for (int i = 0; i < CHOICES.length - 1; i++) {
                if (CHOICES[i] == minutes) {
                    next = CHOICES[i + 1];
                    break;
                }
            }
        }
        arm(now, next);
        return next;
    }
}
