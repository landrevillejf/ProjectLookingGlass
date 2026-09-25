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
import java.util.Locale;
import org.jdesktop.lg3d.utils.prefs.HolidayRegions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link HolidayCalendar}'s headless instance behaviour: the region it
 * resolves to (delegated to {@link HolidayRegions}), weekend/holiday
 * classification for that region, and the per-year memoisation. The pure
 * region-token/holiday-list logic itself is covered by
 * {@code org.jdesktop.lg3d.utils.prefs.HolidayRegionsTest}.
 */
class HolidayCalendarTest {

    @Test
    @DisplayName("the default constructor uses the persisted, locale-resolved region")
    void defaultConstructorUsesConfiguredRegion() {
        assertEquals(HolidayRegions.configuredRegion(), new HolidayCalendar().region());
    }

    @Test
    @DisplayName("AUTO resolves from the injected locale; explicit tokens win")
    void resolvesRegionThroughHelper() {
        assertEquals("CA", new HolidayCalendar("AUTO", Locale.CANADA).region());
        assertEquals("US", new HolidayCalendar("AUTO", Locale.US).region());
        assertEquals("CA:QUEBEC", new HolidayCalendar("ca:quebec", Locale.US).region());
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
