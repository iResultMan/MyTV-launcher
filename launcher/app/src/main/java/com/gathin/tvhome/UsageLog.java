package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Screen time of the TV: when the screen came on and went off and which app was in front. The accessibility helper feeds
 * it (window changes, the screen broadcasts); the lock server hands the log to the home server (UsageLogCore.json), which
 * does the sums for the parents' bot. The log lives in the app's private storage, 30 days of it. A minute heartbeat bounds
 * a screen-on period that ended without a word (the power was cut): at the next start it is closed at the last beat.
 */
final class UsageLog {
    private static final String TAG = "UsageLog";
    private static final long KEEP_MS = 30L * 24 * 60 * 60 * 1000;
    private static final long BEAT_MS = 60 * 1000L;
    private static final long LOST_MS = 3 * 60 * 1000L;
    private static final String FILE = "usage.log";
    private static final String PREFS = "usage";

    private static UsageLog instance;

    private final Context ctx;
    private final PackageManager pm;
    private final UsageLogCore core = new UsageLogCore(KEEP_MS, 12000);
    private final File file;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> labels = new HashMap<>();
    private HandlerThread writer;
    private Handler io;
    private boolean on;
    private long onSince;
    private String pkg = "", label = "";

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) screenOn(System.currentTimeMillis());
            else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) screenOff(System.currentTimeMillis());
        }
    };

    private final Runnable beat = new Runnable() {
        @Override
        public void run() {
            if (on) prefs.edit().putLong("alive", System.currentTimeMillis()).apply();
            main.postDelayed(this, BEAT_MS);
        }
    };

    private UsageLog(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.pm = this.ctx.getPackageManager();
        this.file = new File(this.ctx.getFilesDir(), FILE);
        this.prefs = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Starts logging (once per process); safe to call again. */
    static synchronized void start(Context ctx) {
        if (instance != null) return;
        UsageLog u = new UsageLog(ctx);
        u.begin();
        instance = u;
    }

    static synchronized void stop() {
        if (instance == null) return;
        instance.end();
        instance = null;
    }

    /** What the lock server answers on /usage; an empty log while not started. */
    static String json(long since) {
        UsageLog u;
        synchronized (UsageLog.class) {
            u = instance;
        }
        long now = System.currentTimeMillis();
        if (u == null) return new UsageLogCore(KEEP_MS, 1).json(since, now, false, 0, "", "");
        String system = SystemUsage.json(u.ctx, since);
        if (system != null) return system;                // Android's own log, when usage access is granted
        synchronized (u) {
            return u.core.json(since, now, u.on, u.onSince, u.pkg, u.label);
        }
    }

    /** Deletes the screen-time log, in memory and on disk. */
    static void erase(Context ctx) {
        UsageLog u;
        synchronized (UsageLog.class) {
            u = instance;
        }
        if (u != null) {
            u.core.clear();
            synchronized (u) {
                u.prefs.edit().clear().apply();
            }
        } else {
            ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        }
        new File(ctx.getApplicationContext().getFilesDir(), FILE).delete();
    }

    /** The last 30 days as the day table of family.ScreenTime (JSON), from this log: it is local and quick, the system's log is neither. */
    static String daysJson() {
        UsageLog u;
        synchronized (UsageLog.class) {
            u = instance;
        }
        if (u == null) return "[]";
        return com.gathin.tvhome.family.ScreenTime.json(com.gathin.tvhome.family.ScreenTime.days(
                u.core.forScreenTime(), System.currentTimeMillis(), java.time.ZoneId.systemDefault(), 30, u.ctx.getPackageName()));
    }

    /** The screen now for the cloud state: {on (Boolean), since (Long, ms), package, label}, or null before logging has started. */
    static Object[] screenNow() {
        UsageLog u;
        synchronized (UsageLog.class) {
            u = instance;
        }
        if (u == null) return null;
        synchronized (u) {
            return new Object[] {u.on, u.onSince, u.pkg, u.label};
        }
    }

    /** A window of the system came to the front (from the helper's window events). */
    static void onWindow(CharSequence pkg, CharSequence cls, String own) {
        UsageLog u;
        synchronized (UsageLog.class) {
            u = instance;
        }
        if (u == null || pkg == null) return;
        String p = pkg.toString();
        if (!UsageLogCore.countsAsApp(p, cls == null ? null : cls.toString(), own)) return;
        u.window(p, System.currentTimeMillis());
    }

    private synchronized void window(String p, long now) {
        if (p.equals(pkg)) return;
        pkg = p;
        label = labelOf(p);
        prefs.edit().putString("pkg", pkg).putString("label", label).apply();
        if (on) append(new UsageLogCore.Event(now, 'A', pkg, label));
    }

    private String labelOf(String p) {
        String l = labels.get(p);
        if (l == null) {
            try {
                l = pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString();
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                l = p;
            }
            labels.put(p, l);
        }
        return l;
    }

    private synchronized void screenOn(long now) {
        if (on) return;
        on = true;
        onSince = now;
        append(new UsageLogCore.Event(now, 'S', "", ""));
        if (!pkg.isEmpty()) append(new UsageLogCore.Event(now, 'A', pkg, label));
        prefs.edit().putLong("alive", now).apply();
    }

    private synchronized void screenOff(long now) {
        if (!on) return;
        on = false;
        append(new UsageLogCore.Event(now, 'O', "", ""));
    }

    private void begin() {
        writer = new HandlerThread("usage-log");
        writer.start();
        io = new Handler(writer.getLooper());
        load();
        pkg = prefs.getString("pkg", "");
        label = prefs.getString("label", "");
        long now = System.currentTimeMillis();
        PowerManager power = ctx.getSystemService(PowerManager.class);
        boolean interactive = power != null && power.isInteractive();
        // A screen-on period the log still holds open from before a restart: closed at the last heartbeat when the TV was cut
        // from power, at once when the screen is off now, left open when the screen is still on (this process just restarted).
        UsageLogCore.Event last = core.lastOfKinds("SO");
        if (last != null && last.k == 'S') {
            long alive = prefs.getLong("alive", last.t);
            if (now - alive > LOST_MS) append(new UsageLogCore.Event(Math.max(alive, last.t), 'O', "", ""));
            else if (!interactive) append(new UsageLogCore.Event(now, 'O', "", ""));
        }
        UsageLogCore.Event after = core.lastOfKinds("SO");
        if (interactive) {
            if (after != null && after.k == 'S') {
                on = true;                                // the same period goes on
                onSince = after.t;
            } else {
                screenOn(now);
            }
        }
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        ctx.registerReceiver(screen, f);
        main.post(beat);
    }

    private void end() {
        main.removeCallbacks(beat);
        try {
            ctx.unregisterReceiver(screen);
        } catch (IllegalArgumentException ignored) {
        }
        if (writer != null) writer.quitSafely();
    }

    private void load() {
        if (!file.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            for (String line; (line = r.readLine()) != null; ) {
                UsageLogCore.Event e = UsageLogCore.parse(line);
                if (e != null) core.add(e);
            }
        } catch (IOException e) {
            Log.w(TAG, "cannot read the log: " + e.getMessage());
        }
        core.trim(System.currentTimeMillis());
    }

    private void append(UsageLogCore.Event e) {
        core.add(e);
        final String line = UsageLogCore.line(e) + "\n";
        io.post(() -> {
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                w.write(line);
            } catch (IOException ex) {
                Log.w(TAG, "cannot write the log: " + ex.getMessage());
            }
        });
        if (core.size() % 500 == 0) io.post(this::compact);
    }

    /** Rewrites the file without the old events (called now and then). */
    private void compact() {
        core.trim(System.currentTimeMillis());
        String all = core.lines();
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(all);
        } catch (IOException ex) {
            Log.w(TAG, "cannot compact the log: " + ex.getMessage());
            return;
        }
        if (!tmp.renameTo(file)) Log.w(TAG, "cannot replace the log");
    }
}
