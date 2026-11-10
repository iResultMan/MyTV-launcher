package com.gathin.tvhome;

import android.os.Handler;
import android.util.Log;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The weather block next to the date, from Open-Meteo (no key, no account). It is off unless the theme's
 * config.json has weather_lat and weather_lon, and it only reads while Home is on screen, every 30 minutes,
 * or after 5 minutes when a read failed. Until the first read succeeds the block stays hidden.
 */
final class WeatherHeader {
    private static final String TAG = "WeatherHeader";
    private static final long OK_MS = 30 * 60 * 1000L, RETRY_MS = 5 * 60 * 1000L;

    private final View block;
    private final ImageView icon;
    private final TextView temp, caption, feels, hint;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final double lat, lon;
    private final String city;
    /** weather_url: an address that answers with Open-Meteo's JSON (for a network where the TV cannot reach it itself); empty: ask Open-Meteo. */
    private final String relay;
    private HandlerThread thread;
    private volatile Handler bg;

    /** @return null when the theme has no weather_lat/weather_lon, or this layout has no weather block (the Hebrew one). */
    static WeatherHeader create(JSONObject theme, View block, ImageView icon, TextView temp, TextView caption, TextView feels, TextView hint) {
        if (theme == null || block == null || !theme.has("weather_lat") || !theme.has("weather_lon")) return null;
        return new WeatherHeader(theme.optDouble("weather_lat"), theme.optDouble("weather_lon"),
                theme.optString("weather_city", ""), theme.optString("weather_url", "").trim(), block, icon, temp, caption, feels, hint);
    }

    private WeatherHeader(double lat, double lon, String city, String relay, View block, ImageView icon, TextView temp, TextView caption, TextView feels, TextView hint) {
        this.lat = lat;
        this.lon = lon;
        this.city = city;
        this.relay = relay;
        this.block = block;
        this.icon = icon;
        this.temp = temp;
        this.caption = caption;
        this.feels = feels;
        this.hint = hint;
    }

    void start() {
        if (thread != null) return;
        thread = new HandlerThread("weather");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        bg = h;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (bg != h) return;
                boolean ok = read(h);
                if (bg == h) h.postDelayed(this, ok ? OK_MS : RETRY_MS);
            }
        });
    }

    /** Stops the reads; the last value stays on screen. */
    void stop() {
        if (thread == null) return;
        bg.removeCallbacksAndMessages(null);
        thread.quit();
        thread = null;
        bg = null;
    }

    private boolean read(Handler h) {
        HttpURLConnection c = null;
        try {
            String url = !relay.isEmpty() ? relay : String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,apparent_temperature,weather_code&hourly=precipitation,temperature_2m&forecast_hours=4&timezone=auto",
                    lat, lon);
            if (!com.gathin.tvhome.family.NetRules.allowed(url)) {
                Log.w(TAG, "weather_url refused: plain http is allowed only on the home network");
                return false;
            }
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            int status = c.getResponseCode();
            if (status != 200) {
                Log.w(TAG, "weather read: HTTP " + status);
                return false;
            }
            JSONObject cur, root;
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[2048];
                for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                root = new JSONObject(buf.toString(StandardCharsets.UTF_8.name()));
                cur = root.getJSONObject("current");
            }
            int t = (int) Math.round(cur.getDouble("temperature_2m"));
            int f = (int) Math.round(cur.getDouble("apparent_temperature"));
            int code = cur.getInt("weather_code");
            String label = signed(t);
            android.content.Context ctx = block.getContext();
            String feelsLine = ctx.getString(R.string.wx_feels_like, signed(f));
            String hintLine = precipitationHint(ctx, root, code);
            String words = describe(ctx, code) + (city.isEmpty() ? "" : "  ·  " + city);
            main.post(() -> {
                if (bg != h) return;
                temp.setText(label);
                caption.setText(words);
                feels.setText(feelsLine);
                hint.setText(hintLine);
                hint.setVisibility(hintLine.isEmpty() ? View.GONE : View.VISIBLE);
                icon.setImageResource(iconFor(code));
                block.setVisibility(View.VISIBLE);
            });
            return true;
        } catch (Exception e) {
            Log.w(TAG, "weather read failed: " + e);
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** "Rain around 17:00" when rain or snow is due within the next three hours and none falls now; else "". */
    static String precipitationHint(android.content.Context ctx, JSONObject root, int code) {
        if (code >= 51 && code <= 99) return "";
        try {
            JSONObject hourly = root.getJSONObject("hourly");
            org.json.JSONArray time = hourly.getJSONArray("time"), mm = hourly.getJSONArray("precipitation"), t = hourly.getJSONArray("temperature_2m");
            for (int i = 1; i < time.length(); i++) {
                if (mm.optDouble(i, 0) >= 0.2) {
                    String hhmm = time.getString(i).substring(time.getString(i).indexOf('T') + 1);
                    return ctx.getString(t.optDouble(i, 5) <= 0.5 ? R.string.wx_snow_at : R.string.wx_rain_at, hhmm);
                }
            }
        } catch (Exception e) {
            // No hourly data: no hint.
        }
        return "";
    }

    private static String signed(int degrees) {
        return (degrees > 0 ? "+" : degrees < 0 ? "\u2212" : "") + Math.abs(degrees) + "\u00B0";
    }

    /** WMO weather interpretation codes, in the words of the Home screen. */
    static String describe(android.content.Context ctx, int code) {
        return ctx.getString(descriptionFor(code));
    }

    private static int descriptionFor(int code) {
        if (code == 0) return R.string.wx_clear;
        if (code == 1 || code == 2) return R.string.wx_partly;
        if (code == 3) return R.string.wx_cloudy;
        if (code == 45 || code == 48) return R.string.wx_fog;
        if (code >= 51 && code <= 57) return R.string.wx_drizzle;
        if (code >= 61 && code <= 67) return R.string.wx_rain;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return R.string.wx_snow;
        if (code >= 80 && code <= 82) return R.string.wx_showers;
        if (code >= 95) return R.string.wx_storm;
        return R.string.wx_cloudy;
    }

    static int iconFor(int code) {
        if (code == 0 || code == 1) return R.drawable.ic_weather_clear;
        if (code == 45 || code == 48) return R.drawable.ic_weather_fog;
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return R.drawable.ic_weather_rain;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return R.drawable.ic_weather_snow;
        if (code >= 95) return R.drawable.ic_weather_storm;
        return R.drawable.ic_weather_cloud;
    }
}
