package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import com.gathin.tvhome.family.CloudCore;
import com.gathin.tvhome.family.CloudSession;

import org.json.JSONObject;

/**
 * The cloud link's settings and the TV's identity on the server, in the launcher's private preferences (nothing here goes
 * to the shared folder or a backup): the Cloud control switch, which is off until a parent turns it on, the device id and
 * the secret the server gave this TV.
 */
final class CloudStore implements CloudSession.Credentials {
    static final String DEFAULT_URL = "https://tv.secondoffice.ru";
    private static final String PREFS = "cloud";

    private final SharedPreferences prefs;

    CloudStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean registered() {
        return !deviceId().isEmpty() && !secret().isEmpty();
    }

    boolean enabled() {
        return prefs.getBoolean("enabled", false);
    }

    void setEnabled(boolean on) {
        prefs.edit().putBoolean("enabled", on).apply();
    }

    @Override
    public String deviceId() {
        return prefs.getString("device_id", "");
    }

    @Override
    public String secret() {
        return prefs.getString("device_secret", "");
    }

    @Override
    public void save(String deviceId, String secret) {
        prefs.edit().putString("device_id", deviceId).putString("device_secret", secret).apply();
    }

    @Override
    public void clear() {
        prefs.edit().remove("device_id").remove("device_secret").apply();
    }

    /** The server: the theme's cloud_url when it is a plain https address, else the project's own; null when the theme names one that is not usable. */
    static String serverUrl(Context ctx) {
        JSONObject theme = Config.overlay(ctx);
        if (theme == null || !theme.has("cloud_url")) return DEFAULT_URL;
        String url = theme.optString("cloud_url", "").trim();
        // A debug build may point at a test server on this machine; a release build never speaks plain http.
        boolean debuggable = (ctx.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        return CloudCore.validUrl(url, debuggable) ? url : null;
    }

    /** The bot a pairing QR code opens (theme key cloud_bot, or res cloud_bot); empty: the project's page. */
    static String bot(Context ctx) {
        JSONObject theme = Config.overlay(ctx);
        String bot = theme != null && theme.has("cloud_bot") ? theme.optString("cloud_bot", "") : ctx.getString(R.string.cloud_bot);
        bot = bot.trim();
        return bot.matches("[A-Za-z0-9_]{3,64}") ? bot : "";
    }
}
