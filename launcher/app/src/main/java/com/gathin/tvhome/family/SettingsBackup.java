package com.gathin.tvhome.family;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The launcher's own choices (columns, rows, dock, wallpaper, ...) as one flat JSON object, and back. Pure and strict: the
 * text may come from a phone, so only the known shape is accepted: {"v":1,"settings":{"key":value,...}} with
 * booleans, integers and short strings, and key names of lower-case letters, digits and underscores.
 */
public final class SettingsBackup {
    public static final int VERSION = 1;
    public static final int MAX_ENTRIES = 200, MAX_KEY = 64, MAX_STRING = 500, MAX_TEXT = 64 * 1024;

    private SettingsBackup() {
    }

    /** @param prefs the preference map; values of other types (float, set) are left out, and so is {@code skip} */
    public static String toJson(Map<String, ?> prefs, String... skip) {
        java.util.Set<String> omit = new java.util.HashSet<>(java.util.Arrays.asList(skip));
        StringBuilder b = new StringBuilder("{\"v\":" + VERSION + ",\"settings\":{");
        boolean first = true;
        for (Map.Entry<String, ?> e : new TreeMap<String, Object>(prefs).entrySet()) {
            Object v = e.getValue();
            if (omit.contains(e.getKey()) || !validKey(e.getKey())) continue;
            String lit;
            if (v instanceof Boolean || v instanceof Integer || v instanceof Long) lit = String.valueOf(v);
            else if (v instanceof String && ((String) v).length() <= MAX_STRING) lit = quote((String) v);
            else continue;
            if (!first) b.append(',');
            first = false;
            b.append(quote(e.getKey())).append(':').append(lit);
        }
        return b.append("}}").toString();
    }

    /** @throws IllegalArgumentException when the text is not exactly that shape */
    public static Map<String, Object> fromJson(String text) {
        if (text == null || text.length() > MAX_TEXT) throw new IllegalArgumentException("size");
        Parser p = new Parser(text);
        p.ws();
        p.expect('{');
        Map<String, Object> top = p.object(false);
        p.ws();
        if (!p.atEnd()) throw new IllegalArgumentException("trailing");
        if (!Integer.valueOf(VERSION).equals(top.get("v"))) throw new IllegalArgumentException("version");
        Object settings = top.get("settings");
        if (!(settings instanceof Map)) throw new IllegalArgumentException("settings");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) settings;
        for (String key : map.keySet()) if (!validKey(key)) throw new IllegalArgumentException("key");
        return map;
    }

    static boolean validKey(String k) {
        if (k == null || k.isEmpty() || k.length() > MAX_KEY) return false;
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_')) return false;
        }
        return true;
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private static final class Parser {
        private final String s;
        private int i;
        private int entries;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        void ws() {
            while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
        }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw new IllegalArgumentException("expected " + c);
            i++;
        }

        /** After '{': members; nested objects only at the top level (the "settings" member). */
        Map<String, Object> object(boolean leaf) {
            Map<String, Object> out = new LinkedHashMap<>();
            ws();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return out;
            }
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                ws();
                if (++entries > MAX_ENTRIES + 2) throw new IllegalArgumentException("too many");
                if (out.containsKey(key)) throw new IllegalArgumentException("duplicate");
                out.put(key, value(leaf));
                ws();
                if (i < s.length() && s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                expect('}');
                return out;
            }
        }

        Object value(boolean leaf) {
            if (i >= s.length()) throw new IllegalArgumentException("end");
            char c = s.charAt(i);
            if (c == '"') return string();
            if (c == '{') {
                if (leaf) throw new IllegalArgumentException("nested");
                i++;
                return object(true);
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            int start = i;
            if (c == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i == start || i - start > 18) throw new IllegalArgumentException("number");
            long n = Long.parseLong(s.substring(start, i));
            return n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE ? (Object) Integer.valueOf((int) n) : (Object) Long.valueOf(n);
        }

        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("unterminated");
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    if (i >= s.length()) throw new IllegalArgumentException("escape");
                    char e = s.charAt(i++);
                    if (e == 'u') {
                        if (i + 4 > s.length()) throw new IllegalArgumentException("unicode");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    } else if (e == '"' || e == '\\' || e == '/') {
                        b.append(e);
                    } else {
                        throw new IllegalArgumentException("escape");
                    }
                } else {
                    b.append(c);
                }
                if (b.length() > MAX_STRING) throw new IllegalArgumentException("string");
            }
            return b.toString();
        }
    }
}
