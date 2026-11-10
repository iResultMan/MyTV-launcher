package com.gathin.tvhome;

import android.view.View;

import org.json.JSONObject;

import java.util.Calendar;

/**
 * Dimming: between night_from and night_to (default 23:00 and 06:00) a black layer of night_dim percent, and in the day
 * one of day_dim percent (both 0 to 90, 0 or missing leaves that time of day undimmed) lies over Home, so a dark room is not lit by bright tiles. The layer
 * sits below the family message. Updated on resume and every minute.
 */
final class NightDim {
    private final View scrim;
    private final float nightAlpha, dayAlpha;
    private final int fromMinute, toMinute;

    /** @return null when the theme has no night_dim above 0, or this layout has no scrim (the Hebrew one). */
    static NightDim create(JSONObject theme, View scrim) {
        if (theme == null || scrim == null) return null;
        int night = Math.max(0, Math.min(90, theme.optInt("night_dim", 0)));
        int day = Math.max(0, Math.min(90, theme.optInt("day_dim", 0)));
        return night == 0 && day == 0 ? null : new NightDim(scrim, night / 100f, day / 100f,
                minutes(theme.optString("night_from", "23:00"), 23 * 60), minutes(theme.optString("night_to", "06:00"), 6 * 60));
    }

    private NightDim(View scrim, float nightAlpha, float dayAlpha, int fromMinute, int toMinute) {
        this.scrim = scrim;
        this.nightAlpha = nightAlpha;
        this.dayAlpha = dayAlpha;
        this.fromMinute = fromMinute;
        this.toMinute = toMinute;
    }

    /** "HH:MM" as minutes since midnight, or the fallback for anything else. */
    static int minutes(String hhmm, int fallback) {
        try {
            String[] p = hhmm.trim().split(":");
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            return h < 0 || h > 23 || m < 0 || m > 59 ? fallback : h * 60 + m;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Whether minute-of-day {@code now} is in the night window, which may wrap past midnight. */
    static boolean isNight(int now, int from, int to) {
        return from <= to ? now >= from && now < to : now >= from || now < to;
    }

    void update() {
        Calendar c = Calendar.getInstance();
        boolean night = isNight(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), fromMinute, toMinute);
        float alpha = night ? nightAlpha : dayAlpha;
        scrim.setAlpha(alpha);
        scrim.setVisibility(alpha > 0f ? View.VISIBLE : View.GONE);
    }
}
