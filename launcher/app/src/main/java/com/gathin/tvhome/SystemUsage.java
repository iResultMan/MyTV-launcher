package com.gathin.tvhome;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.os.Process;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The screen-time log from Android itself (UsageStatsManager): every screen on and off and every app that came to the
 * front, a week or so back, kept by the system whether this launcher runs or not. Needs "usage access" for the launcher
 * (adb: appops set com.gathin.tvhome GET_USAGE_STATS allow); without it UsageLog answers from its own, shorter log.
 */
final class SystemUsage {
    private static final long SPAN_MS = 15L * 24 * 60 * 60 * 1000;
    private static final Map<String, String> LABELS = new HashMap<>();

    private SystemUsage() { }

    static boolean granted(Context ctx) {
        AppOpsManager ops = ctx.getSystemService(AppOpsManager.class);
        if (ops == null) return false;
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    private static synchronized String label(Context ctx, String pkg) {
        String l = LABELS.get(pkg);
        if (l == null) {
            PackageManager pm = ctx.getPackageManager();
            try {
                l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                l = pkg;
            }
            LABELS.put(pkg, l);
        }
        return l;
    }

    /**
     * The /usage answer built from the system log, or null when it cannot be read. The log is big and the TV's processor
     * slow (a query over all 15 days took 13 s, longer than the home server waits), so only the events after {@code since}
     * are read, plus a short look back for the state now: when the screen came on and what is in front.
     */
    static String json(Context ctx, long since) {
        UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
        if (usm == null || !granted(ctx)) return null;
        long now = System.currentTimeMillis();
        long begin = Math.max(since + 1, now - SPAN_MS);
        PowerManager power = ctx.getSystemService(PowerManager.class);
        boolean on = power != null && power.isInteractive();
        List<UsageLogCore.Event> out = new ArrayList<>();
        try {
            State state = new State();
            scan(usm, ctx, begin, now, begin, out, state);
            // Not seen in the new events: look back, first a few hours, then two days (a TV on for longer shows no start).
            if (on && (state.onSince == 0 || state.pkg.isEmpty())) {
                for (long back : new long[] {LOOK_BACK_MS, 8 * LOOK_BACK_MS}) {
                    long from = Math.max(now - back, now - SPAN_MS);
                    if (from >= begin) continue;
                    State earlier = new State();
                    scan(usm, ctx, from, begin, Long.MAX_VALUE, null, earlier);
                    if (state.onSince == 0) state.onSince = earlier.onSince;
                    if (state.pkg.isEmpty()) {
                        state.pkg = earlier.pkg;
                        state.label = earlier.label;
                    }
                    if (state.onSince != 0 && !state.pkg.isEmpty()) break;
                }
            }
            return UsageLogCore.render(out, since, now, on, on ? state.onSince : 0, state.pkg, state.label);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static final long LOOK_BACK_MS = 6L * 60 * 60 * 1000;

    private static final class State {
        long onSince;
        String pkg = "", label = "";
    }

    /** Reads the system events in [from, to); the ones at or after keepFrom go to out (when it is not null). */
    private static void scan(UsageStatsManager usm, Context ctx, long from, long to, long keepFrom, List<UsageLogCore.Event> out, State state) {
        UsageEvents all = usm.queryEvents(from, to);
        UsageEvents.Event e = new UsageEvents.Event();
        while (all.hasNextEvent()) {
            all.getNextEvent(e);
            UsageLogCore.Event ev = UsageLogCore.fromSystem(e.getEventType(), e.getTimeStamp(), e.getPackageName(), e.getClassName(),
                    ctx.getPackageName(), p -> label(ctx, p));
            if (ev == null) continue;
            if (ev.k == 'S') state.onSince = ev.t;
            else if (ev.k == 'O') state.onSince = 0;
            else if (ev.k == 'A') {
                state.pkg = ev.pkg;
                state.label = ev.label;
            }
            if (out != null && ev.t >= keepFrom) out.add(ev);
        }
    }
}
