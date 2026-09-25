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

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
import org.jdesktop.lg3d.utils.prefs.HolidayRegions;

/**
 * Classifies calendar days as statutory holidays or weekends for the 2D
 * taskbar clock's {@link CalendarPopup}, backed by the bundled
 * {@code jbusinessday} library.
 *
 * <p>The region is a small token grammar resolved once per instance:
 * {@code "AUTO"} (the {@link DesktopConfig} default) derives the holiday set
 * from the system {@link Locale} — Canada maps to Canadian federal, everything
 * else to US federal — while an explicit token overrides it: {@code "US"} or
 * {@code "CA"} (federal), {@code "CA:<PROVINCE>"} (e.g. {@code "CA:QUEBEC"}) or
 * {@code "US:<STATE>"}. The Control Center's Desktop panel edits that token and
 * persists it, so the calendar reflects the user's choice across restarts.</p>
 *
 * <p>The token grammar and the {@code jbusinessday} calls are delegated to the
 * shared {@link HolidayRegions} helper so the orgchart {@code AgendaGrid} marks
 * the same holidays from the same preference. This class only adds a
 * per-instance region and a memoised per-year holiday list for the popup's
 * cells. A holiday lookup never throws: if {@code jbusinessday} (or its slf4j
 * dependency) is missing at runtime, or a token names an unknown province/state,
 * the day is simply treated as an ordinary day rather than breaking the
 * calendar.</p>
 */
final class HolidayCalendar {

    private final String region;
    private final Map<Integer, List<LocalDate>> cache = new HashMap<>();

    /** Uses the region persisted in {@link DesktopConfig}, resolved against the default locale. */
    HolidayCalendar() {
        this(DesktopConfig.get().getHolidayRegion());
    }

    /** Uses an explicit configured token, resolved against the default locale. */
    HolidayCalendar(String configuredRegion) {
        this(configuredRegion, Locale.getDefault());
    }

    /** Uses an explicit configured token resolved against {@code locale} (injectable for tests). */
    HolidayCalendar(String configuredRegion, Locale locale) {
        this.region = HolidayRegions.resolveRegion(configuredRegion, locale);
    }

    /** The effective region token this calendar classifies against. */
    String region() {
        return region;
    }

    /** True when {@code date} is a statutory holiday in this calendar's region. */
    boolean isHoliday(LocalDate date) {
        return date != null && holidays(date.getYear()).contains(date);
    }

    /** True when {@code date} falls on a Saturday or Sunday. */
    boolean isWeekend(LocalDate date) {
        return HolidayRegions.isWeekend(date);
    }

    /** The holiday list for {@code year} in this calendar's region, memoised. */
    List<LocalDate> holidays(int year) {
        List<LocalDate> cached = cache.get(year);
        if (cached == null) {
            cached = HolidayRegions.holidays(region, year);
            cache.put(year, cached);
        }
        return cached;
    }
}
