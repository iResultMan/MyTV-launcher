package com.gathin.tvhome.family;

/**
 * What the TV's tidy-up decides, with no Android in it so it can be tested. It is driven by events, never by a clock:
 * Home went quiet, the screen went off, the system asked apps to give memory back, the VPN dropped. Each answer is
 * "do it or not" with a cooldown, so a TV that stays tight is not cleaned again and again. Times are milliseconds of
 * one monotonic clock.
 */
public final class SanitizerRules {
    /** Home has been in front with no key pressed for this long: the TV is not being used. */
    public static final long IDLE_MS = 3 * 60_000L;
    /** The VPN has to stay down this long before its app is started. */
    public static final long VPN_DOWN_MS = 4 * 60_000L;
    public static final long CLEAN_COOLDOWN_MS = 20 * 60_000L;
    public static final long VPN_COOLDOWN_MS = 30 * 60_000L;

    private static final long NEVER = Long.MIN_VALUE;
    private long lastClean = NEVER;
    private long lastVpnTry = NEVER;

    /** Memory is tight: little RAM left to apps, or the swap nearly used up (the TV starts to stutter before either runs out). */
    public static boolean memoryTight(long memTotalKb, long memAvailableKb, long swapTotalKb, long swapFreeKb) {
        boolean lowRam = memTotalKb > 0 && memAvailableKb * 100 < memTotalKb * 15;
        boolean swapFull = swapTotalKb > 0 && swapFreeKb * 100 < swapTotalKb * 25;
        return lowRam || swapFull;
    }

    /**
     * A level from onTrimMemory that means the whole system is short of memory: the running app is asked to give some
     * back (RUNNING_LOW 10, RUNNING_CRITICAL 15), or the process is first in line to be killed (MODERATE 60,
     * COMPLETE 80). The in-between levels only say the UI is hidden or the process is in the background.
     */
    public static boolean pressure(int trimLevel) {
        return trimLevel == 10 || trimLevel == 15 || trimLevel >= 60;
    }

    /** Close the background apps now? True when memory is tight and the last clean is long enough ago. */
    public boolean shouldClean(long now, boolean tight) {
        if (!tight || (lastClean != NEVER && now - lastClean < CLEAN_COOLDOWN_MS)) return false;
        lastClean = now;
        return true;
    }

    /** Start the VPN app now? True when the last try is long enough ago. */
    public boolean shouldStartVpn(long now) {
        if (lastVpnTry != NEVER && now - lastVpnTry < VPN_COOLDOWN_MS) return false;
        lastVpnTry = now;
        return true;
    }
}
