package com.gathin.tvhome.family;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

public class KidsPolicyTest {
    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final long MIN = 60_000L;

    private static long at(ZoneId zone, String local) {
        return LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli();
    }

    private static KidsSchedule.Window w(String text) {
        KidsSchedule.Window w = KidsSchedule.parseWindow(text);
        if (w == null) throw new AssertionError(text);
        return w;
    }

    private static KidsSchedule schedule(ZoneId zone, String[] weekdays, String[] weekends, int limitMin) {
        return new KidsSchedule(true, zone, windows(weekdays), windows(weekends), limitMin, new int[] {10, 5, 1});
    }

    private static java.util.List<KidsSchedule.Window> windows(String[] texts) {
        if (texts == null) return null;
        java.util.List<KidsSchedule.Window> out = new java.util.ArrayList<>();
        for (String t : texts) out.add(w(t));
        return out;
    }

    // 2026-10-26 is a Monday, 2026-10-31 a Saturday, 2026-11-01 a Sunday.

    @Test public void offAllowsEverything() {
        KidsPolicy.Decision d = new KidsPolicy(KidsSchedule.off()).evaluate(at(MSK, "2026-10-27T03:00"), 0, 0);
        assertTrue(d.allowed);
        assertEquals(-1, d.leftMs);
    }

    @Test public void insideAWindowCountsDownToItsEnd() {
        KidsPolicy p = new KidsPolicy(schedule(MSK, new String[] {"07:00-20:30"}, new String[] {"08:00-22:00"}, 0));
        KidsPolicy.Decision d = p.evaluate(at(MSK, "2026-10-27T20:00"), 0, 0);
        assertTrue(d.allowed);
        assertEquals(KidsPolicy.Reason.ALLOWED, d.reason);
        assertEquals(30 * MIN, d.leftMs);
    }

    @Test public void theEndOfAWindowIsExclusiveAndTheStartInclusive() {
        KidsPolicy p = new KidsPolicy(schedule(MSK, new String[] {"07:00-20:30"}, new String[] {"08:00-22:00"}, 0));
        assertFalse(p.evaluate(at(MSK, "2026-10-27T20:30"), 0, 0).allowed);
        assertTrue(p.evaluate(at(MSK, "2026-10-27T20:29"), 0, 0).allowed);
        assertTrue(p.evaluate(at(MSK, "2026-10-27T07:00"), 0, 0).allowed);
        assertFalse(p.evaluate(at(MSK, "2026-10-27T06:59"), 0, 0).allowed);
    }

    @Test public void outsideAWindowSaysWhenTheNextOneOpens() {
        KidsPolicy p = new KidsPolicy(schedule(MSK, new String[] {"07:00-20:30"}, new String[] {"08:00-22:00"}, 0));
        KidsPolicy.Decision d = p.evaluate(at(MSK, "2026-10-27T22:00"), 0, 0);
        assertFalse(d.allowed);
        assertEquals(KidsPolicy.Reason.OUTSIDE_WINDOW, d.reason);
        assertEquals(at(MSK, "2026-10-28T07:00"), d.opensAtMs);
    }

    @Test public void fridayNightOpensOnSaturdayMorningByTheWeekendList() {
        KidsPolicy p = new KidsPolicy(schedule(MSK, new String[] {"07:00-20:30"}, new String[] {"09:00-22:00"}, 0));
        KidsPolicy.Decision d = p.evaluate(at(MSK, "2026-10-30T21:00"), 0, 0);
        assertFalse(d.allowed);
        assertEquals(at(MSK, "2026-10-31T09:00"), d.opensAtMs);
    }

    @Test public void weekendsUseTheirOwnWindows() {
        KidsPolicy p = new KidsPolicy(schedule(MSK, new String[] {"07:00-20:30"}, new String[] {"09:00-22:00"}, 0));
        assertTrue(p.evaluate(at(MSK, "2026-10-31T21:30"), 0, 0).allowed);
        assertTrue(p.evaluate(at(MSK, "2026-11-01T21:30"), 0, 0).allowed);
        assertFalse(p.evaluate(at(MSK, "2026-11-01T08:30"), 0, 0).allowed);
        assertFalse(p.evaluate(at(MSK, "2026-10-26T21:30"), 0, 0).allowed);
    }

    @Test public void aWindowOverMidnightBelongsToTheDayItStarts() {
        KidsPolicy p = new KidsPolicy(schedule(MSK, new String[] {"06:00-21:00"}, new String[] {"09:00-01:30"}, 0));
        // Saturday 00:30 is still Friday's window? No: Friday is a weekday, so it ends at 21:00.
        assertFalse(p.evaluate(at(MSK, "2026-10-31T00:30"), 0, 0).allowed);
        // Sunday 00:30 belongs to Saturday's weekend window.
        KidsPolicy.Decision d = p.evaluate(at(MSK, "2026-11-01T00:30"), 0, 0);
        assertTrue(d.allowed);
        assertEquals(60 * MIN, d.leftMs);
        assertFalse(p.evaluate(at(MSK, "2026-11-01T01:30"), 0, 0).allowed);
        // Sunday 09:00 opens Sunday's own window, which runs into Monday 01:30.
        assertTrue(p.evaluate(at(MSK, "2026-11-01T23:00"), 0, 0).allowed);
        assertTrue(p.evaluate(at(MSK, "2026-11-02T01:00"), 0, 0).allowed);
    }

    @Test public void midnightEndsAWindowAtTheStartOfTheNextDay() {
        KidsPolicy p = new KidsPolicy(schedule(MSK, new String[] {"18:00-24:00"}, new String[] {"18:00-24:00"}, 0));
        KidsPolicy.Decision d = p.evaluate(at(MSK, "2026-10-27T23:30"), 0, 0);
        assertTrue(d.allowed);
        assertEquals(30 * MIN, d.leftMs);
        assertFalse(p.evaluate(at(MSK, "2026-10-28T00:00"), 0, 0).allowed);
    }

    @Test public void touchingWindowsAreOneStretch() {
        KidsPolicy p = new KidsPolicy(schedule(MSK, new String[] {"07:00-12:00", "12:00-20:00"}, new String[] {"07:00-20:00"}, 0));
        assertEquals(8 * 60 * MIN, p.evaluate(at(MSK, "2026-10-27T12:00"), 0, 0).leftMs);
        // A gap is not one.
        KidsPolicy gap = new KidsPolicy(schedule(MSK, new String[] {"07:00-12:00", "15:00-20:00"}, new String[] {"07:00-20:00"}, 0));
        assertEquals(60 * MIN, gap.evaluate(at(MSK, "2026-10-27T11:00"), 0, 0).leftMs);
        assertFalse(gap.evaluate(at(MSK, "2026-10-27T13:00"), 0, 0).allowed);
    }

    @Test public void aDayWithNoWindowsSetIsUnrestrictedAndAnEmptyListBlocksTheDay() {
        KidsPolicy open = new KidsPolicy(schedule(MSK, new String[] {"07:00-20:00"}, null, 0));
        KidsPolicy.Decision sat = open.evaluate(at(MSK, "2026-10-31T23:00"), 0, 0);
        assertTrue(sat.allowed);
        assertEquals(-1, sat.leftMs);
        KidsPolicy shut = new KidsPolicy(schedule(MSK, new String[] {"07:00-20:00"}, new String[0], 0));
        assertFalse(shut.evaluate(at(MSK, "2026-10-31T12:00"), 0, 0).allowed);
        assertEquals(at(MSK, "2026-11-02T07:00"), shut.evaluate(at(MSK, "2026-10-31T12:00"), 0, 0).opensAtMs);
    }

    @Test public void theDailyLimitCountsDownAndLiftsTomorrow() {
        KidsPolicy p = new KidsPolicy(schedule(MSK, new String[] {"07:00-12:00", "15:00-20:00"}, new String[] {"07:00-20:00"}, 120));
        KidsPolicy.Decision d = p.evaluate(at(MSK, "2026-10-27T10:00"), 100 * MIN, 0);
        assertTrue(d.allowed);
        assertEquals(20 * MIN, d.leftMs);
        KidsPolicy.Decision out = p.evaluate(at(MSK, "2026-10-27T10:00"), 120 * MIN, 0);
        assertFalse(out.allowed);
        assertEquals(KidsPolicy.Reason.LIMIT_REACHED, out.reason);
        // Not 15:00 today: the limit is for the day.
        assertEquals(at(MSK, "2026-10-28T07:00"), out.opensAtMs);
    }

    @Test public void theLimitWithNoWindowsOpensAtMidnight() {
        KidsPolicy p = new KidsPolicy(schedule(MSK, null, null, 60));
        KidsPolicy.Decision out = p.evaluate(at(MSK, "2026-10-27T18:00"), 60 * MIN, 0);
        assertFalse(out.allowed);
        assertEquals(at(MSK, "2026-10-28T00:00"), out.opensAtMs);
        assertEquals(at(MSK, "2026-10-27T00:00"), p.dayStartMs(at(MSK, "2026-10-27T18:00")));
    }

    @Test public void theEarlierOfWindowAndLimitEndsIt() {
        KidsPolicy p = new KidsPolicy(schedule(MSK, new String[] {"07:00-20:00"}, new String[] {"07:00-20:00"}, 120));
        assertEquals(30 * MIN, p.evaluate(at(MSK, "2026-10-27T19:30"), 10 * MIN, 0).leftMs);
        assertEquals(30 * MIN, p.evaluate(at(MSK, "2026-10-27T09:00"), 90 * MIN, 0).leftMs);
    }

    @Test public void aBonusLiftsWindowsAndLimitUntilItEnds() {
        KidsPolicy p = new KidsPolicy(schedule(MSK, new String[] {"07:00-20:00"}, new String[] {"07:00-20:00"}, 120));
        long now = at(MSK, "2026-10-27T21:00");
        KidsPolicy.Decision d = p.evaluate(now, 500 * MIN, now + 15 * MIN);
        assertTrue(d.allowed);
        assertEquals(KidsPolicy.Reason.BONUS, d.reason);
        assertEquals(15 * MIN, d.leftMs);
        assertFalse(p.evaluate(now + 15 * MIN, 500 * MIN, now + 15 * MIN).allowed);
    }

    @Test public void aTimeZoneIsTheSchedulesNotTheDevices() {
        KidsPolicy p = new KidsPolicy(schedule(MSK, new String[] {"07:00-20:00"}, new String[] {"07:00-20:00"}, 0));
        long moscow19 = at(MSK, "2026-10-27T19:00");
        assertTrue(p.evaluate(moscow19, 0, 0).allowed);
        assertEquals(60 * MIN, p.evaluate(moscow19, 0, 0).leftMs);
    }

    @Test public void springForwardMakesANightWindowAnHourShorter() {
        // Europe/Berlin jumps from 02:00 to 03:00 on 2026-03-29 (a Sunday); a Saturday window 22:00-07:00.
        KidsPolicy p = new KidsPolicy(schedule(BERLIN, new String[] {"07:00-20:00"}, new String[] {"22:00-07:00"}, 0));
        long start = at(BERLIN, "2026-03-28T22:00");
        KidsPolicy.Decision d = p.evaluate(start, 0, 0);
        assertTrue(d.allowed);
        assertEquals(8 * 60 * MIN, d.leftMs);   // 9 clock hours, 8 real ones
        assertFalse(p.evaluate(at(BERLIN, "2026-03-29T07:00"), 0, 0).allowed);
        assertTrue(p.evaluate(at(BERLIN, "2026-03-29T06:59"), 0, 0).allowed);
    }

    @Test public void fallBackMakesANightWindowAnHourLonger() {
        // 2026-10-25 (a Sunday): 03:00 goes back to 02:00.
        KidsPolicy p = new KidsPolicy(schedule(BERLIN, new String[] {"07:00-20:00"}, new String[] {"22:00-07:00"}, 0));
        KidsPolicy.Decision d = p.evaluate(at(BERLIN, "2026-10-24T22:00"), 0, 0);
        assertEquals(10 * 60 * MIN, d.leftMs);
    }

    @Test public void windowTextParses() {
        assertEquals(new KidsSchedule.Window(420, 1230), KidsSchedule.parseWindow("07:00-20:30"));
        assertEquals(new KidsSchedule.Window(1320, 420), KidsSchedule.parseWindow(" 22:00 - 07:00 "));
        assertEquals(new KidsSchedule.Window(1080, 1440), KidsSchedule.parseWindow("18:00-24:00"));
        assertEquals(new KidsSchedule.Window(1080, 1440), KidsSchedule.parseWindow("18:00-00:00"));
        assertNull(KidsSchedule.parseWindow("07:00-07:00"));
        assertNull(KidsSchedule.parseWindow("25:00-07:00"));
        assertNull(KidsSchedule.parseWindow("07:60-08:00"));
        assertNull(KidsSchedule.parseWindow("evening"));
        assertNull(KidsSchedule.parseWindow(null));
        assertEquals("07:00-20:30", KidsSchedule.parseWindow("7:00-20:30").toString());
    }

    @Test public void warningsFireOnceEachAsTheTimeGoesUnderThem() {
        int[] warn = {10, 5, 1};
        assertEquals(-1, KidsPolicy.warningDue(11 * MIN, 10 * MIN + 1, warn));
        assertEquals(10, KidsPolicy.warningDue(10 * MIN + 30_000, 10 * MIN, warn));
        assertEquals(-1, KidsPolicy.warningDue(10 * MIN, 9 * MIN, warn));
        assertEquals(5, KidsPolicy.warningDue(5 * MIN + 1, 5 * MIN, warn));
        assertEquals(1, KidsPolicy.warningDue(2 * MIN, 59_000, warn));
        // A check that skips over two: the closest to the end is the one that counts.
        assertEquals(5, KidsPolicy.warningDue(12 * MIN, 4 * MIN, warn));
        // Nothing ends it, or it already ended: no warning.
        assertEquals(-1, KidsPolicy.warningDue(-1, 5 * MIN, warn));
        assertEquals(-1, KidsPolicy.warningDue(3 * MIN, -1, warn));
        assertEquals(-1, KidsPolicy.warningDue(MIN, 0, warn));
    }
}
