package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import com.gathin.tvhome.family.LicenseKey;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Calendar;

/**
 * Which tier this TV is on: free unless a licence key for this TV checks out (LicenseKey). The check needs no network. The
 * free tier holds everything about the children's protection; nothing is gated on a higher tier yet, the tier is only
 * known and shown. The public key the keys are checked against is the license_public_key string in config.xml, empty
 * until the owner makes a signing key with tools/license/mint.py.
 */
final class Entitlements {
    private static final String PREFS = "license";

    private Entitlements() {
    }

    /** Eight hex digits that name this TV in a key. */
    static String deviceId(Context ctx) {
        String id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("tvhome:" + (id == null ? "" : id)).getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 4; i++) s.append(String.format("%02x", h[i]));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static LicenseKey.Result current(Context ctx) {
        return check(ctx, prefs(ctx).getString("key", ""));
    }

    static LicenseKey.Tier tier(Context ctx) {
        return current(ctx).tier;
    }

    /** Stores the key only when it is valid for this TV, so a wrong paste never replaces a good key. */
    static LicenseKey.Result submit(Context ctx, String key) {
        LicenseKey.Result r = check(ctx, key);
        if (r.reason == LicenseKey.Reason.OK) prefs(ctx).edit().putString("key", key.trim()).apply();
        return r;
    }

    /** Reads license.key from the app's external folder (an owner can copy it there). */
    static LicenseKey.Result submitFile(Context ctx) {
        try {
            File f = new File(ctx.getExternalFilesDir(null), "license.key");
            if (!f.isFile() || f.length() > 2048) return check(ctx, "");
            return submit(ctx, new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (java.io.IOException | RuntimeException e) {
            return check(ctx, "");
        }
    }

    private static LicenseKey.Result check(Context ctx, String key) {
        Calendar c = Calendar.getInstance();
        int today = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
        return LicenseKey.check(key, LicenseKey.hex(ctx.getString(R.string.license_public_key)), deviceId(ctx), today);
    }

    /** One line for the setup screen and the phone page. */
    static String summary(Context ctx) {
        LicenseKey.Result r = current(ctx);
        String id = deviceId(ctx);
        switch (r.tier) {
            case HOME:
                return ctx.getString(r.expires == 0 ? R.string.license_home : R.string.license_home_until, r.expires, id);
            case FAMILY:
                return ctx.getString(r.expires == 0 ? R.string.license_family : R.string.license_family_until, r.expires, id);
            default:
                return ctx.getString(R.string.license_free, 0, id);
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
