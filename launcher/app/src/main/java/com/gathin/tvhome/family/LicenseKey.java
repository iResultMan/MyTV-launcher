package com.gathin.tvhome.family;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A licence key checked on the TV with no network and no account: TVH1.&lt;payload&gt;.&lt;signature&gt; (both base64url), the
 * payload "tier=home;exp=20271231;dev=ab12cd34,ef56ab78" signed with Ed25519. The key names up to five TVs by a short
 * device id, so a key bought for one family does not run everywhere. Without a valid key the launcher stays in the
 * free tier, which holds all of the children's protection. tools/license/mint.py makes the keys.
 */
public final class LicenseKey {
    public enum Tier { FREE, HOME, FAMILY }

    public enum Reason { OK, NO_KEY, NO_PUBLIC_KEY, BAD_FORMAT, BAD_SIGNATURE, OTHER_DEVICE, EXPIRED }

    public static final class Result {
        public final Tier tier;
        public final Reason reason;
        /** The last valid day as yyyymmdd, 0 for a key with no end. */
        public final int expires;

        Result(Tier tier, Reason reason, int expires) {
            this.tier = tier;
            this.reason = reason;
            this.expires = expires;
        }
    }

    private LicenseKey() {
    }

    /**
     * @param key       the text the owner entered, may be null or padded with spaces
     * @param publicKey the 32 bytes the launcher was built with, or null while none is set
     * @param deviceId  this TV's id (8 hex digits)
     * @param today     today as yyyymmdd
     */
    public static Result check(String key, byte[] publicKey, String deviceId, int today) {
        if (key == null || key.trim().isEmpty()) return free(Reason.NO_KEY);
        if (publicKey == null || publicKey.length != 32) return free(Reason.NO_PUBLIC_KEY);
        String[] parts = key.trim().split("\\.", -1);
        if (parts.length != 3 || !parts[0].equals("TVH1")) return free(Reason.BAD_FORMAT);
        byte[] payload, signature;
        try {
            payload = Base64.getUrlDecoder().decode(parts[1]);
            signature = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return free(Reason.BAD_FORMAT);
        }
        if (!Ed25519.verify(publicKey, payload, signature)) return free(Reason.BAD_SIGNATURE);
        Tier tier = null;
        int exp = 0;
        boolean mine = false;
        for (String field : new String(payload, StandardCharsets.UTF_8).split(";")) {
            int eq = field.indexOf('=');
            if (eq < 0) return free(Reason.BAD_FORMAT);
            String name = field.substring(0, eq), value = field.substring(eq + 1);
            switch (name) {
                case "tier":
                    tier = value.equals("home") ? Tier.HOME : value.equals("family") ? Tier.FAMILY : null;
                    if (tier == null) return free(Reason.BAD_FORMAT);
                    break;
                case "exp":
                    if (!value.matches("\\d{8}")) return free(Reason.BAD_FORMAT);
                    exp = Integer.parseInt(value);
                    break;
                case "dev":
                    String[] ids = value.split(",");
                    if (ids.length > 5) return free(Reason.BAD_FORMAT);
                    for (String id : ids) if (id.equals(deviceId)) mine = true;
                    break;
                default:
                    return free(Reason.BAD_FORMAT);
            }
        }
        if (tier == null) return free(Reason.BAD_FORMAT);
        if (!mine) return free(Reason.OTHER_DEVICE);
        if (exp != 0 && today > exp) return free(Reason.EXPIRED);
        return new Result(tier, Reason.OK, exp);
    }

    private static Result free(Reason reason) {
        return new Result(Tier.FREE, reason, 0);
    }

    public static byte[] hex(String s) {
        if (s == null || s.length() != 64) return null;
        byte[] b = new byte[32];
        try {
            for (int i = 0; i < 32; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        } catch (NumberFormatException e) {
            return null;
        }
        return b;
    }
}
