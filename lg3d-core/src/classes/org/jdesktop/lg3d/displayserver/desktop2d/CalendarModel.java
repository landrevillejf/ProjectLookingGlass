/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * The pure {@code java.time} model behind the 2D taskbar clock's calendar popup:
 * how a month lays out as an ISO Monday-first grid, which cell is today, and how
 * a month is titled.
 *
 * <p>Every method is deterministic given an injected {@link Clock}, so the grid
 * layout — including leap-year Februaries and 31-day months starting on each
 * weekday — is unit-testable headless. {@link CalendarPopup} is the thin Swing
 * view that renders this model on the 2D desktop; the {@code lg3d-widgets}
 * built-in {@code CalendarCard} is a second, Java 3D-free view of the same model
 * on the 3D desktop, so the month layout is written exactly once.</p>
 */
public final class CalendarModel {

    /** Rows in the rendered grid: six weeks always cover any month. */
    public static final int WEEKS = 6;
    /** Columns in the grid: the seven ISO days, Monday first. */
    public static final int DAYS = 7;
    /** The value a {@link #weeksOfMonth} cell holds when it is outside the month. */
    public static final int OUT_OF_MONTH = 0;

    private CalendarModel() {
        // no instances
    }

    /**
     * The date of the grid's first (top-left) cell for {@code month}: the Monday
     * on or before the first of the month, so the grid is ISO Monday-first.
     */
    public static LocalDate firstCellOfMonth(YearMonth month) {
        LocalDate first = month.atDay(1);
        // DayOfWeek.getValue(): Monday=1 .. Sunday=7; step back to Monday.
        return first.minusDays(first.getDayOfWeek().getValue() - 1L);
    }

    /**
     * The month as a {@value #WEEKS}×{@value #DAYS} grid of day-of-month numbers,
     * Monday-first, with {@link #OUT_OF_MONTH} (0) in the leading/trailing cells
     * that belong to the neighbouring months. Always exactly six rows.
     */
    public static int[][] weeksOfMonth(YearMonth month) {
        int[][] grid = new int[WEEKS][DAYS];
        LocalDate start = firstCellOfMonth(month);
        for (int week = 0; week < WEEKS; week++) {
            for (int day = 0; day < DAYS; day++) {
                LocalDate date = start.plusWeeks(week).plusDays(day);
                grid[week][day] = YearMonth.from(date).equals(month)
                        ? date.getDayOfMonth()
                        : OUT_OF_MONTH;
            }
        }
        return grid;
    }

    /**
     * The actual date of a grid cell, including the neighbouring-month days the
     * grid pads with. Lets the view resolve any cell to a {@link LocalDate}.
     */
    public static LocalDate cellDate(YearMonth month, int week, int dayOfWeek) {
        return firstCellOfMonth(month).plusWeeks(week).plusDays(dayOfWeek);
    }

    /** True when {@code date} falls inside {@code month}. */
    public static boolean isInMonth(LocalDate date, YearMonth month) {
        return date != null && month != null && YearMonth.from(date).equals(month);
    }

    /** True when {@code date} is "today" according to {@code clock}. */
    public static boolean isToday(LocalDate date, Clock clock) {
        return date != null && clock != null && date.equals(LocalDate.now(clock));
    }

    /** The current month according to {@code clock}. */
    public static YearMonth currentMonth(Clock clock) {
        return YearMonth.now(clock);
    }

    /** The current date according to {@code clock}. */
    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock);
    }

    /** A human month title, e.g. {@code "March 2026"}. */
    public static String title(YearMonth month) {
        if (month == null) {
            return "";
        }
        return month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + " " + month.getYear();
    }

    /** The single-letter weekday headers, Monday-first. */
    public static String[] weekdayHeaders() {
        String[] headers = new String[DAYS];
        for (int i = 0; i < DAYS; i++) {
            headers[i] = DayOfWeek.of(i + 1)
                    .getDisplayName(TextStyle.NARROW, Locale.ENGLISH);
        }
        return headers;
    }
}
