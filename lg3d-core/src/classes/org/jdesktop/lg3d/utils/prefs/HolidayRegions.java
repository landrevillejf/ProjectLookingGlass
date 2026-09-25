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

import io.github.landrevillejf.jbusinessday.JBusinessDay;
import io.github.landrevillejf.jbusinessday.enums.AmericanState;
import io.github.landrevillejf.jbusinessday.enums.CanadianProvince;
import io.github.landrevillejf.jbusinessday.utils.canada.CanadianHolidayUtil;
import io.github.landrevillejf.jbusinessday.utils.usa.AmericanHolidayUtil;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * The single source of truth for turning a configured holiday-region token into
 * a statutory-holiday list, shared by every surface that marks holidays: the 2D
 * taskbar clock's calendar popup (via {@code HolidayCalendar}) and the orgchart
 * {@code AgendaGrid}. It lives in {@code lg3d-core} because the Gradle module
 * dependency direction (incubator -&gt; core) forbids the two callers from
 * sharing a helper placed in either app module.
 *
 * <p>The region is a small token grammar: {@code "AUTO"} (the
 * {@link DesktopConfig} default) derives the holiday set from the system
 * {@link Locale} — Canada maps to Canadian federal, everything else to US
 * federal — while an explicit token overrides it: {@code "US"} or {@code "CA"}
 * (federal), {@code "CA:<PROVINCE>"} (e.g. {@code "CA:QUEBEC"}) or
 * {@code "US:<STATE>"}. The Control Center's Desktop panel edits that token and
 * persists it, so both the calendar and the agenda reflect the user's choice
 * across restarts.</p>
 *
 * <p>Every method is static, pure and defensive: {@link #resolveRegion} and
 * {@link #holidays} are unit-testable headless, and a holiday/weekend lookup
 * never throws — if {@code jbusinessday} (or its slf4j dependency) is missing at
 * runtime, or a token names an unknown province/state, the day is simply treated
 * as an ordinary day rather than breaking the caller. Memoisation of each year's
 * list is left to the caller (the two callers keep their own caches).</p>
 */
public final class HolidayRegions {

    /** Token meaning "derive the region from the system locale". */
    public static final String AUTO = "AUTO";

    private HolidayRegions() {
        // Static utility; not instantiable.
    }

    /**
     * The effective region token for the current user: the token persisted in
     * {@link DesktopConfig}, resolved against the default locale.
     */
    public static String configuredRegion() {
        return resolveRegion(DesktopConfig.get().getHolidayRegion(),
                Locale.getDefault());
    }

    /**
     * Resolves a configured token to an effective one: a blank/{@code AUTO}
     * value falls back to the locale's country (Canada -&gt; {@code "CA"},
     * otherwise {@code "US"}); any explicit token is normalised (trimmed,
     * upper-cased) and returned as-is. Pure so the mapping is unit-testable.
     */
    public static String resolveRegion(String configured, Locale locale) {
        if (configured != null) {
            String t = configured.trim();
            if (!t.isEmpty() && !AUTO.equalsIgnoreCase(t)) {
                return t.toUpperCase(Locale.ROOT);
            }
        }
        String country = (locale == null) ? "" : nullToEmpty(locale.getCountry());
        return "CA".equalsIgnoreCase(country) ? "CA" : "US";
    }

    /**
     * The statutory holidays {@code token} selects for {@code year}. Pure and
     * defensive: an unknown token or a missing {@code jbusinessday}/slf4j runtime
     * yields an empty list rather than throwing, so the caller still renders.
     */
    public static List<LocalDate> holidays(String token, int year) {
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

    /**
     * True when {@code date} falls on a Saturday or Sunday. Defensive: falls back
     * to the ISO weekday if {@code jbusinessday} is unavailable at runtime.
     */
    public static boolean isWeekend(LocalDate date) {
        if (date == null) {
            return false;
        }
        try {
            return JBusinessDay.isWeekend(date);
        } catch (Throwable t) {
            int dow = date.getDayOfWeek().getValue();
            return dow == 6 || dow == 7;
        }
    }

    /** True when {@code date} is a statutory holiday in region {@code token}. */
    public static boolean isHoliday(String token, LocalDate date) {
        return date != null && holidays(token, date.getYear()).contains(date);
    }

    private static String nullToEmpty(String s) {
        return (s == null) ? "" : s;
    }
}
