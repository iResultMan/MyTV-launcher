package com.gathin.tvhome.family;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ScreenTimeTest {
    private static final ZoneId Z = ZoneId.of("Europe/Moscow");
    private static final long MIN = 60_000L, H = 60 * MIN;
    private static final String OWN = "com.gathin.tvhome";

    private static long at(int d, int h, int m) {
        return ZonedDateTime.of(2026, 11, d, h, m, 0, 0, Z).toInstant().toEpochMilli();
    }

    private static ScreenTime.Event e(long t, char k, String pkg, String label) {
        return new ScreenTime.Event(t, k, pkg, label);
    }

    private static List<ScreenTime.Day> run(long now, int days, ScreenTime.Event... ev) {
        return ScreenTime.days(new ArrayList<>(Arrays.asList(ev)), now, Z, days, OWN);
    }

    @Test
    public void onPeriodsAndAppsAreSummedPerDay() {
        List<ScreenTime.Day> d = run(at(1, 23, 0), 1,
                e(at(1, 10, 0), 'S', "", ""), e(at(1, 10, 0), 'A', "ru.ivi.client", "Ivi"),
                e(at(1, 12, 0), 'A', "ru.more.play", "Okko"), e(at(1, 13, 0), 'O', "", ""),
                e(at(1, 15, 0), 'S', "", ""), e(at(1, 15, 0), 'A', "ru.ivi.client", "Ivi"), e(at(1, 15, 30), 'O', "", ""));
        assertEquals(1, d.size());
        assertEquals(3 * H + 30 * MIN, d.get(0).totalMs);
        assertEquals("ru.ivi.client", d.get(0).apps.get(0).pkg);
        assertEquals(2 * H + 30 * MIN, d.get(0).apps.get(0).ms);
        assertEquals(H, d.get(0).apps.get(1).ms);
    }

    @Test
    public void aPeriodOverMidnightIsCutInTwo() {
        List<ScreenTime.Day> d = run(at(2, 12, 0), 2,
                e(at(1, 22, 0), 'S', "", ""), e(at(1, 22, 0), 'A', "ru.ivi.client", "Ivi"), e(at(2, 1, 0), 'O', "", ""));
        assertEquals(2 * H, d.get(0).totalMs);
        assertEquals(H, d.get(1).totalMs);
        assertEquals(H, d.get(1).apps.get(0).ms);
    }

    @Test
    public void anOpenPeriodRunsToNow() {
        List<ScreenTime.Day> d = run(at(1, 21, 0), 1, e(at(1, 20, 0), 'S', "", ""), e(at(1, 20, 0), 'A', "x.y", "XY"));
        assertEquals(H, d.get(0).totalMs);
        assertEquals(H, d.get(0).apps.get(0).ms);
    }

    @Test
    public void theLaunchersOwnScreenCountsButIsNotListed() {
        List<ScreenTime.Day> d = run(at(1, 12, 0), 1,
                e(at(1, 10, 0), 'S', "", ""), e(at(1, 10, 0), 'A', OWN, "Home"), e(at(1, 11, 0), 'A', "x.y", "XY"), e(at(1, 11, 30), 'O', "", ""));
        assertEquals(90 * MIN, d.get(0).totalMs);
        assertEquals(1, d.get(0).apps.size());
        assertEquals(30 * MIN, d.get(0).apps.get(0).ms);
    }

    @Test
    public void daysWithoutUseAreListedAndTheOrderIsOldestFirst() {
        List<ScreenTime.Day> d = run(at(3, 12, 0), 3, e(at(2, 10, 0), 'S', "", ""), e(at(2, 11, 0), 'O', "", ""));
        assertEquals(3, d.size());
        assertEquals("2026-11-01", d.get(0).date.toString());
        assertEquals(0, d.get(0).totalMs);
        assertEquals(H, d.get(1).totalMs);
        assertEquals(0, d.get(2).totalMs);
    }

    @Test
    public void jsonHasSecondsAndAtMostFiveApps() {
        List<ScreenTime.Event> ev = new ArrayList<>();
        ev.add(e(at(1, 10, 0), 'S', "", ""));
        for (int i = 0; i < 7; i++) ev.add(e(at(1, 10, 0) + i * 10 * MIN, 'A', "p" + i, "App\"" + i));
        ev.add(e(at(1, 12, 0), 'O', "", ""));
        String j = ScreenTime.json(ScreenTime.days(ev, at(1, 23, 0), Z, 1, OWN));
        assertTrue(j.startsWith("[{\"day\":\"2026-11-01\",\"s\":7200,\"apps\":[{"));
        assertEquals(5, j.split("\"p\":", -1).length - 1);
        assertTrue(!j.contains("App\""));
    }
}
