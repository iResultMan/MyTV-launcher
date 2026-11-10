package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.gathin.tvhome.family.PosterRules;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posters for the watch-next cards: downloaded once into a disk cache of at most 20 MB (oldest dropped first), decoded
 * at the size the card needs. No image library: two threads, no memory cache (the shelf holds its own bitmaps).
 * Addresses come from other apps, so PosterRules decides what may be fetched.
 */
final class PosterLoader {
    private static final String TAG = "PosterLoader";
    private static final long CACHE_MAX = 20L * 1024 * 1024;
    private static final int FILE_MAX = 3 * 1024 * 1024;

    interface Callback {
        void done(Bitmap bitmap);
    }

    private final Context ctx;
    private final File dir;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    PosterLoader(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.dir = new File(this.ctx.getCacheDir(), "watchnext");
        dir.mkdirs();
    }

    /** Loads off the main thread and calls back on it; null when the poster cannot be had. */
    void load(String uri, int maxWidth, Callback cb) {
        if (!PosterRules.allowed(uri)) {
            Log.w(TAG, "poster refused: " + Uri.parse(String.valueOf(uri)).getScheme() + "://" + Uri.parse(String.valueOf(uri)).getHost());
            cb.done(null);
            return;
        }
        pool.execute(() -> {
            Bitmap b = null;
            try {
                b = decode(uri, maxWidth);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "poster failed: " + e);
            }
            Bitmap result = b;
            main.post(() -> cb.done(result));
        });
    }

    void shutdown() {
        pool.shutdownNow();
    }

    private Bitmap decode(String uri, int maxWidth) throws IOException {
        byte[] bytes = bytes(uri);
        if (bytes == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
    }

    private byte[] bytes(String uri) throws IOException {
        if (uri.regionMatches(true, 0, "content://", 0, 10)) return read(ctx.getContentResolver().openInputStream(Uri.parse(uri)));
        File f = new File(dir, key(uri));
        if (f.isFile()) {
            f.setLastModified(System.currentTimeMillis());
            try (InputStream in = new java.io.FileInputStream(f)) {
                return read(in);
            }
        }
        HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try {
            if (c.getResponseCode() != 200) return null;
            byte[] data = read(c.getInputStream());
            if (data == null) return null;
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
            trim();
            return data;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        if (in == null) return null;
        try (InputStream s = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = s.read(b)) > 0; ) {
                buf.write(b, 0, n);
                if (buf.size() > FILE_MAX) return null;
            }
            return buf.toByteArray();
        }
    }

    private synchronized void trim() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long[] sizes = new long[files.length], used = new long[files.length];
        for (int i = 0; i < files.length; i++) {
            sizes[i] = files[i].length();
            used[i] = files[i].lastModified();
        }
        List<Integer> drop = PosterRules.evict(sizes, used, CACHE_MAX);
        for (int i : drop) files[i].delete();
    }

    private static String key(String uri) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(uri.getBytes("UTF-8"));
            StringBuilder s = new StringBuilder();
            for (byte x : d) s.append(String.format("%02x", x));
            return s.toString();
        } catch (java.security.NoSuchAlgorithmException | java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
