package com.gathin.tvhome.family;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * The rules of the kids' schedule with no Android in them: given the time, today's use and a bonus, is the TV allowed,
 * for how long, and when does it open next. Every delivery channel (the schedule on the TV, the phone on the home
 * network, a cloud link) feeds this same evaluation and only adds a way to send it commands.
 */
public final class KidsPolicy {
    public enum Reason {
        /** Within an allowed window and under the daily limit, or no restriction is set. */
        ALLOWED,
        /** A parent gave extra time with the code. */
        BONUS,
        /** Outside every allowed window. */
        OUTSIDE_WINDOW,
        /** The daily limit is used up. */
        LIMIT_REACHED
    }

    public static final class Decision {
        public final boolean allowed;
        public final Reason reason;
        /** ms until the TV stops being allowed, -1 when nothing ends it; 0 when it is not allowed now. */
        public final long leftMs;
        /** When the next allowed window opens (ms since the epoch), 0 when it is allowed now or none opens within a week. */
        public final long opensAtMs;

        Decision(boolean allowed, Reason reason, long leftMs, long opensAtMs) {
            this.allowed = allowed;
            this.reason = reason;
            this.leftMs = leftMs;
            this.opensAtMs = opensAtMs;
        }
    }

    private static final long MINUTE_MS = 60_000L;
    private static final int LOOKAHEAD_DAYS = 8;

    private final KidsSchedule schedule;

    public KidsPolicy(KidsSchedule schedule) {
        this.schedule = schedule;
    }

    /** The start of the schedule's day that holds nowMs: the day the daily limit counts from. */
    public long dayStartMs(long nowMs) {
        ZoneId zone = schedule.zone;
        return java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private long nextDayStartMs(long nowMs) {
        ZoneId zone = schedule.zone;
        return java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /**
     * @param usedTodayMs   the TV's use since dayStartMs(nowMs)
     * @param bonusUntilMs  extra time given by a parent runs until then (0 for none); it lifts the windows and the limit
     */
    public Decision evaluate(long nowMs, long usedTodayMs, long bonusUntilMs) {
        if (!schedule.enabled) return new Decision(true, Reason.ALLOWED, -1, 0);
        if (bonusUntilMs > nowMs) return new Decision(true, Reason.BONUS, bonusUntilMs - nowMs, 0);
        long limitLeft = schedule.dailyLimitMin > 0 ? schedule.dailyLimitMin * MINUTE_MS - usedTodayMs : Long.MAX_VALUE;
        // The limit counts per day, so it lifts at the start of the next one, not at a later window of today.
        if (limitLeft <= 0) return new Decision(false, Reason.LIMIT_REACHED, 0, nextOpening(nextDayStartMs(nowMs) - 1));
        long windowLeft = windowLeft(nowMs);
        if (windowLeft == 0) return new Decision(false, Reason.OUTSIDE_WINDOW, 0, nextOpening(nowMs));
        long left = Math.min(windowLeft < 0 ? Long.MAX_VALUE : windowLeft, limitLeft);
        return new Decision(true, Reason.ALLOWED, left == Long.MAX_VALUE ? -1 : left, 0);
    }

    /** ms to the end of the allowed time that holds nowMs, -1 when today has no windows, 0 when nowMs is outside them. */
    private long windowLeft(long nowMs) {
        ZoneId zone = schedule.zone;
        LocalDate today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        long end = 0;
        // A window that runs over midnight belongs to the day before it ends.
        for (LocalDate day = today.minusDays(1); !day.isAfter(today); day = day.plusDays(1)) {
            List<KidsSchedule.Window> windows = schedule.windowsFor(day.getDayOfWeek());
            if (windows == null) continue;
            for (KidsSchedule.Window w : windows) {
                long from = instant(day, w.startMin, false, zone);
                long to = instant(day, w.endMin, w.overMidnight(), zone);
                if (from <= nowMs && nowMs < to) end = Math.max(end, to);
            }
        }
        // A day with no windows set is unrestricted, whatever yesterday's windows said.
        if (schedule.windowsFor(today.getDayOfWeek()) == null) return -1;
        if (end == 0) return 0;
        // Windows that touch (07:00-12:00 and 12:00-20:00) are one stretch.
        boolean grown = true;
        while (grown) {
            grown = false;
            for (LocalDate day = today.minusDays(1); !day.isAfter(today.plusDays(1)); day = day.plusDays(1)) {
                List<KidsSchedule.Window> windows = schedule.windowsFor(day.getDayOfWeek());
                if (windows == null) continue;
                for (KidsSchedule.Window w : windows) {
                    long from = instant(day, w.startMin, false, zone);
                    long to = instant(day, w.endMin, w.overMidnight(), zone);
                    if (from <= end && to > end) {
                        end = to;
                        grown = true;
                    }
                }
            }
        }
        return end - nowMs;
    }

    /** The start of the next allowed window after nowMs within a week, 0 when there is none. */
    private long nextOpening(long nowMs) {
        ZoneId zone = schedule.zone;
        LocalDate today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        long best = 0;
        for (int i = 0; i < LOOKAHEAD_DAYS; i++) {
            LocalDate day = today.plusDays(i);
            List<KidsSchedule.Window> windows = schedule.windowsFor(day.getDayOfWeek());
            if (windows == null) {
                // An unrestricted day is open from its first minute (or from now, when the limit is what closed it).
                long start = instant(day, 0, false, zone);
                if (start > nowMs && (best == 0 || start < best)) best = start;
                continue;
            }
            for (KidsSchedule.Window w : windows) {
                long start = instant(day, w.startMin, false, zone);
                if (start > nowMs && (best == 0 || start < best)) best = start;
            }
        }
        return best;
    }

    /** The instant of minute-of-day min on day (on the next day when over), by the clock on the wall of zone. */
    private static long instant(LocalDate day, int min, boolean over, ZoneId zone) {
        LocalDate d = over || min >= 1440 ? day.plusDays(1) : day;
        int m = min >= 1440 ? min - 1440 : min;
        LocalDateTime local = d.atTime(m / 60, m % 60);
        return ZonedDateTime.ofLocal(local, zone, null).toInstant().toEpochMilli();
    }

    /**
     * Which warning is due as the time left goes from prevLeftMs to leftMs: the smallest warning (in minutes) the time
     * has just gone under, or -1 when none. Call it with the previous and current value on every check; a check that
     * skips over several only reports the last, the closest to the end. A left of -1 (nothing ends it) never warns.
     */
    public static int warningDue(long prevLeftMs, long leftMs, int[] warnMin) {
        if (leftMs < 0 || prevLeftMs < 0) return -1;
        int due = -1;
        for (int w : warnMin) {
            long t = w * MINUTE_MS;
            if (prevLeftMs > t && leftMs <= t && leftMs > 0 && (due == -1 || w < due)) due = w;
        }
        return due;
    }
}
