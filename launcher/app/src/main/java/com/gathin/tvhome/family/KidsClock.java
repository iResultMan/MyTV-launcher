package com.gathin.tvhome.family;

import java.util.function.LongSupplier;

/**
 * The time the kids' schedule runs on: wall clock when it can be believed, otherwise the last believed time plus the
 * monotonic clock (which a child cannot set). The wall clock is followed while it agrees with the monotonic one; a jump
 * either way while the TV is running is ignored and reported by tampered(). After a restart the monotonic clock starts
 * over, so the last believed time is kept in a Store; a wall clock behind it (no battery clock, no network time yet) is
 * not believed until it has caught up, and then it is adopted once.
 *
 * A clock set forward and then a restart cannot be told from a TV that was simply off; the phone's lock stays the
 * answer to that. A parent who really changed the clock calls adopt().
 */
public final class KidsClock {
    /** Where the last believed time survives a restart. */
    public interface Store {
        long lastTime();

        void saveLastTime(long wallMs);
    }

    /** How far the wall clock may differ from the monotonic one and still count as the same time. */
    static final long TOLERANCE_MS = 2 * 60_000L;
    /** The stored time is refreshed at most this often. */
    private static final long SAVE_EVERY_MS = 60_000L;

    private final LongSupplier wall, elapsed;
    private final Store store;
    private long anchorWall, anchorElapsed, saved;
    /** False from a start whose wall clock is behind the stored time, until it catches up. */
    private boolean settled;
    private boolean tampered;

    public KidsClock(LongSupplier wall, LongSupplier elapsed, Store store) {
        this.wall = wall;
        this.elapsed = elapsed;
        this.store = store;
        long w = wall.getAsLong();
        long last = store.lastTime();
        anchorElapsed = elapsed.getAsLong();
        if (w + TOLERANCE_MS >= last) {
            anchorWall = w;
            settled = true;
        } else {
            anchorWall = last;
            settled = false;
        }
        saved = anchorWall;
    }

    /** The believed time, in ms since the epoch. */
    public synchronized long now() {
        long e = elapsed.getAsLong();
        long believed = anchorWall + (e - anchorElapsed);
        long w = wall.getAsLong();
        long diff = w - believed;
        if (!settled) {
            if (diff >= -TOLERANCE_MS) {
                // The wall clock has caught up with the stored time: the first network sync, or the TV was off a while.
                settled = true;
                tampered = false;
                anchorWall = w;
                anchorElapsed = e;
                believed = w;
            }
        } else if (Math.abs(diff) <= TOLERANCE_MS) {
            tampered = false;
            anchorWall = w;
            anchorElapsed = e;
            believed = w;
        } else {
            tampered = true;
        }
        if (believed - saved >= SAVE_EVERY_MS) {
            store.saveLastTime(believed);
            saved = believed;
        }
        return believed;
    }

    /** True while the wall clock disagrees with the monotonic one by more than the tolerance and is not followed. */
    public synchronized boolean tampered() {
        now();
        return tampered;
    }

    /** True until the wall clock is believed after a start (nothing shows the real time yet). */
    public synchronized boolean unsettled() {
        now();
        return !settled;
    }

    /** A parent confirms the wall clock: it is followed from now on, whatever it says. */
    public synchronized void adopt() {
        anchorWall = wall.getAsLong();
        anchorElapsed = elapsed.getAsLong();
        settled = true;
        tampered = false;
        store.saveLastTime(anchorWall);
        saved = anchorWall;
    }
}
