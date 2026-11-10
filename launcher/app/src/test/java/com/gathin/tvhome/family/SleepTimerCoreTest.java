package com.gathin.tvhome.family;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SleepTimerCoreTest {
    private static final long MIN = 60_000L;

    @Test
    public void offByDefault() {
        SleepTimerCore t = new SleepTimerCore();
        assertFalse(t.armed());
        assertEquals(0, t.minutes());
        assertEquals(SleepTimerCore.Event.NONE, t.poll(1_000_000L));
        assertEquals(-1, t.nextDelay(0));
    }

    @Test
    public void warnsOnceThenFiresOnceAndTurnsOff() {
        SleepTimerCore t = new SleepTimerCore();
        t.arm(1000, 15);
        assertEquals(SleepTimerCore.Event.NONE, t.poll(1000 + 13 * MIN));
        assertEquals(SleepTimerCore.Event.WARN, t.poll(1000 + 14 * MIN));
        assertEquals(SleepTimerCore.Event.NONE, t.poll(1000 + 14 * MIN + 30_000));
        assertEquals(SleepTimerCore.Event.FIRE, t.poll(1000 + 15 * MIN));
        assertFalse(t.armed());
        assertEquals(SleepTimerCore.Event.NONE, t.poll(1000 + 16 * MIN));
    }

    @Test
    public void lateFirstPollStillFires() {
        SleepTimerCore t = new SleepTimerCore();
        t.arm(0, 15);
        assertEquals(SleepTimerCore.Event.FIRE, t.poll(40 * MIN));
    }

    @Test
    public void extendingRestartsTheCountdownAndTheWarning() {
        SleepTimerCore t = new SleepTimerCore();
        t.arm(0, 15);
        assertEquals(SleepTimerCore.Event.WARN, t.poll(14 * MIN));
        t.arm(14 * MIN + 10_000, 15);
        assertEquals(SleepTimerCore.Event.NONE, t.poll(15 * MIN));
        assertEquals(SleepTimerCore.Event.WARN, t.poll(28 * MIN + 10_000));
        assertEquals(SleepTimerCore.Event.FIRE, t.poll(29 * MIN + 10_000));
    }

    @Test
    public void shortTimerWarnsImmediately() {
        SleepTimerCore t = new SleepTimerCore();
        t.arm(0, 1);
        assertEquals(SleepTimerCore.Event.WARN, t.poll(0));
        assertEquals(SleepTimerCore.Event.FIRE, t.poll(MIN));
    }

    @Test
    public void remainingRoundsUp() {
        SleepTimerCore t = new SleepTimerCore();
        t.arm(0, 30);
        assertEquals(30, t.remainingMinutes(0));
        assertEquals(30, t.remainingMinutes(1));
        assertEquals(29, t.remainingMinutes(MIN));
        assertEquals(1, t.remainingMinutes(30 * MIN - 1));
        assertEquals(0, t.remainingMinutes(30 * MIN));
    }

    @Test
    public void nextDelayPointsAtTheWarningThenTheFire() {
        SleepTimerCore t = new SleepTimerCore();
        t.arm(0, 15);
        assertEquals(14 * MIN, t.nextDelay(0));
        assertEquals(MIN, t.nextDelay(14 * MIN - MIN));   // still before the warning: it is at 14 min
        t.poll(14 * MIN);
        assertEquals(MIN, t.nextDelay(14 * MIN));
        assertEquals(0, t.nextDelay(16 * MIN));
    }

    @Test
    public void cycleGoesThroughTheChoicesAndBackToOff() {
        SleepTimerCore t = new SleepTimerCore();
        assertEquals(15, t.cycle(0));
        assertEquals(30, t.cycle(1));
        assertEquals(60, t.cycle(2));
        assertEquals(90, t.cycle(3));
        assertEquals(0, t.cycle(4));
        assertFalse(t.armed());
        assertEquals(15, t.cycle(5));
    }

    @Test
    public void armWithZeroOrLessTurnsOff() {
        SleepTimerCore t = new SleepTimerCore();
        t.arm(0, 30);
        t.arm(0, 0);
        assertFalse(t.armed());
        t.arm(0, -5);
        assertFalse(t.armed());
    }

    @Test
    public void customMinutesAreKeptAndCycleStartsOver() {
        SleepTimerCore t = new SleepTimerCore();
        t.arm(0, 45);
        assertTrue(t.armed());
        assertEquals(45, t.minutes());
        assertEquals(0, t.cycle(1));
    }

    @Test
    public void secondsDelayFiresOnTimeAndTheKeyCycleStartsOver() {
        SleepTimerCore t = new SleepTimerCore();
        t.armSeconds(0, 90);
        assertEquals(SleepTimerCore.Event.NONE, t.poll(20_000));
        assertEquals(SleepTimerCore.Event.WARN, t.poll(30_000));
        assertEquals(SleepTimerCore.Event.FIRE, t.poll(90_000));
        t.armSeconds(0, 600);
        assertEquals(0, t.minutes());
        assertEquals(0, t.cycle(1));
        t.armSeconds(0, 0);
        assertFalse(t.armed());
    }

    @Test
    public void upAndDownGoRoundTheChoicesWithOffBetweenTheEnds() {
        SleepTimerCore t = new SleepTimerCore();
        assertEquals(15, t.step(0, 1));
        assertEquals(30, t.step(0, 1));
        assertEquals(60, t.step(0, 1));
        assertEquals(90, t.step(0, 1));
        assertEquals(0, t.step(0, 1));
        assertFalse(t.armed());
        assertEquals(15, t.step(0, 1));
        assertEquals(0, t.step(0, -1));
        assertFalse(t.armed());
        assertEquals(90, t.step(0, -1));
        assertEquals(60, t.step(0, -1));
        assertEquals(30, t.step(0, -1));
        assertEquals(15, t.step(0, -1));
        assertEquals(0, t.step(0, -1));
    }

    @Test
    public void aStepRestartsTheCountdown() {
        SleepTimerCore t = new SleepTimerCore();
        t.arm(0, 15);
        // 5 minutes left: Up rounds to 15 and the countdown starts again from 15.
        assertEquals(15, t.step(10 * 60_000L, 1));
        assertEquals(15, t.remainingMinutes(10 * 60_000L));
        assertEquals(14, t.remainingMinutes(11 * 60_000L));
    }

    @Test
    public void aStepRoundsTheTimeLeftToTheNextChoice() {
        long min = 60_000L;
        SleepTimerCore t = new SleepTimerCore();
        t.arm(0, 60);
        // 37 minutes left: Up goes to 60, Down to 30.
        assertEquals(60, t.step(23 * min, 1));
        t.arm(0, 60);
        assertEquals(30, t.step(23 * min, -1));
        // 22 minutes left: Up goes to 30, Down to 15.
        t.arm(0, 30);
        assertEquals(30, t.step(8 * min, 1));
        t.arm(0, 30);
        assertEquals(15, t.step(8 * min, -1));
        // 7 minutes left: Up goes to 15, Down turns it off.
        t.arm(0, 15);
        assertEquals(15, t.step(8 * min, 1));
        t.arm(0, 15);
        assertEquals(0, t.step(8 * min, -1));
        assertFalse(t.armed());
        // 95 minutes left cannot come from a choice, but a parent's delay can: Up turns it off.
        t.armSeconds(0, 95 * 60);
        assertEquals(0, t.step(0, 1));
    }

    @Test
    public void aTimerSetInSecondsRoundsToo() {
        SleepTimerCore t = new SleepTimerCore();
        t.armSeconds(0, 90);
        assertEquals(15, t.step(0, 1));
        t.armSeconds(0, 90);
        assertEquals(0, t.step(0, -1));
        t.armSeconds(0, 40 * 60);
        assertEquals(60, t.step(0, 1));
        t.armSeconds(0, 40 * 60);
        assertEquals(30, t.step(0, -1));
    }
}
