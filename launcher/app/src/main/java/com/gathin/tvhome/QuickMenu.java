package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import com.gathin.tvhome.family.GearPress;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One remote button, two answers, over any app and without stopping it: a single tap opens what the TV opens for that
 * button (on the TCL BeyondTV2 the settings panel, scancode 561), two quick taps open the sleep timer over the video (the time left when it already runs).
 * Theme key quick_menu_scancode names the button (a number, or a list for remotes that send it differently: the TCL
 * IR remote sends scancode 561, its Bluetooth remote 233); 0 or absent leaves it to the system. The button's own press is
 * taken, so the single tap is the helper's call to the TCL panel service, 350 ms late.
 * Keys reach the helper only while it filters them, and a filtered release held up on this TV makes Android repeat
 * the press (see FamilyHooks.updateKeyFilter), so the filter is on only while an app other than Home is in front.
 * On Home the launcher's own window gets every key unfiltered, so MainActivity runs a second instance of this class
 * on the same button: the helper covers other apps, the activity covers Home, and neither sees the other's keys.
 */
final class QuickMenu {
    private static final String TAG = "QuickMenu";
    private static final ComponentName TCL_PANEL = new ComponentName("com.tcl.settings", "com.tcl.settings.ShowWindowService");

    private final Context service;
    private final Handler handler;
    private final GearPress press = new GearPress();
    private int[] scancodes = new int[0];
    private boolean foreign;
    private final Runnable single = this::singleTap;

    QuickMenu(Context service, Handler handler) {
        this.service = service;
        this.handler = handler;
    }

    void configure(JSONObject overlay) {
        scancodes = readScancodes(overlay);
        if (scancodes.length == 0) handler.removeCallbacks(single);
        // No window event has come yet when the helper starts with an app already in front: our own process is in the
        // foreground only while Home is on screen.
        android.app.ActivityManager.RunningAppProcessInfo me = new android.app.ActivityManager.RunningAppProcessInfo();
        android.app.ActivityManager.getMyMemoryState(me);
        foreign = me.importance != android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    /** quick_menu_scancode: one number, or a list when the TV has two remotes that send the button differently. */
    private static int[] readScancodes(JSONObject overlay) {
        Object raw = overlay != null ? overlay.opt("quick_menu_scancode") : null;
        if (raw instanceof Number) {
            int one = ((Number) raw).intValue();
            return one > 0 ? new int[] {one} : new int[0];
        }
        if (!(raw instanceof JSONArray)) return new int[0];
        JSONArray list = (JSONArray) raw;
        int[] out = new int[list.length()];
        int n = 0;
        for (int i = 0; i < out.length; i++) {
            int code = list.optInt(i, 0);
            if (code > 0) out[n++] = code;
        }
        return java.util.Arrays.copyOf(out, n);
    }

    private boolean isButton(int code) {
        for (int c : scancodes) if (c == code) return true;
        return false;
    }

    boolean enabled() {
        return scancodes.length > 0;
    }

    /** True while the helper has to see keys for this: configured and another app is in front. */
    boolean wantsKeys() {
        return scancodes.length > 0 && foreign;
    }

    /** A window-state event; true when the key filter has to be reconsidered. */
    boolean onWindow(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (scancodes.length == 0 || pkg == null || cls == null) return false;
        try {
            // Only activities count: a toast, the volume bar or a panel does not change the app in front.
            service.getPackageManager().getActivityInfo(new ComponentName(pkg.toString(), cls.toString()), 0);
        } catch (PackageManager.NameNotFoundException notAnActivity) {
            return false;
        }
        boolean now = !pkg.toString().equals(service.getPackageName()) && !pkg.toString().equals("com.android.systemui");
        if (now == foreign) return false;
        foreign = now;
        return true;
    }

    /** True when the key was the button: both its press and release are taken. */
    boolean onKey(KeyEvent event) {
        if (!isButton(event.getScanCode())) return false;
        long now = SystemClock.elapsedRealtime();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (press.onKey(down, down && event.getRepeatCount() > 0, now)) {
            handler.removeCallbacks(single);
            doubleTap();
        } else {
            long wait = press.nextDelay(now);
            if (wait >= 0) {
                handler.removeCallbacks(single);
                handler.postDelayed(single, wait);
            }
        }
        return true;
    }

    private void singleTap() {
        if (!press.singleDue(SystemClock.elapsedRealtime())) return;
        Intent panel = new Intent("com.tcl.settings.SHOW_WINDOW").setComponent(TCL_PANEL).putExtra("Type", "Settings");
        try {
            service.startService(panel);
            Log.i(TAG, "single tap: TV settings panel");
        } catch (RuntimeException e) {
            Log.w(TAG, "single tap: could not open the TV settings panel: " + e);
        }
    }

    private void doubleTap() {
        // A timer that is already running shows the time left; Up and Down then round it to the next choice.
        int minutes = SleepTimer.remainingMinutes();
        if (minutes <= 0) minutes = SleepTimer.cycle(service);
        Log.i(TAG, "double tap: sleep timer at " + minutes + " min");
        if (minutes > 0) SleepTimer.tell(service, minutes);
    }
}
