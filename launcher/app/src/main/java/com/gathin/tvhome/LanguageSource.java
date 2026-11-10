package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.gathin.tvhome.family.LanguageList;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The language list for Launcher settings: languages.json from the project's GitHub repository (it grows as
 * translations are added), kept in a file for when the TV is offline, and the languages built into the installed
 * launcher as the last resort. The theme key languages_url points somewhere else, or is empty to never go online. The
 * only request is this one file, made when the language dialog opens.
 */
final class LanguageSource {
    static final String DEFAULT_URL = "https://raw.githubusercontent.com/iResultMan/MyTV-launcher/main/languages.json";
    private static final String TAG = "LanguageSource";
    private static final int MAX_BYTES = 64 * 1024;

    interface Done {
        /** online: the list is the one just read from the network. */
        void languages(List<LanguageList.Language> list, boolean online);
    }

    private LanguageSource() {
    }

    /** Reads the list off the main thread and calls done on it. */
    static void load(Context ctx, Done done) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            boolean online = false;
            List<LanguageList.Language> list = new ArrayList<>();
            File cache = new File(app.getFilesDir(), "languages.json");
            String url = Config.overlay(app) == null ? DEFAULT_URL : Config.overlay(app).optString("languages_url", DEFAULT_URL);
            if (url.startsWith("https://")) {
                String text = fetch(url);
                list = LanguageList.parse(text);
                if (!list.isEmpty()) {
                    online = true;
                    try (FileOutputStream out = new FileOutputStream(cache)) {
                        out.write(text.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        Log.w(TAG, "could not keep the language list: " + e);
                    }
                }
            }
            if (list.isEmpty() && cache.isFile() && cache.length() <= MAX_BYTES) {
                try {
                    list = LanguageList.parse(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    Log.w(TAG, "could not read the kept language list: " + e);
                }
            }
            if (list.isEmpty()) list = builtIn(app);
            final List<LanguageList.Language> result = list;
            final boolean fromNetwork = online;
            new Handler(Looper.getMainLooper()).post(() -> done.languages(result, fromNetwork));
        }, "LanguageSource").start();
    }

    /** English and every language the installed build has resources for, each named in itself. */
    static List<LanguageList.Language> builtIn(Context ctx) {
        List<LanguageList.Language> list = new ArrayList<>();
        list.add(new LanguageList.Language("en", "English"));
        for (String have : ctx.getAssets().getLocales()) {
            if (have.isEmpty()) continue;
            Locale l = Locale.forLanguageTag(have.replace('_', '-'));
            String tag = l.toLanguageTag();
            if (tag.equals("und") || tag.equals("en") || tag.startsWith("en-")) continue;
            boolean seen = false;
            for (LanguageList.Language known : list) seen |= known.tag.equals(tag);
            if (!seen) list.add(new LanguageList.Language(tag, capitalized(l)));
        }
        return list;
    }

    private static String capitalized(Locale l) {
        String name = l.getDisplayName(l);
        return name.isEmpty() ? l.toLanguageTag() : name.substring(0, 1).toUpperCase(l) + name.substring(1);
    }

    private static String fetch(String address) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            if (c.getResponseCode() != 200) return "";
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[2048];
                for (int n; (n = in.read(b)) > 0; ) {
                    buf.write(b, 0, n);
                    if (buf.size() > MAX_BYTES) return "";
                }
                return buf.toString(StandardCharsets.UTF_8.name());
            }
        } catch (IOException | RuntimeException e) {
            Log.i(TAG, "language list not read: " + e);
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
