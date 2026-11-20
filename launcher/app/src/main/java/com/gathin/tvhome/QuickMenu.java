package com.gathin.tvhome;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One remote button, two answers, over any app and without stopping it: a single tap opens what the TV opens for that
 * button (on the TCL BeyondTV2 the settings panel, scancode 561, opened by the firmware itself), two quick taps open the
 * sleep timer over the video (the time left when it already runs).
 * Theme key quick_menu_scancode names the button (a number, or a list for remotes that send it differently: the TCL
 * IR remote sends scancode 561, its Bluetooth remote 233); 0 or absent leaves it to the system.
 * Keys reach the helper only while it filters them, and a filtered release held up on this TV makes Android repeat
 * the press (arrows skip rows, volume jumps; see FamilyHooks.updateKeyFilter), so the filter is never on for this
 * button alone. The TCL firmware opens its settings panel itself on the first press, on Home and in every other app;
 * when that panel's window appears the helper sees keys for ARM_MS, so the second press of a double tap reaches it
 * whatever window has the focus. MainActivity runs a second instance of this class for Home, where the activity gets
 * every key unfiltered; neither instance sees the other's keys.
 */
final class QuickMenu {
    private static final String TAG = "QuickMenu";

    private final Context service;
    private final boolean opensPanel;
    private int[] scancodes = new int[0];
    private long armedUntil;
    /** Home mode: when the last press of the button came in (shared by the helper and the activity). */
    private static long lastHomePress;
    private static final long HOME_DOUBLE_MS = 700;
    static final long ARM_MS = 1200;
    private static final String TCL_PANEL_PACKAGE = "com.tcl.settings";
    /** Armed mode: the press the helper took is the second of a double tap, so its release is taken too. */
    private boolean tookDown;

    /** opensPanel: false for the activity on Home, whose keys the firmware handles itself; the helper takes only the second press. */
    QuickMenu(Context service, boolean opensPanel) {
        this.service = service;
        this.opensPanel = opensPanel;
    }

    void configure(JSONObject overlay) {
        scancodes = readScancodes(overlay);
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

    /** True while the helper has to see keys for this: only for a moment after the button's first press. */
    boolean wantsKeys() {
        return scancodes.length > 0 && SystemClock.elapsedRealtime() < armedUntil;
    }

    void arm(long ms) {
        armedUntil = SystemClock.elapsedRealtime() + ms;
    }

    /**
     * A window-state event; true when the TV's settings panel has just opened, which the firmware does on the button's
     * first press: that is the first tap of a possible double tap, and the filter has to be armed for the second.
     * Events inside an open panel (a submenu) do not count while it is armed.
     */
    boolean onWindow(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (scancodes.length == 0 || pkg == null || !TCL_PANEL_PACKAGE.contentEquals(pkg)) return false;
        long now = SystemClock.elapsedRealtime();
        if (now < armedUntil) return false;
        lastHomePress = now;
        return true;
    }

    /** True when the key was the button: both its press and release are taken. */
    boolean onKey(KeyEvent event) {
        if (!isButton(event.getScanCode())) return false;
        long now = SystemClock.elapsedRealtime();
        if (!opensPanel) return onHomeKey(event, now);
        return onArmedKey(event, now);
    }

    /**
     * The helper sees the button only while armed. A press within HOME_DOUBLE_MS of the panel opening is the second tap:
     * the panel is closed and the sleep timer shown. Any other press goes on to the firmware (it closes the panel).
     */
    private boolean onArmedKey(KeyEvent event, long now) {
        if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean took = tookDown;
            tookDown = false;
            return took;
        }
        if (event.getRepeatCount() > 0) return tookDown;
        if (now - lastHomePress > HOME_DOUBLE_MS) return false;
        lastHomePress = 0;
        tookDown = true;
        HomeKeyService.closePanel();
        doubleTap();
        return true;
    }

    /**
     * On Home the TV's own firmware opens its panel on the press, and that panel takes the window focus within a few
     * milliseconds: the release and the second press then go to the panel, and a press-and-release state machine never
     * completes. So only the presses count: two within HOME_DOUBLE_MS are a double tap, and the first one makes the
     * helper see keys for a moment, because only its filter gets the second press whatever window has the focus.
     */
    private boolean onHomeKey(KeyEvent event, long now) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (now - lastHomePress <= HOME_DOUBLE_MS) {
                lastHomePress = 0;
                // The first press made the firmware open its panel and the helper took the second, so nothing
                // closes it: Back does, and on Home a Back with no panel open does nothing.
                HomeKeyService.closePanel();
                doubleTap();
            } else {
                lastHomePress = now;
                HomeKeyService.armGear(ARM_MS);
            }
        }
        return true;
    }

    private void doubleTap() {
        // A timer that is already running shows the time left; Up and Down then round it to the next choice.
        int minutes = SleepTimer.remainingMinutes();
        if (minutes <= 0) minutes = SleepTimer.cycle(service);
        Log.i(TAG, "double tap: sleep timer at " + minutes + " min");
        if (minutes > 0) SleepTimer.tell(service, minutes);
    }
}
