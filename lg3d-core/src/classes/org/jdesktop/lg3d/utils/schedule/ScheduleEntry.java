/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.utils.schedule;

import java.time.LocalTime;

/**
 * One entry of the wallpaper schedule: at {@code hour:minute} the desktop
 * switches to {@code wallpaper}. The schedule is an ordered, variable-length
 * list of these (two, four, ten - whatever the user adds), each independent of
 * the others and of the separate lighting schedule.
 *
 * <p>A pure, Java-3D-free value type so the ordering and clamping rules are
 * unit-testable in the headless test JVM. The compact constructor clamps the
 * time into a valid 0-23 / 0-59 range and trims the wallpaper (null becomes the
 * empty "use bundled default" value), so no caller can build an out-of-range
 * entry.</p>
 */
public record ScheduleEntry(int hour, int minute, String wallpaper)
        implements Comparable<ScheduleEntry> {

    /** The empty wallpaper value meaning "use the bundled default". */
    public static final String DEFAULT_WALLPAPER = "";

    public ScheduleEntry {
        hour = Math.max(0, Math.min(23, hour));
        minute = Math.max(0, Math.min(59, minute));
        wallpaper = (wallpaper == null) ? DEFAULT_WALLPAPER : wallpaper.trim();
    }

    /** The entry's time of day. */
    public LocalTime time() {
        return LocalTime.of(hour, minute);
    }

    /** Minute-of-day (0-1439), the ordering key across the day. */
    public int minuteOfDay() {
        return hour * 60 + minute;
    }

    /** Orders entries by their time of day (earliest first). */
    @Override
    public int compareTo(ScheduleEntry other) {
        return Integer.compare(minuteOfDay(), other.minuteOfDay());
    }

    /** Renders as {@code HH:MM} for list display. */
    public String formatTime() {
        return String.format("%02d:%02d", hour, minute);
    }
}
