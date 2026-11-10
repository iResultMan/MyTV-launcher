package com.gathin.tvhome.family;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Screen time per day from the usage events (screen on S, screen off O, app to the front A), the same sums the home
 * server makes: a screen-on period runs from S to O, an open one to now, and an app counts from its A to the next A or
 * to the end of the period. Days are cut at the midnights of {@code zone}.
 */
public final class ScreenTime {
    public static final class Event {
        public final long t;
        public final char k;
        public final String pkg, label;

        public Event(long t, char k, String pkg, String label) {
            this.t = t;
            this.k = k;
            this.pkg = pkg == null ? "" : pkg;
            this.label = label == null ? "" : label;
        }
    }

    public static final class App {
        public final String pkg, label;
        public long ms;

        App(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    public static final class Day {
        public final LocalDate date;
        public long totalMs;
        /** Most watched first; the launcher's own screen is in totalMs but not listed. */
        public final List<App> apps = new ArrayList<>();

        Day(LocalDate date) {
            this.date = date;
        }
    }

    private ScreenTime() {
    }

    /**
     * @param events ascending by time
     * @param own    the launcher's package: its screen counts as screen time but is not listed as an app
     * @return the last {@code days} days up to the one of nowMs, oldest first, a day with no use included
     */
    public static List<Day> days(List<Event> events, long nowMs, ZoneId zone, int days, String own) {
        List<long[]> periods = new ArrayList<>();
        List<Object[]> segments = new ArrayList<>();      // {from, to, pkg, label}
        boolean on = false, seen = false;
        long pstart = 0, astart = 0;
        String curPkg = null, curLabel = "";
        for (Event e : events) {
            if (e.k == 'A') {
                String p = e.pkg, l = e.label;
                boolean known = !p.isEmpty() || !l.isEmpty();
                if (!seen) {                                  // logging began while the TV was on
                    seen = on = true;
                    pstart = astart = e.t;
                } else if (on) {
                    closeApp(segments, curPkg, curLabel, astart, e.t);
                    astart = e.t;
                }
                curPkg = known ? p : null;
                curLabel = l;
            } else if (e.k == 'S') {
                seen = true;
                if (!on) {
                    on = true;
                    pstart = astart = e.t;
                }
            } else {
                seen = true;
                if (on) {
                    closeApp(segments, curPkg, curLabel, astart, e.t);
                    periods.add(new long[] {pstart, e.t});
                    on = false;
                }
            }
        }
        if (on) {
            long end = Math.max(nowMs, pstart);
            closeApp(segments, curPkg, curLabel, astart, end);
            periods.add(new long[] {pstart, end});
        }

        LocalDate today = localDate(nowMs, zone);
        List<Day> out = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            long lo = date.atStartOfDay(zone).toInstant().toEpochMilli();
            long hi = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            Day d = new Day(date);
            for (long[] p : periods) d.totalMs += clip(p[0], p[1], lo, hi);
            Map<String, App> byApp = new LinkedHashMap<>();
            for (Object[] s : segments) {
                long ms = clip((Long) s[0], (Long) s[1], lo, hi);
                String pkg = (String) s[2];
                if (ms == 0 || pkg.equals(own)) continue;
                App a = byApp.get(pkg);
                if (a == null) byApp.put(pkg, a = new App(pkg, (String) s[3]));
                a.ms += ms;
            }
            d.apps.addAll(byApp.values());
            d.apps.sort((x, y) -> Long.compare(y.ms, x.ms));
            out.add(d);
        }
        return out;
    }

    private static void closeApp(List<Object[]> segments, String pkg, String label, long from, long to) {
        if (pkg != null && to > from) segments.add(new Object[] {from, to, pkg, label});
    }

    private static long clip(long a, long b, long lo, long hi) {
        return Math.max(0, Math.min(b, hi) - Math.max(a, lo));
    }

    private static LocalDate localDate(long ms, ZoneId zone) {
        return ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), zone).toLocalDate();
    }

    /** [{"day":"2026-11-01","s":30103,"apps":[{"p":"..","l":"..","s":23265}]}] oldest first; at most five apps a day. */
    public static String json(List<Day> days) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < days.size(); i++) {
            Day d = days.get(i);
            if (i > 0) b.append(',');
            b.append("{\"day\":\"").append(d.date).append("\",\"s\":").append(d.totalMs / 1000).append(",\"apps\":[");
            for (int j = 0; j < d.apps.size() && j < 5; j++) {
                App a = d.apps.get(j);
                if (j > 0) b.append(',');
                b.append("{\"p\":\"").append(esc(a.pkg)).append("\",\"l\":\"").append(esc(a.label)).append("\",\"s\":").append(a.ms / 1000).append('}');
            }
            b.append("]}");
        }
        return b.append(']').toString();
    }

    private static String esc(String s) {
        return s.replaceAll("[\\p{Cntrl}\"\\\\]", " ");
    }
}
