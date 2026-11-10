package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import com.gathin.tvhome.family.SanitizerRules;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The TV's tidy-up. It runs on events only, nothing polls: Home has been quiet for three minutes (one timer, started
 * by Home coming to the front and by each key, so it fires once after the last key), the screen went off, the system
 * asked apps for memory (onTrimMemory), or the VPN network was lost. Memory low: the background processes of the apps
 * are closed (the app on screen and anything playing in a foreground service are not touched). VPN down for four
 * minutes with Home in front: the VPN app is started and Home comes back. Rules and cooldowns are in SanitizerRules.
 * Off with "sanitizer": false in the theme. The result goes to the log (tag Sanitizer) and to the "sanitizer"
 * preferences (count and time of the last clean).
 */
final class Sanitizer {
    private static final String TAG = "Sanitizer";
    private static final String PREFS = "sanitizer";
    private final Activity activity;
    private final Context app;
    private final Supplier<String> vpnApp;
    private final Supplier<Intent> vpnIntent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SanitizerRules rules = new SanitizerRules();
    private boolean running;
    private boolean registered;

    private final Runnable idle = () -> {
        Log.i(TAG, "Home has been quiet: looking at memory");
        cleanIfTight();
        checkVpn();
    };
    private final Runnable vpnDown = this::checkVpn;

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "screen off: looking at memory");
            cleanIfTight();
        }
    };

    private final ComponentCallbacks2 trim = new ComponentCallbacks2() {
        @Override
        public void onTrimMemory(int level) {
            if (!SanitizerRules.pressure(level)) return;
            Log.i(TAG, "the system asked for memory (level " + level + ")");
            main.post(Sanitizer.this::cleanIfTight);
        }

        @Override
        public void onConfigurationChanged(Configuration newConfig) {
        }

        @Override
        public void onLowMemory() {
            main.post(Sanitizer.this::cleanIfTight);
        }
    };

    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onLost(Network network) {
            if (!vpnWatched()) return;
            Log.i(TAG, "the VPN was lost");
            main.removeCallbacks(vpnDown);
            main.postDelayed(vpnDown, SanitizerRules.VPN_DOWN_MS);
        }

        @Override
        public void onAvailable(Network network) {
            main.removeCallbacks(vpnDown);
        }
    };

    Sanitizer(Activity activity, Supplier<String> vpnApp, Supplier<Intent> vpnIntent) {
        this.activity = activity;
        this.app = activity.getApplicationContext();
        this.vpnApp = vpnApp;
        this.vpnIntent = vpnIntent;
    }

    private boolean enabled() {
        org.json.JSONObject theme = Config.overlay(activity);
        return theme == null || theme.optBoolean("sanitizer", true);
    }

    private boolean vpnWatched() {
        return !vpnApp.get().isEmpty() && vpnIntent.get() != null;
    }

    /** Listens for the events; called once Home exists. */
    void register() {
        if (registered || !enabled()) return;
        registered = true;
        app.registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        app.registerComponentCallbacks(trim);
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        if (cm != null) {
            cm.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), vpnCallback);
        }
    }

    void release() {
        if (!registered) return;
        registered = false;
        main.removeCallbacksAndMessages(null);
        app.unregisterReceiver(screenOff);
        app.unregisterComponentCallbacks(trim);
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(vpnCallback);
    }

    /** Home came to the front: wait for it to go quiet, and look at a VPN that is down. */
    void start() {
        if (!registered) return;
        running = true;
        armIdle();
        if (vpnWatched() && !vpnUp()) {
            main.removeCallbacks(vpnDown);
            main.postDelayed(vpnDown, SanitizerRules.IDLE_MS);
        }
    }

    void stop() {
        running = false;
        main.removeCallbacks(idle);
    }

    /** A key was pressed on Home: the quiet starts over. */
    void touch() {
        if (running) armIdle();
    }

    private void armIdle() {
        main.removeCallbacks(idle);
        main.postDelayed(idle, SanitizerRules.IDLE_MS);
    }

    private boolean usable() {
        PowerManager pm = app.getSystemService(PowerManager.class);
        return !KidsLock.isLocked() && (pm == null || pm.isInteractive());
    }

    private void cleanIfTight() {
        long[] mem = readMemory();
        boolean tight = mem != null && SanitizerRules.memoryTight(mem[0], mem[1], mem[2], mem[3]);
        if (rules.shouldClean(SystemClock.elapsedRealtime(), tight)) clean(mem);
    }

    /** The VPN has been down for a while: start its app, but only while Home is in front and quiet. */
    private void checkVpn() {
        if (!vpnWatched() || vpnUp()) return;
        if (!running || !usable()) return;     // Home start() looks again when it comes back
        if (rules.shouldStartVpn(SystemClock.elapsedRealtime())) startVpn();
    }

    /** MemTotal, MemAvailable, SwapTotal, SwapFree in kB; null when /proc/meminfo cannot be read. */
    static long[] readMemory() {
        long[] out = new long[4];
        int found = 0;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null && found < 4) {
                int slot = line.startsWith("MemTotal:") ? 0 : line.startsWith("MemAvailable:") ? 1
                        : line.startsWith("SwapTotal:") ? 2 : line.startsWith("SwapFree:") ? 3 : -1;
                if (slot < 0) continue;
                out[slot] = Long.parseLong(line.replaceAll("[^0-9]", ""));
                found++;
            }
        } catch (IOException | NumberFormatException e) {
            return null;
        }
        return found == 4 ? out : null;
    }

    private boolean vpnUp() {
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        if (cm == null) return true;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }

    /** Closes the background processes of every installed app that has a launcher entry, except the launcher and the VPN. */
    private void clean(long[] before) {
        ActivityManager am = app.getSystemService(ActivityManager.class);
        PackageManager pm = app.getPackageManager();
        if (am == null) return;
        Set<String> packages = new HashSet<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                ApplicationInfo info = ri.activityInfo.applicationInfo;
                boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                if (!system) packages.add(ri.activityInfo.packageName);
            }
        }
        packages.remove(app.getPackageName());
        packages.remove(vpnApp.get());
        for (String pkg : packages) {
            try {
                am.killBackgroundProcesses(pkg);
            } catch (SecurityException e) {
                Log.w(TAG, "cannot close background processes: " + e);
                return;
            }
        }
        android.content.SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putLong("last_clean_at", System.currentTimeMillis()).putInt("cleans", prefs.getInt("cleans", 0) + 1).apply();
        Log.i(TAG, "cleaned background processes of " + packages.size() + " apps; swap free "
                + (before == null ? "?" : before[3] / 1024 + " MB") + ", RAM available " + (before == null ? "?" : before[1] / 1024 + " MB"));
        main.postDelayed(() -> {
            long[] after = readMemory();
            if (after != null) Log.i(TAG, "after: swap free " + after[3] / 1024 + " MB, RAM available " + after[1] / 1024 + " MB");
        }, 5000);
    }

    /** Starts the VPN app, then brings Home back: the app connects when it opens (its own setting), or it does not. */
    private void startVpn() {
        Intent i = vpnIntent.get();
        if (i == null) return;
        Log.i(TAG, "the VPN has been down for minutes: starting " + vpnApp.get());
        try {
            activity.startActivity(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot start the VPN app: " + e);
            return;
        }
        main.postDelayed(() -> {
            Log.i(TAG, "VPN " + (vpnUp() ? "is up" : "is still down") + " after starting its app");
            activity.startActivity(new Intent(activity, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK));
        }, 20_000);
    }
}
