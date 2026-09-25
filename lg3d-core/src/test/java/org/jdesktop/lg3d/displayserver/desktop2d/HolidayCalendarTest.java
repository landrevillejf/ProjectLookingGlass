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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link HolidayCalendar}'s headless behaviour: the pure region-token
 * resolution (explicit tokens win, {@code AUTO}/blank fall back to the locale's
 * country), the {@code jbusinessday}-backed holiday lists per region, weekend
 * classification, and the per-year memoisation.
 */
class HolidayCalendarTest {

    @Test
    @DisplayName("an explicit region token is trimmed and upper-cased")
    void resolveExplicitRegion() {
        assertEquals("US", HolidayCalendar.resolveRegion("us", Locale.CANADA));
        assertEquals("CA", HolidayCalendar.resolveRegion(" CA ", Locale.US));
        assertEquals("CA:QUEBEC",
                HolidayCalendar.resolveRegion("ca:quebec", Locale.US));
    }

    @Test
    @DisplayName("AUTO/blank/null resolve from the locale's country")
    void resolveAutoRegion() {
        assertEquals("CA", HolidayCalendar.resolveRegion("AUTO", Locale.CANADA));
        assertEquals("CA", HolidayCalendar.resolveRegion("auto", Locale.CANADA));
        assertEquals("US", HolidayCalendar.resolveRegion("AUTO", Locale.US));
        assertEquals("US", HolidayCalendar.resolveRegion(null, Locale.FRANCE));
        assertEquals("US", HolidayCalendar.resolveRegion("  ", Locale.JAPAN));
        assertEquals("US", HolidayCalendar.resolveRegion("AUTO", null));
    }

    @Test
    @DisplayName("US federal holidays include Independence Day")
    void usFederalHolidays() {
        List<LocalDate> us = HolidayCalendar.computeHolidays("US", 2026);
        assertTrue(us.contains(LocalDate.of(2026, 7, 4)));
        assertTrue(us.contains(LocalDate.of(2026, 12, 25)));
    }

    @Test
    @DisplayName("Canadian federal holidays include Canada Day")
    void canadianFederalHolidays() {
        List<LocalDate> ca = HolidayCalendar.computeHolidays("CA", 2026);
        assertTrue(ca.contains(LocalDate.of(2026, 7, 1)));
        assertFalse(ca.contains(LocalDate.of(2026, 7, 4)),
                "US Independence Day is not a Canadian federal holiday");
    }

    @Test
    @DisplayName("a province token adds that province's holiday (Quebec's St-Jean)")
    void provinceHolidays() {
        List<LocalDate> qc = HolidayCalendar.computeHolidays("CA:QUEBEC", 2026);
        assertTrue(qc.contains(LocalDate.of(2026, 6, 24)),
                "St-Jean-Baptiste Day is a Quebec statutory holiday");
        assertTrue(qc.contains(LocalDate.of(2026, 7, 1)));
    }

    @Test
    @DisplayName("an unknown province/state token degrades to no holidays, not a throw")
    void unknownSubdivisionDegrades() {
        assertTrue(HolidayCalendar.computeHolidays("CA:NOTAPROVINCE", 2026).isEmpty());
        assertTrue(HolidayCalendar.computeHolidays("US:NOTASTATE", 2026).isEmpty());
    }

    @Test
    @DisplayName("an unrecognised region falls back to US federal")
    void unknownRegionFallsBackToUs() {
        List<LocalDate> xx = HolidayCalendar.computeHolidays("XX", 2026);
        assertTrue(xx.contains(LocalDate.of(2026, 7, 4)));
        assertTrue(HolidayCalendar.computeHolidays(null, 2026)
                .contains(LocalDate.of(2026, 7, 4)));
    }

    @Test
    @DisplayName("weekend classification: Sat/Sun true, weekdays false, null false")
    void weekendClassification() {
        HolidayCalendar cal = new HolidayCalendar("US", Locale.US);
        assertTrue(cal.isWeekend(LocalDate.of(2026, 3, 14)), "Saturday");
        assertTrue(cal.isWeekend(LocalDate.of(2026, 3, 15)), "Sunday");
        assertFalse(cal.isWeekend(LocalDate.of(2026, 3, 16)), "Monday");
        assertFalse(cal.isWeekend(null));
    }

    @Test
    @DisplayName("isHoliday reflects the resolved region's list")
    void holidayClassification() {
        HolidayCalendar us = new HolidayCalendar("US", Locale.US);
        assertEquals("US", us.region());
        assertTrue(us.isHoliday(LocalDate.of(2026, 7, 4)));
        assertFalse(us.isHoliday(LocalDate.of(2026, 7, 3)));
        assertFalse(us.isHoliday(null));

        HolidayCalendar ca = new HolidayCalendar("CA", Locale.US);
        assertTrue(ca.isHoliday(LocalDate.of(2026, 7, 1)));
        assertFalse(ca.isHoliday(LocalDate.of(2026, 7, 4)));
    }

    @Test
    @DisplayName("holidays for a year are memoised (same list instance reused)")
    void holidaysAreCached() {
        HolidayCalendar cal = new HolidayCalendar("US", Locale.US);
        assertSame(cal.holidays(2026), cal.holidays(2026));
    }
}
