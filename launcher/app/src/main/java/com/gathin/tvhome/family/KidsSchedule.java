package com.gathin.tvhome.family;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When the TV may be used, on the TV itself: allowed windows for weekdays and for weekends, a daily limit and the
 * warnings before the end. A window "22:00-07:00" runs over midnight and belongs to the day it starts on. A list that is
 * null has no windows (the day is unrestricted); an empty list is a day with no allowed time at all.
 */
public final class KidsSchedule {
    /** An allowed window in minutes of the day; endMin up to 1440, and at or below startMin when it runs over midnight. */
    public static final class Window {
        public final int startMin, endMin;

        public Window(int startMin, int endMin) {
            if (startMin < 0 || startMin > 1439 || endMin < 1 || endMin > 1440 || startMin == endMin) {
                throw new IllegalArgumentException("window " + startMin + "-" + endMin);
            }
            this.startMin = startMin;
            this.endMin = endMin;
        }

        public boolean overMidnight() {
            return endMin <= startMin;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Window && ((Window) o).startMin == startMin && ((Window) o).endMin == endMin;
        }

        @Override
        public int hashCode() {
            return startMin * 31 + endMin;
        }

        @Override
        public String toString() {
            return time(startMin) + "-" + time(endMin);
        }

        private static String time(int min) {
            return String.format(java.util.Locale.ROOT, "%02d:%02d", min / 60, min % 60);
        }
    }

    private static final Pattern WINDOW = Pattern.compile("^\\s*(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})\\s*$");

    /** "07:00-20:30" as a window, or null when it is not one. */
    public static Window parseWindow(String text) {
        if (text == null) return null;
        Matcher m = WINDOW.matcher(text);
        if (!m.matches()) return null;
        int sh = Integer.parseInt(m.group(1)), sm = Integer.parseInt(m.group(2));
        int eh = Integer.parseInt(m.group(3)), em = Integer.parseInt(m.group(4));
        if (sh > 23 || sm > 59 || eh > 24 || em > 59 || (eh == 24 && em != 0)) return null;
        int end = eh * 60 + em;
        int start = sh * 60 + sm;
        if (end == 0) end = 1440;
        if (end == start) return null;
        return new Window(start, end);
    }

    public final boolean enabled;
    public final ZoneId zone;
    /** Monday to Friday; null for no windows. */
    public final List<Window> weekdays;
    /** Saturday and Sunday; null for no windows. */
    public final List<Window> weekends;
    /** Minutes of use per day; 0 for no limit. */
    public final int dailyLimitMin;
    /** Minutes before the end at which the TV warns, longest first. */
    public final int[] warnMin;

    public KidsSchedule(boolean enabled, ZoneId zone, List<Window> weekdays, List<Window> weekends, int dailyLimitMin, int[] warnMin) {
        this.enabled = enabled;
        this.zone = zone;
        this.weekdays = weekdays == null ? null : Collections.unmodifiableList(new ArrayList<>(weekdays));
        this.weekends = weekends == null ? null : Collections.unmodifiableList(new ArrayList<>(weekends));
        this.dailyLimitMin = Math.max(0, dailyLimitMin);
        this.warnMin = warnMin.clone();
    }

    /** Off: nothing is restricted. */
    public static KidsSchedule off() {
        return new KidsSchedule(false, ZoneId.systemDefault(), null, null, 0, new int[] {10, 5, 1});
    }

    List<Window> windowsFor(DayOfWeek day) {
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY ? weekends : weekdays;
    }
}
