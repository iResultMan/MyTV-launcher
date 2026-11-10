package com.gathin.tvhome.family;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KidsClockTest {
    private static final long MIN = 60_000L;
    private static final long T0 = 1_800_000_000_000L;

    private static final class Fake implements KidsClock.Store {
        long wall = T0, elapsed = 1_000_000, stored;

        Fake() {
            stored = 0;
        }

        @Override public long lastTime() {
            return stored;
        }

        @Override public void saveLastTime(long wallMs) {
            stored = wallMs;
        }

        void advance(long ms) {
            wall += ms;
            elapsed += ms;
        }

        KidsClock clock() {
            return new KidsClock(() -> wall, () -> elapsed, this);
        }
    }

    @Test public void followsTheWallClockWhileTheyAgree() {
        Fake f = new Fake();
        KidsClock c = f.clock();
        f.advance(10 * MIN);
        assertEquals(T0 + 10 * MIN, c.now());
        assertFalse(c.tampered());
    }

    @Test public void aClockSetBackWhileRunningIsNotFollowed() {
        Fake f = new Fake();
        KidsClock c = f.clock();
        f.advance(10 * MIN);
        f.wall -= 5 * 60 * MIN;
        f.advance(MIN);
        assertEquals(T0 + 11 * MIN, c.now());
        assertTrue(c.tampered());
    }

    @Test public void aClockSetForwardWhileRunningIsNotFollowed() {
        Fake f = new Fake();
        KidsClock c = f.clock();
        f.advance(10 * MIN);
        f.wall += 12 * 60 * MIN;
        f.advance(MIN);
        assertEquals(T0 + 11 * MIN, c.now());
        assertTrue(c.tampered());
    }

    @Test public void putBackItIsFollowedAgain() {
        Fake f = new Fake();
        KidsClock c = f.clock();
        f.wall += 12 * 60 * MIN;
        f.advance(MIN);
        assertTrue(c.tampered());
        f.wall -= 12 * 60 * MIN;
        f.advance(MIN);
        assertEquals(T0 + 2 * MIN, c.now());
        assertFalse(c.tampered());
    }

    @Test public void aParentCanAdoptTheWallClock() {
        Fake f = new Fake();
        KidsClock c = f.clock();
        f.wall += 3 * 60 * MIN;
        f.advance(MIN);
        assertTrue(c.tampered());
        c.adopt();
        assertFalse(c.tampered());
        assertEquals(T0 + 3 * 60 * MIN + MIN, c.now());
        f.advance(5 * MIN);
        assertEquals(T0 + 3 * 60 * MIN + 6 * MIN, c.now());
    }

    @Test public void smallDriftIsFollowed() {
        Fake f = new Fake();
        KidsClock c = f.clock();
        f.wall += 90_000;
        f.advance(MIN);
        assertEquals(f.wall, c.now());
        assertFalse(c.tampered());
    }

    @Test public void theLastTimeSurvivesARestartAndABehindClockWaitsForSync() {
        Fake f = new Fake();
        KidsClock c = f.clock();
        f.advance(30 * MIN);
        c.now();
        assertEquals(T0 + 30 * MIN, f.stored);
        // A restart: the monotonic clock starts over and the wall clock is a day behind (no network time yet).
        Fake g = new Fake();
        g.stored = f.stored;
        g.wall = T0 - 24 * 60 * MIN * 60;
        g.elapsed = 5_000;
        KidsClock d = g.clock();
        assertTrue(d.unsettled());
        assertEquals(T0 + 30 * MIN, d.now());
        g.advance(2 * MIN);
        assertEquals(T0 + 32 * MIN, d.now());
        // The network time arrives, 40 minutes after the stored time: believed once.
        g.wall = T0 + 70 * MIN;
        assertEquals(T0 + 70 * MIN, d.now());
        assertFalse(d.unsettled());
        assertFalse(d.tampered());
    }

    @Test public void aRestartWithAGoodClockIsSettledAtOnce() {
        Fake g = new Fake();
        g.stored = T0 - 3 * 60 * MIN;
        KidsClock d = g.clock();
        assertFalse(d.unsettled());
        assertEquals(T0, d.now());
    }

    @Test public void afterSettlingAJumpIsTampering() {
        Fake g = new Fake();
        g.stored = T0 + 60 * MIN;
        g.wall = T0;
        KidsClock d = g.clock();
        g.wall = T0 + 61 * MIN;
        g.elapsed += MIN;
        d.now();
        assertFalse(d.unsettled());
        g.wall -= 10 * 60 * MIN;
        g.advance(MIN);
        assertTrue(d.tampered());
    }

    @Test public void theStoredTimeIsWrittenOncePerMinuteAtMost() {
        Fake f = new Fake();
        final int[] writes = {0};
        KidsClock c = new KidsClock(() -> f.wall, () -> f.elapsed, new KidsClock.Store() {
            @Override public long lastTime() {
                return 0;
            }

            @Override public void saveLastTime(long wallMs) {
                writes[0]++;
            }
        });
        for (int i = 0; i < 10; i++) {
            f.advance(10_000);
            c.now();
        }
        assertTrue("writes " + writes[0], writes[0] <= 2);
    }
}
