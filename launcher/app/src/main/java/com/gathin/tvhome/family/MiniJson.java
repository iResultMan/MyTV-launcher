package com.gathin.tvhome.family;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer for the cloud link, so its rules run in plain JUnit (org.json is a stub there). It reads
 * objects (Map), arrays (List), strings, numbers (Double), booleans and null, nests at most 12 deep and takes at most
 * 64 KB; anything else is an IllegalArgumentException.
 */
public final class MiniJson {
    private static final int MAX_DEPTH = 12;
    private static final int MAX_CHARS = 64 * 1024;

    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        if (text == null || text.length() > MAX_CHARS) throw new IllegalArgumentException("json too long or missing");
        MiniJson p = new MiniJson(text);
        p.space();
        Object v = p.value(0);
        p.space();
        if (p.i != text.length()) throw new IllegalArgumentException("trailing text");
        return v;
    }

    /** The text as a JSON string literal, with the quotes. */
    public static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int k = 0; k < value.length(); k++) {
            char c = value.charAt(k);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else if (c == '\t') out.append("\\t");
            else if (c < 0x20 || c == ' ' || c == ' ') out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object v) {
        return v instanceof List ? (List<Object>) v : null;
    }

    /** A string member, or null when it is absent or not a string. */
    public static String string(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof String ? (String) v : null;
    }

    /** A whole-number member, or the fallback when it is absent, not a number or not whole. */
    public static long integer(Map<String, Object> m, String key, long fallback) {
        Object v = m == null ? null : m.get(key);
        if (!(v instanceof Double)) return fallback;
        double d = (Double) v;
        return d == Math.rint(d) && Math.abs(d) < 9.0e15 ? (long) d : fallback;
    }

    private void space() {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("too deep");
        if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
        char c = s.charAt(i);
        if (c == '{') return obj(depth);
        if (c == '[') return arr(depth);
        if (c == '"') return str();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return num();
    }

    private Map<String, Object> obj(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        space();
        if (peek('}')) return m;
        while (true) {
            space();
            if (i >= s.length() || s.charAt(i) != '"') throw new IllegalArgumentException("key expected");
            String k = str();
            space();
            expect(':');
            space();
            m.put(k, value(depth + 1));
            space();
            if (peek('}')) return m;
            expect(',');
        }
    }

    private List<Object> arr(int depth) {
        List<Object> l = new ArrayList<>();
        i++;
        space();
        if (peek(']')) return l;
        while (true) {
            space();
            l.add(value(depth + 1));
            space();
            if (peek(']')) return l;
            expect(',');
        }
    }

    private boolean peek(char c) {
        if (i < s.length() && s.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!peek(c)) throw new IllegalArgumentException("'" + c + "' expected at " + i);
    }

    private String str() {
        StringBuilder out = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw new IllegalArgumentException("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw new IllegalArgumentException("control character in string");
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (i >= s.length()) throw new IllegalArgumentException("unterminated escape");
            char n = s.charAt(i++);
            switch (n) {
                case '"': case '\\': case '/': out.append(n); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw new IllegalArgumentException("short \\u escape");
                    try {
                        out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("bad \\u escape");
                    }
                    i += 4;
                    break;
                default: throw new IllegalArgumentException("bad escape");
            }
        }
    }

    private Double num() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw new IllegalArgumentException("value expected at " + i);
        try {
            return Double.valueOf(s.substring(start, i));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad number");
        }
    }
}
