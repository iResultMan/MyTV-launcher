package com.gathin.tvhome.family;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What the poster loader may fetch, and what the disk cache drops; pure, so it is tested without Android. */
public final class PosterRules {
    private PosterRules() {
    }

    /**
     * Apps publish poster addresses, so they are untrusted: https anywhere, content:// from the system, http only on the
     * home network (a private IPv4 address), nothing else (no file://, no javascript:, no other schemes).
     */
    public static boolean allowed(String uri) {
        if (uri == null) return false;
        String u = uri.trim().toLowerCase(Locale.ROOT);
        if (u.startsWith("https://") || u.startsWith("content://")) return true;
        if (!u.startsWith("http://")) return false;
        String host = u.substring(7);
        int end = host.length();
        for (char stop : new char[] {'/', ':', '?', '#'}) {
            int i = host.indexOf(stop);
            if (i >= 0 && i < end) end = i;
        }
        return privateIpv4(host.substring(0, end));
    }

    static boolean privateIpv4(String host) {
        String[] p = host.split("\\.", -1);
        if (p.length != 4) return false;
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            if (p[i].isEmpty() || p[i].length() > 3) return false;
            try {
                n[i] = Integer.parseInt(p[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (n[i] < 0 || n[i] > 255) return false;
        }
        return n[0] == 10 || n[0] == 192 && n[1] == 168 || n[0] == 172 && n[1] >= 16 && n[1] <= 31;
    }

    /**
     * @param sizes     file sizes in bytes
     * @param modified  last-use times, same order
     * @return the indexes to delete, oldest first, so that what stays is at most {@code maxBytes}
     */
    public static List<Integer> evict(long[] sizes, long[] modified, long maxBytes) {
        long total = 0;
        for (long s : sizes) total += s;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < sizes.length; i++) order.add(i);
        order.sort((a, b) -> Long.compare(modified[a], modified[b]));
        List<Integer> drop = new ArrayList<>();
        for (int i : order) {
            if (total <= maxBytes) break;
            drop.add(i);
            total -= sizes[i];
        }
        return drop;
    }
}
