package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The events of the screen-time log, with no Android in them so they can be tested: S (screen on), O (screen off),
 * A (the app in front changed, with its package and name). One line each, "t\tk\tpackage\tname", oldest first.
 * The phone-side home server reads them over the lock server (GET /k/token/usage?since=ms) and does the sums.
 */
final class UsageLogCore {
    static final class Event {
        final long t;
        final char k;
        final String pkg, label;

        Event(long t, char k, String pkg, String label) {
            this.t = t;
            this.k = k;
            this.pkg = pkg == null ? "" : pkg;
            this.label = label == null ? "" : label;
        }
    }

    private final List<Event> events = new ArrayList<>();
    private final long keepMs;
    private final int maxEvents;

    UsageLogCore(long keepMs, int maxEvents) {
        this.keepMs = keepMs;
        this.maxEvents = maxEvents;
    }

    synchronized void add(Event e) {
        events.add(e);
    }

    synchronized Event last() {
        return events.isEmpty() ? null : events.get(events.size() - 1);
    }

    /** The newest event whose kind is one of the characters of kinds, or null. */
    synchronized Event lastOfKinds(String kinds) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (kinds.indexOf(events.get(i).k) >= 0) return events.get(i);
        }
        return null;
    }

    /** The events for the day sums of family.ScreenTime. */
    synchronized List<com.gathin.tvhome.family.ScreenTime.Event> forScreenTime() {
        List<com.gathin.tvhome.family.ScreenTime.Event> out = new ArrayList<>(events.size());
        for (Event e : events) out.add(new com.gathin.tvhome.family.ScreenTime.Event(e.t, e.k, e.pkg, e.label));
        return out;
    }

    synchronized void clear() {
        events.clear();
    }

    /** All events as stored lines, for rewriting the file. */
    synchronized String lines() {
        StringBuilder b = new StringBuilder();
        for (Event e : events) b.append(line(e)).append('\n');
        return b.toString();
    }

    synchronized int size() {
        return events.size();
    }

    /** Drops what is older than keepMs at now, and the oldest beyond maxEvents. */
    synchronized void trim(long now) {
        int cut = 0;
        while (cut < events.size() && now - events.get(cut).t > keepMs) cut++;
        int over = events.size() - cut - maxEvents;
        if (over > 0) cut += over;
        if (cut > 0) events.subList(0, cut).clear();
    }

    static String line(Event e) {
        return e.t + "\t" + e.k + "\t" + clean(e.pkg) + "\t" + clean(e.label);
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** One stored line back to an event, or null when it is damaged. */
    static Event parse(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 2 || f[1].length() != 1) return null;
        char k = f[1].charAt(0);
        if (k != 'S' && k != 'O' && k != 'A') return null;
        try {
            return new Event(Long.parseLong(f[0]), k, f.length > 2 ? f[2] : "", f.length > 3 ? f[3] : "");
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    /** One event of Android's own usage log as ours, or null when it is none of ours: type is UsageEvents.Event's number. */
    static Event fromSystem(int type, long t, String pkg, String cls, String own, java.util.function.Function<String, String> labelOf) {
        switch (type) {
            case 15: return new Event(t, 'S', "", "");                          // SCREEN_INTERACTIVE
            case 16: case 26: return new Event(t, 'O', "", "");                 // SCREEN_NON_INTERACTIVE, DEVICE_SHUTDOWN
            case 1: return countsAsApp(pkg, cls, own) ? new Event(t, 'A', pkg, labelOf.apply(pkg)) : null;   // ACTIVITY_RESUMED
            default: return null;
        }
    }

    synchronized String json(long since, long now, boolean on, long onSince, String pkg, String label) {
        return render(new ArrayList<>(events), since, now, on, onSince, pkg, label);
    }

    /** The answer of /usage: the state now and the events after since (at most 3000, oldest first). */
    static String render(List<Event> events, long since, long now, boolean on, long onSince, String pkg, String label) {
        StringBuilder b = new StringBuilder(256);
        b.append("{\"now\":").append(now).append(",\"on\":").append(on).append(",\"onSince\":").append(onSince)
                .append(",\"pkg\":\"").append(esc(pkg)).append("\",\"label\":\"").append(esc(label)).append("\",\"events\":[");
        int first = 0;
        while (first < events.size() && events.get(first).t <= since) first++;
        int end = Math.min(events.size(), first + 3000);
        for (int i = first; i < end; i++) {
            Event e = events.get(i);
            if (i > first) b.append(',');
            b.append("{\"t\":").append(e.t).append(",\"k\":\"").append(e.k).append('"');
            if (e.k == 'A') b.append(",\"p\":\"").append(esc(e.pkg)).append("\",\"l\":\"").append(esc(e.label)).append('"');
            b.append('}');
        }
        return b.append("]}").toString();
    }

    /** since=NNN from a request path's query, 0 when absent or damaged. */
    static long sinceOf(String path) {
        int q = path.indexOf('?');
        if (q < 0) return 0;
        for (String part : path.substring(q + 1).split("&")) {
            if (part.toLowerCase(Locale.ROOT).startsWith("since=")) {
                try {
                    return Math.max(0, Long.parseLong(part.substring(6)));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    /** Packages that come to the front on their own and are no place the viewer is at: the stock home the firmware keeps
     *  turning back on, the voice assistant, dialogs of the system, the vendor's key service. */
    private static final java.util.Set<String> NOT_AN_APP = new java.util.HashSet<>(java.util.Arrays.asList(
            "android", "com.android.systemui", "com.google.android.tvlauncher", "com.google.android.katniss",
            "com.google.android.gms", "com.google.android.packageinstaller", "com.android.packageinstaller",
            "com.google.android.permissioncontroller", "com.android.permissioncontroller", "com.tcl.keycustomfunctionservice"));

    /** Whether a window of this package counts as "the app in front": not the system itself, not a keyboard. */
    static boolean countsAsApp(String pkg, String cls, String own) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (NOT_AN_APP.contains(pkg) || pkg.contains("inputmethod")) return false;
        // Our own windows are Home (an activity) and the message overlay (a plain view): only Home is a place the TV is at.
        if (pkg.equals(own)) return cls != null && cls.endsWith("MainActivity");
        return true;
    }
}
