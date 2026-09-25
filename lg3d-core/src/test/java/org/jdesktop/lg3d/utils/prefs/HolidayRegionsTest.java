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
package org.jdesktop.lg3d.utils.prefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link HolidayRegions}, the shared headless seam behind both the
 * calendar popup and the orgchart agenda: the pure region-token resolution
 * (explicit tokens win, {@code AUTO}/blank fall back to the locale's country),
 * the {@code jbusinessday}-backed holiday lists per region, weekend/holiday
 * classification, and the persisted-preference default via
 * {@link HolidayRegions#configuredRegion()}.
 */
class HolidayRegionsTest {

    @Test
    @DisplayName("an explicit region token is trimmed and upper-cased")
    void resolveExplicitRegion() {
        assertEquals("US", HolidayRegions.resolveRegion("us", Locale.CANADA));
        assertEquals("CA", HolidayRegions.resolveRegion(" CA ", Locale.US));
        assertEquals("CA:QUEBEC",
                HolidayRegions.resolveRegion("ca:quebec", Locale.US));
    }

    @Test
    @DisplayName("AUTO/blank/null resolve from the locale's country")
    void resolveAutoRegion() {
        assertEquals("CA", HolidayRegions.resolveRegion("AUTO", Locale.CANADA));
        assertEquals("CA", HolidayRegions.resolveRegion("auto", Locale.CANADA));
        assertEquals("US", HolidayRegions.resolveRegion("AUTO", Locale.US));
        assertEquals("US", HolidayRegions.resolveRegion(null, Locale.FRANCE));
        assertEquals("US", HolidayRegions.resolveRegion("  ", Locale.JAPAN));
        assertEquals("US", HolidayRegions.resolveRegion("AUTO", null));
    }

    @Test
    @DisplayName("configuredRegion resolves the persisted preference deterministically")
    void configuredRegionIsResolved() {
        String region = HolidayRegions.configuredRegion();
        assertNotNull(region);
        assertFalse(region.isEmpty());
        assertEquals(
                HolidayRegions.resolveRegion(
                        DesktopConfig.get().getHolidayRegion(), Locale.getDefault()),
                region);
    }

    @Test
    @DisplayName("US federal holidays include Independence Day")
    void usFederalHolidays() {
        List<LocalDate> us = HolidayRegions.holidays("US", 2026);
        assertTrue(us.contains(LocalDate.of(2026, 7, 4)));
        assertTrue(us.contains(LocalDate.of(2026, 12, 25)));
    }

    @Test
    @DisplayName("Canadian federal holidays include Canada Day")
    void canadianFederalHolidays() {
        List<LocalDate> ca = HolidayRegions.holidays("CA", 2026);
        assertTrue(ca.contains(LocalDate.of(2026, 7, 1)));
        assertFalse(ca.contains(LocalDate.of(2026, 7, 4)),
                "US Independence Day is not a Canadian federal holiday");
    }

    @Test
    @DisplayName("a province token adds that province's holiday (Quebec's St-Jean)")
    void provinceHolidays() {
        List<LocalDate> qc = HolidayRegions.holidays("CA:QUEBEC", 2026);
        assertTrue(qc.contains(LocalDate.of(2026, 6, 24)),
                "St-Jean-Baptiste Day is a Quebec statutory holiday");
        assertTrue(qc.contains(LocalDate.of(2026, 7, 1)));
    }

    @Test
    @DisplayName("an unknown province/state token degrades to no holidays, not a throw")
    void unknownSubdivisionDegrades() {
        assertTrue(HolidayRegions.holidays("CA:NOTAPROVINCE", 2026).isEmpty());
        assertTrue(HolidayRegions.holidays("US:NOTASTATE", 2026).isEmpty());
    }

    @Test
    @DisplayName("an unrecognised region falls back to US federal")
    void unknownRegionFallsBackToUs() {
        List<LocalDate> xx = HolidayRegions.holidays("XX", 2026);
        assertTrue(xx.contains(LocalDate.of(2026, 7, 4)));
        assertTrue(HolidayRegions.holidays(null, 2026)
                .contains(LocalDate.of(2026, 7, 4)));
    }

    @Test
    @DisplayName("weekend classification: Sat/Sun true, weekdays false, null false")
    void weekendClassification() {
        assertTrue(HolidayRegions.isWeekend(LocalDate.of(2026, 3, 14)), "Saturday");
        assertTrue(HolidayRegions.isWeekend(LocalDate.of(2026, 3, 15)), "Sunday");
        assertFalse(HolidayRegions.isWeekend(LocalDate.of(2026, 3, 16)), "Monday");
        assertFalse(HolidayRegions.isWeekend(null));
    }

    @Test
    @DisplayName("isHoliday reflects the given region token")
    void holidayClassification() {
        assertTrue(HolidayRegions.isHoliday("US", LocalDate.of(2026, 7, 4)));
        assertFalse(HolidayRegions.isHoliday("US", LocalDate.of(2026, 7, 3)));
        assertFalse(HolidayRegions.isHoliday("US", null));
        assertTrue(HolidayRegions.isHoliday("CA", LocalDate.of(2026, 7, 1)));
        assertFalse(HolidayRegions.isHoliday("CA", LocalDate.of(2026, 7, 4)));
    }
}
