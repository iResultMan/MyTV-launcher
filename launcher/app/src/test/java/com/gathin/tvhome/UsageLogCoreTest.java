package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class UsageLogCoreTest {
    private static UsageLogCore.Event ev(long t, char k, String p, String l) {
        return new UsageLogCore.Event(t, k, p, l);
    }

    @Test public void aLineRoundTripsAndDamagedOnesAreSkipped() {
        UsageLogCore.Event e = UsageLogCore.parse(UsageLogCore.line(ev(1700000000000L, 'A', "ru.kinopoisk.tv", "Kinopoisk")));
        assertNotNull(e);
        assertEquals(1700000000000L, e.t);
        assertEquals('A', e.k);
        assertEquals("ru.kinopoisk.tv", e.pkg);
        assertEquals("Kinopoisk", e.label);
        assertNull(UsageLogCore.parse("garbage"));
        assertNull(UsageLogCore.parse("12\tX\t\t"));
        assertNull(UsageLogCore.parse("abc\tS\t\t"));
        assertEquals('S', UsageLogCore.parse("42\tS").k);
        assertEquals("a b", UsageLogCore.parse(UsageLogCore.line(ev(1, 'A', "p", "a\tb"))).label);
    }

    @Test public void jsonHasTheStateAndOnlyTheEventsAfterSince() {
        UsageLogCore c = new UsageLogCore(1000000, 100);
        c.add(ev(10, 'S', "", ""));
        c.add(ev(11, 'A', "com.google.android.youtube.tv", "You\"Tube"));
        c.add(ev(20, 'O', "", ""));
        String all = c.json(0, 99, true, 10, "com.google.android.youtube.tv", "YouTube");
        assertTrue(all.startsWith("{\"now\":99,\"on\":true,\"onSince\":10,\"pkg\":\"com.google.android.youtube.tv\",\"label\":\"YouTube\",\"events\":["));
        assertTrue(all.contains("{\"t\":10,\"k\":\"S\"}"));
        assertTrue(all.contains("{\"t\":11,\"k\":\"A\",\"p\":\"com.google.android.youtube.tv\",\"l\":\"You\\\"Tube\"}"));
        String tail = c.json(11, 99, false, 0, "", "");
        assertFalse(tail.contains("\"t\":10"));
        assertFalse(tail.contains("\"t\":11"));
        assertTrue(tail.contains("{\"t\":20,\"k\":\"O\"}"));
        assertTrue(c.json(20, 99, false, 0, "", "").endsWith("\"events\":[]}"));
    }

    @Test public void trimDropsOldAndKeepsTheNewestWithinTheCap() {
        UsageLogCore c = new UsageLogCore(100, 3);
        for (int i = 0; i < 10; i++) c.add(ev(1000 + i * 10, 'A', "p" + i, ""));
        c.trim(1100);                 // older than 100 ms before now: those at 1000 only (1100 - 1000 = 100 is not over)
        assertEquals(3, c.size());
        assertEquals("p9", c.last().pkg);
        assertEquals("p7", UsageLogCore.parse(c.lines().split("\n")[0]).pkg);
    }

    @Test public void lastOfKindsSkipsAppEvents() {
        UsageLogCore c = new UsageLogCore(1000, 10);
        assertNull(c.lastOfKinds("SO"));
        c.add(ev(1, 'S', "", ""));
        c.add(ev(2, 'A', "p", "P"));
        assertEquals('S', c.lastOfKinds("SO").k);
        c.add(ev(3, 'O', "", ""));
        assertEquals('O', c.lastOfKinds("SO").k);
    }

    @Test public void sinceIsReadFromTheQuery() {
        assertEquals(0, UsageLogCore.sinceOf("/k/t/usage"));
        assertEquals(77, UsageLogCore.sinceOf("/k/t/usage?since=77"));
        assertEquals(77, UsageLogCore.sinceOf("/k/t/usage?x=1&since=77"));
        assertEquals(0, UsageLogCore.sinceOf("/k/t/usage?since=oops"));
        assertEquals(0, UsageLogCore.sinceOf("/k/t/usage?since=-5"));
    }

    @Test public void androidsOwnEventsBecomeOurs() {
        String own = "com.gathin.tvhome";
        java.util.function.Function<String, String> name = p -> p.equals("com.google.android.youtube.tv") ? "YouTube" : p;
        assertEquals('S', UsageLogCore.fromSystem(15, 5, "android", "", own, name).k);
        assertEquals('O', UsageLogCore.fromSystem(16, 6, "android", "", own, name).k);
        assertEquals('O', UsageLogCore.fromSystem(26, 7, "android", "", own, name).k);
        UsageLogCore.Event a = UsageLogCore.fromSystem(1, 8, "com.google.android.youtube.tv", "x.Main", own, name);
        assertEquals('A', a.k);
        assertEquals("YouTube", a.label);
        assertNull(UsageLogCore.fromSystem(2, 9, "com.google.android.youtube.tv", "x.Main", own, name));       // ACTIVITY_PAUSED
        assertNull(UsageLogCore.fromSystem(1, 9, "com.google.android.tvlauncher", "x.Main", own, name));       // the stock home
        assertNull(UsageLogCore.fromSystem(1, 9, "com.tcl.keycustomfunctionservice", "x.FallbackHome", own, name));
        assertNull(UsageLogCore.fromSystem(31, 9, "x", "y", own, name));
    }

    @Test public void renderWorksOnAnyList() {
        java.util.List<UsageLogCore.Event> l = new java.util.ArrayList<>();
        l.add(ev(10, 'S', "", ""));
        l.add(ev(11, 'A', "p", "P"));
        String s = UsageLogCore.render(l, 10, 50, true, 10, "p", "P");
        assertFalse(s.contains("\"t\":10"));
        assertTrue(s.contains("\"t\":11"));
    }

    @Test public void onlyRealAppsCount() {
        String own = "com.gathin.tvhome";
        assertTrue(UsageLogCore.countsAsApp("com.google.android.youtube.tv", "x.Main", own));
        assertTrue(UsageLogCore.countsAsApp(own, "com.gathin.tvhome.MainActivity", own));
        assertFalse(UsageLogCore.countsAsApp(own, "android.widget.LinearLayout", own));      // the message overlay
        assertFalse(UsageLogCore.countsAsApp("android", "x", own));
        assertFalse(UsageLogCore.countsAsApp("com.android.systemui", "x", own));
        assertFalse(UsageLogCore.countsAsApp("com.google.android.inputmethod.latin", "x", own));
        assertFalse(UsageLogCore.countsAsApp("", "x", own));
        assertFalse(UsageLogCore.countsAsApp(null, "x", own));
    }
}
