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

import io.github.landrevillejf.jbusinessday.JBusinessDay;
import io.github.landrevillejf.jbusinessday.enums.AmericanState;
import io.github.landrevillejf.jbusinessday.enums.CanadianProvince;
import io.github.landrevillejf.jbusinessday.utils.canada.CanadianHolidayUtil;
import io.github.landrevillejf.jbusinessday.utils.usa.AmericanHolidayUtil;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;

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
 * <p>{@link #resolveRegion} and {@link #computeHolidays} are static and pure so
 * the region mapping is unit-testable headless; {@link #holidays} memoises each
 * year's list. A holiday lookup never throws: if {@code jbusinessday} (or its
 * slf4j dependency) is missing at runtime, or a token names an unknown
 * province/state, the day is simply treated as an ordinary day rather than
 * breaking the calendar.</p>
 */
final class HolidayCalendar {

    /** Token meaning "derive the region from the system locale". */
    static final String AUTO = "AUTO";

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
        this.region = resolveRegion(configuredRegion, locale);
    }

    /** The effective region token this calendar classifies against. */
    String region() {
        return region;
    }

    /**
     * Resolves a configured token to an effective one: a blank/{@code AUTO}
     * value falls back to the locale's country (Canada -> {@code "CA"}, otherwise
     * {@code "US"}); any explicit token is normalised (trimmed, upper-cased) and
     * returned as-is. Pure so the mapping is unit-testable.
     */
    static String resolveRegion(String configured, Locale locale) {
        if (configured != null) {
            String t = configured.trim();
            if (!t.isEmpty() && !AUTO.equalsIgnoreCase(t)) {
                return t.toUpperCase(Locale.ROOT);
            }
        }
        String country = (locale == null) ? "" : nullToEmpty(locale.getCountry());
        return "CA".equalsIgnoreCase(country) ? "CA" : "US";
    }

    /** True when {@code date} is a statutory holiday in this calendar's region. */
    boolean isHoliday(LocalDate date) {
        return date != null && holidays(date.getYear()).contains(date);
    }

    /** True when {@code date} falls on a Saturday or Sunday. */
    boolean isWeekend(LocalDate date) {
        if (date == null) {
            return false;
        }
        try {
            return JBusinessDay.isWeekend(date);
        } catch (Throwable t) {
            // Fall back to the ISO weekday if jbusinessday is unavailable.
            int dow = date.getDayOfWeek().getValue();
            return dow == 6 || dow == 7;
        }
    }

    /** The holiday list for {@code year} in this calendar's region, memoised. */
    List<LocalDate> holidays(int year) {
        List<LocalDate> cached = cache.get(year);
        if (cached == null) {
            cached = computeHolidays(region, year);
            cache.put(year, cached);
        }
        return cached;
    }

    /**
     * The statutory holidays {@code token} selects for {@code year}. Pure and
     * defensive: an unknown token or a missing {@code jbusinessday}/slf4j runtime
     * yields an empty list rather than throwing, so the calendar still renders.
     */
    static List<LocalDate> computeHolidays(String token, int year) {
        String t = (token == null) ? "" : token.trim().toUpperCase(Locale.ROOT);
        try {
            if (t.startsWith("CA:")) {
                CanadianProvince province = CanadianProvince.valueOf(t.substring(3));
                return CanadianHolidayUtil.getCanadianHolidays(year, province, true);
            }
            if (t.startsWith("US:")) {
                AmericanState state = AmericanState.valueOf(t.substring(3));
                return AmericanHolidayUtil.getHolidaysWithStateObservance(year, state);
            }
            if (t.startsWith("CA")) {
                return CanadianHolidayUtil.getCanadianFederalHolidays(year);
            }
            // "US", "AUTO" fallback and anything unrecognised: US federal.
            return AmericanHolidayUtil.getFederalHolidays(year);
        } catch (Throwable t2) {
            return List.of();
        }
    }

    private static String nullToEmpty(String s) {
        return (s == null) ? "" : s;
    }
}
