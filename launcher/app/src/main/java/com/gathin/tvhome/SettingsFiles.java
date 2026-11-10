package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import com.gathin.tvhome.family.SettingsBackup;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * Saves and restores the launcher's own choices (the "launcher_settings" preferences: columns, rows, dock, wallpaper,
 * open on start) as SettingsBackup JSON: to a file beside the app's data, and over the phone page. The children's
 * schedule, the code and the paired phones are not part of it. The file lives in the app's external folder, which
 * "clear data" also empties, so the copy that survives is the one on the phone.
 */
final class SettingsFiles {
    private static final String[] SKIP = {Config.SETTINGS_REVISION_KEY, "theme_signature"};

    private SettingsFiles() {
    }

    static File file(Context ctx) {
        return new File(ctx.getExternalFilesDir(null), "settings-backup.json");
    }

    static String export(Context ctx) {
        return SettingsBackup.toJson(prefs(ctx).getAll(), SKIP);
    }

    /** @return false when the text is not a valid backup; nothing is changed then */
    static boolean restore(Context ctx, String json) {
        Map<String, Object> values;
        try {
            values = SettingsBackup.fromJson(json);
        } catch (IllegalArgumentException e) {
            return false;
        }
        SharedPreferences p = prefs(ctx);
        SharedPreferences.Editor e = p.edit();
        for (String key : p.getAll().keySet()) {
            if (!values.containsKey(key) && !isSkipped(key)) e.remove(key);
        }
        for (Map.Entry<String, Object> v : values.entrySet()) {
            if (isSkipped(v.getKey())) continue;
            Object x = v.getValue();
            if (x instanceof Boolean) e.putBoolean(v.getKey(), (Boolean) x);
            else if (x instanceof Integer) e.putInt(v.getKey(), (Integer) x);
            else if (x instanceof Long) e.putLong(v.getKey(), (Long) x);
            else if (x instanceof String) e.putString(v.getKey(), (String) x);
        }
        e.putLong(Config.SETTINGS_REVISION_KEY, p.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1);   // Home rebuilds when it resumes
        e.apply();
        return true;
    }

    static boolean saveFile(Context ctx) {
        try {
            File f = file(ctx);
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            Files.write(f.toPath(), export(ctx).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** @return false when there is no file or it is not a valid backup */
    static boolean loadFile(Context ctx) {
        try {
            File f = file(ctx);
            if (!f.isFile() || f.length() > SettingsBackup.MAX_TEXT) return false;
            return restore(ctx, new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean isSkipped(String key) {
        for (String s : SKIP) if (s.equals(key)) return true;
        return false;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }
}
