package com.gathin.tvhome.family;

import java.util.ArrayList;
import java.util.List;

/**
 * What the parents may want to see later: the accessibility helper going off or on, wrong codes, a phone paired.
 * The log is one text, a line per event ("ms|kind|text"), the newest last, kept to {@link #MAX} lines.
 */
public final class KidsJournal {
    public static final int MAX = 100;

    private KidsJournal() {
    }

    /** @return the log with the event added; the oldest lines fall off past {@link #MAX} */
    public static String append(String log, long nowMs, String kind, String text) {
        String line = nowMs + "|" + clean(kind) + "|" + clean(text);
        String all = log == null || log.isEmpty() ? line : log + "\n" + line;
        int lines = 0;
        for (int i = 0; i < all.length(); i++) if (all.charAt(i) == '\n') lines++;
        for (; lines >= MAX; lines--) all = all.substring(all.indexOf('\n') + 1);
        return all;
    }

    /** The last {@code n} events as a JSON array, newest first: [{"t":ms,"kind":"..","text":".."}]. */
    public static String json(String log, int n) {
        List<String[]> events = new ArrayList<>();
        if (log != null && !log.isEmpty()) {
            for (String line : log.split("\n")) {
                String[] p = line.split("\\|", 3);
                if (p.length == 3) events.add(p);
            }
        }
        StringBuilder out = new StringBuilder("[");
        for (int i = events.size() - 1, shown = 0; i >= 0 && shown < n; i--, shown++) {
            String[] p = events.get(i);
            if (shown > 0) out.append(',');
            out.append("{\"t\":").append(number(p[0])).append(",\"kind\":\"").append(p[1]).append("\",\"text\":\"")
                    .append(p[2].replace("\\", "").replace("\"", "")).append("\"}");
        }
        return out.append(']').toString();
    }

    /** The kind of the newest event, "" when there is none. */
    public static String lastKind(String log) {
        if (log == null || log.isEmpty()) return "";
        String[] p = log.substring(log.lastIndexOf('\n') + 1).split("\\|", 3);
        return p.length == 3 ? p[1] : "";
    }

    private static String clean(String s) {
        String t = (s == null ? "" : s).replaceAll("[\\p{Cntrl}|]", " ").trim();
        return t.length() > 80 ? t.substring(0, 80) : t;
    }

    private static long number(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
