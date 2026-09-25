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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link CalendarModel}'s pure grid maths with an injected clock: the
 * Monday-first first cell, the 6×7 layout for known months, leap-year February,
 * the structural invariants across many months (so every start-weekday and month
 * length is exercised), today/in-month detection and the title.
 */
class CalendarModelTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");

    private static Clock clockOn(LocalDate date) {
        return Clock.fixed(date.atStartOfDay(ZONE).toInstant(), ZONE);
    }

    /** Flattens a grid row-major. */
    private static int[] flatten(int[][] grid) {
        int[] flat = new int[grid.length * grid[0].length];
        int i = 0;
        for (int[] row : grid) {
            for (int cell : row) {
                flat[i++] = cell;
            }
        }
        return flat;
    }

    @Test
    @DisplayName("the first cell is the Monday on or before the 1st")
    void firstCellIsMonday() {
        // Feb 2021 starts on a Monday, so the first cell is the 1st itself.
        assertEquals(LocalDate.of(2021, 2, 1),
                CalendarModel.firstCellOfMonth(YearMonth.of(2021, 2)));
        // Feb 2020 starts on a Saturday, so the grid opens on Mon 27 Jan.
        assertEquals(LocalDate.of(2020, 1, 27),
                CalendarModel.firstCellOfMonth(YearMonth.of(2020, 2)));
        // Always a Monday, never more than six days before the 1st.
        for (YearMonth m : sampleMonths()) {
            LocalDate first = CalendarModel.firstCellOfMonth(m);
            assertEquals(DayOfWeek.MONDAY, first.getDayOfWeek());
            long back = m.atDay(1).toEpochDay() - first.toEpochDay();
            assertTrue(back >= 0 && back <= 6, m + " opens " + back + " days back");
        }
    }

    @Test
    @DisplayName("a known month (Feb 2021) lays out Monday-first in four weeks")
    void knownMonthLayout() {
        int[][] grid = CalendarModel.weeksOfMonth(YearMonth.of(2021, 2));
        assertEquals(CalendarModel.WEEKS, grid.length);
        for (int[] row : grid) {
            assertEquals(CalendarModel.DAYS, row.length);
        }
        assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6, 7}, grid[0]);
        assertArrayEquals(new int[] {22, 23, 24, 25, 26, 27, 28}, grid[3]);
        assertArrayEquals(new int[7], grid[4], "no fifth week of February");
        assertArrayEquals(new int[7], grid[5]);
    }

    @Test
    @DisplayName("leap-year February 2020 includes the 29th")
    void leapYearFebruary() {
        int[] flat = flatten(CalendarModel.weeksOfMonth(YearMonth.of(2020, 2)));
        List<Integer> days = nonZero(flat);
        assertEquals(29, days.size());
        assertEquals(29, days.get(days.size() - 1), "the last day is the 29th");
    }

    @Test
    @DisplayName("grid invariants hold across many months and start-weekdays")
    void invariantsAcrossMonths() {
        for (YearMonth month : sampleMonths()) {
            int[][] grid = CalendarModel.weeksOfMonth(month);
            assertEquals(CalendarModel.WEEKS, grid.length);
            int[] flat = flatten(grid);

            // Leading padding equals the 1st's Monday-first weekday index.
            int leading = 0;
            while (leading < flat.length && flat[leading] == CalendarModel.OUT_OF_MONTH) {
                leading++;
            }
            assertEquals(month.atDay(1).getDayOfWeek().getValue() - 1, leading,
                    month + " leading padding");

            // The non-zero cells are exactly 1..lengthOfMonth in order.
            List<Integer> days = nonZero(flat);
            assertEquals(month.lengthOfMonth(), days.size(), month + " day count");
            for (int i = 0; i < days.size(); i++) {
                assertEquals(i + 1, days.get(i), month + " day " + i);
            }

            // Trailing padding fills the rest of the 42 cells.
            assertEquals(CalendarModel.WEEKS * CalendarModel.DAYS,
                    leading + days.size() + trailingZeros(flat));
        }
    }

    @Test
    @DisplayName("cellDate resolves a padded cell to the neighbouring month")
    void cellDateResolvesPadding() {
        YearMonth march = YearMonth.of(2026, 3);
        // The first cell is the Monday on/before 1 March 2026.
        LocalDate firstCell = CalendarModel.firstCellOfMonth(march);
        assertEquals(firstCell, CalendarModel.cellDate(march, 0, 0));
        assertEquals(DayOfWeek.MONDAY, firstCell.getDayOfWeek());
        assertFalse(firstCell.isAfter(march.atDay(1)),
                "the grid opens on or before the 1st");
        // A cell known to be in-month maps back to that day.
        for (int week = 0; week < CalendarModel.WEEKS; week++) {
            for (int day = 0; day < CalendarModel.DAYS; day++) {
                LocalDate date = CalendarModel.cellDate(march, week, day);
                assertEquals(CalendarModel.isInMonth(date, march),
                        CalendarModel.weeksOfMonth(march)[week][day]
                                != CalendarModel.OUT_OF_MONTH);
            }
        }
    }

    @Test
    @DisplayName("isToday compares against the injected clock")
    void isTodayWithClock() {
        Clock clock = clockOn(LocalDate.of(2026, 3, 15));
        assertTrue(CalendarModel.isToday(LocalDate.of(2026, 3, 15), clock));
        assertFalse(CalendarModel.isToday(LocalDate.of(2026, 3, 16), clock));
        assertFalse(CalendarModel.isToday(null, clock));
        assertFalse(CalendarModel.isToday(LocalDate.of(2026, 3, 15), null));
    }

    @Test
    @DisplayName("currentMonth and today follow the injected clock")
    void currentFromClock() {
        Clock clock = clockOn(LocalDate.of(2026, 3, 15));
        assertEquals(YearMonth.of(2026, 3), CalendarModel.currentMonth(clock));
        assertEquals(LocalDate.of(2026, 3, 15), CalendarModel.today(clock));
    }

    @Test
    @DisplayName("isInMonth is true only for dates inside the month")
    void isInMonth() {
        YearMonth march = YearMonth.of(2026, 3);
        assertTrue(CalendarModel.isInMonth(LocalDate.of(2026, 3, 1), march));
        assertTrue(CalendarModel.isInMonth(LocalDate.of(2026, 3, 31), march));
        assertFalse(CalendarModel.isInMonth(LocalDate.of(2026, 2, 28), march));
        assertFalse(CalendarModel.isInMonth(LocalDate.of(2026, 4, 1), march));
        assertFalse(CalendarModel.isInMonth(null, march));
    }

    @Test
    @DisplayName("title formats as 'Month Year'")
    void titleFormatting() {
        assertEquals("March 2026", CalendarModel.title(YearMonth.of(2026, 3)));
        assertEquals("February 2020", CalendarModel.title(YearMonth.of(2020, 2)));
        assertEquals("", CalendarModel.title(null));
    }

    @Test
    @DisplayName("weekday headers are Monday-first single letters")
    void weekdayHeaders() {
        String[] headers = CalendarModel.weekdayHeaders();
        assertEquals(CalendarModel.DAYS, headers.length);
        assertEquals("M", headers[0], "Monday first");
        assertEquals("S", headers[6], "Sunday last");
    }

    // -- helpers -------------------------------------------------------

    private static List<Integer> nonZero(int[] flat) {
        List<Integer> days = new ArrayList<>();
        for (int cell : flat) {
            if (cell != CalendarModel.OUT_OF_MONTH) {
                days.add(cell);
            }
        }
        return days;
    }

    private static int trailingZeros(int[] flat) {
        int trailing = 0;
        for (int i = flat.length - 1; i >= 0 && flat[i] == CalendarModel.OUT_OF_MONTH; i--) {
            trailing++;
        }
        return trailing;
    }

    /** Every month from 2019-01 to 2031-12: covers all start-weekdays/lengths. */
    private static List<YearMonth> sampleMonths() {
        List<YearMonth> months = new ArrayList<>();
        YearMonth m = YearMonth.of(2019, 1);
        YearMonth end = YearMonth.of(2031, 12);
        while (!m.isAfter(end)) {
            months.add(m);
            m = m.plusMonths(1);
        }
        return months;
    }
}
