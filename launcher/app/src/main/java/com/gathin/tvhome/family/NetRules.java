package com.gathin.tvhome.family;

/**
 * Where the launcher may fetch from: https anywhere, plain http only to a private IPv4 address on the home network (the
 * family message service, a weather relay). The manifest still allows cleartext because Android's network security config
 * cannot name an address range, so this rule is the one that holds. Pure.
 */
public final class NetRules {
    private NetRules() {
    }

    public static boolean allowed(String url) {
        return url != null && !url.trim().toLowerCase(java.util.Locale.ROOT).startsWith("content://") && PosterRules.allowed(url);
    }
}
