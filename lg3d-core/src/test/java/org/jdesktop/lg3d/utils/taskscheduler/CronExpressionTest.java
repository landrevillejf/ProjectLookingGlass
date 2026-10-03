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
package org.jdesktop.lg3d.utils.taskscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link CronExpression}: parsing, matching, the Vixie-cron
 * day rule, macros, next-fire-time arithmetic and rejection of bad input. All
 * times use a fixed UTC zone so the assertions are deterministic.
 */
class CronExpressionTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static ZonedDateTime at(int y, int mo, int d, int h, int mi) {
        return ZonedDateTime.of(y, mo, d, h, mi, 0, 0, UTC);
    }

    @Test
    @DisplayName("a five-field expression matches its minute and hour")
    void fiveFieldMatches() {
        CronExpression c = CronExpression.parse("30 2 * * *");
        assertFalse(c.hasSeconds());
        assertTrue(c.matches(at(2026, 3, 10, 2, 30)));
        assertFalse(c.matches(at(2026, 3, 10, 2, 31)));
        assertFalse(c.matches(at(2026, 3, 10, 3, 30)));
    }

    @Test
    @DisplayName("a six-field expression carries seconds resolution")
    void sixFieldHasSeconds() {
        CronExpression c = CronExpression.parse("15 30 2 * * *");
        assertTrue(c.hasSeconds());
        assertTrue(c.matches(at(2026, 3, 10, 2, 30).withSecond(15)));
        assertFalse(c.matches(at(2026, 3, 10, 2, 30).withSecond(16)));
    }

    @Test
    @DisplayName("steps, ranges and lists expand correctly")
    void stepsRangesLists() {
        CronExpression step = CronExpression.parse("*/15 * * * *");
        assertTrue(step.matches(at(2026, 1, 1, 0, 0)));
        assertTrue(step.matches(at(2026, 1, 1, 0, 45)));
        assertFalse(step.matches(at(2026, 1, 1, 0, 20)));

        CronExpression range = CronExpression.parse("0 9-17 * * *");
        assertTrue(range.matches(at(2026, 1, 1, 12, 0)));
        assertFalse(range.matches(at(2026, 1, 1, 18, 0)));

        CronExpression list = CronExpression.parse("0 0 1,15 * *");
        assertTrue(list.matches(at(2026, 1, 15, 0, 0)));
        assertFalse(list.matches(at(2026, 1, 2, 0, 0)));
    }

    @Test
    @DisplayName("month and day names are accepted")
    void namesAreAccepted() {
        CronExpression c = CronExpression.parse("0 0 * JAN MON");
        // 2026-01-05 is a Monday in January.
        assertTrue(c.matches(at(2026, 1, 5, 0, 0)));
    }

    @Test
    @DisplayName("day-of-week 7 is an alias for Sunday (0)")
    void sevenIsSunday() {
        CronExpression c = CronExpression.parse("0 0 * * 7");
        // 2026-01-04 is a Sunday.
        assertTrue(c.matches(at(2026, 1, 4, 0, 0)));
        assertFalse(c.matches(at(2026, 1, 5, 0, 0)));
    }

    @Test
    @DisplayName("macros expand to their field expressions")
    void macrosExpand() {
        assertEquals(CronExpression.parse("0 0 * * *"), CronExpression.parse("@daily"));
        assertEquals(CronExpression.parse("0 * * * *"), CronExpression.parse("@hourly"));
        assertEquals(CronExpression.parse("0 0 1 * *"), CronExpression.parse("@monthly"));
        assertEquals(CronExpression.parse("0 0 1 1 *"), CronExpression.parse("@yearly"));
    }

    @Test
    @DisplayName("@reboot has no wall-clock match or next time")
    void rebootSemantics() {
        CronExpression c = CronExpression.parse("@reboot");
        assertTrue(c.isReboot());
        assertFalse(c.matches(at(2026, 1, 1, 0, 0)));
        assertFalse(c.nextFireTime(at(2026, 1, 1, 0, 0)).isPresent());
    }

    @Test
    @DisplayName("when both day fields are restricted, either one fires (Vixie rule)")
    void vixieOrRule() {
        CronExpression c = CronExpression.parse("0 0 1 * 1");   // 1st of month OR Monday
        assertTrue(c.matches(at(2026, 1, 1, 0, 0)),  "the 1st (a Thursday) matches by day-of-month");
        assertTrue(c.matches(at(2026, 1, 5, 0, 0)),  "a Monday matches by day-of-week");
        assertFalse(c.matches(at(2026, 1, 6, 0, 0)), "neither the 1st nor a Monday");
    }

    @Test
    @DisplayName("nextFireTime returns the next daily midnight")
    void nextFireDaily() {
        CronExpression c = CronExpression.parse("0 0 * * *");
        Optional<ZonedDateTime> next = c.nextFireTime(at(2026, 1, 1, 10, 0));
        assertTrue(next.isPresent());
        assertEquals(at(2026, 1, 2, 0, 0), next.get());
    }

    @Test
    @DisplayName("nextFireTime is strictly after the reference instant")
    void nextFireIsStrict() {
        CronExpression c = CronExpression.parse("30 2 * * *");
        Optional<ZonedDateTime> next = c.nextFireTime(at(2026, 1, 1, 2, 30));
        assertTrue(next.isPresent());
        assertEquals(at(2026, 1, 2, 2, 30), next.get(), "the same instant is skipped");
    }

    @Test
    @DisplayName("an impossible date never fires")
    void impossibleDate() {
        CronExpression c = CronExpression.parse("0 0 30 2 *");   // 30 February
        assertFalse(c.nextFireTime(at(2026, 1, 1, 0, 0)).isPresent());
    }

    @Test
    @DisplayName("malformed expressions are rejected")
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse(null));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("1 2 3"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("60 * * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("* * * * * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("@nonsense"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("0 0 * * 9"));
        assertThrows(IllegalArgumentException.class, () -> CronExpression.parse("5-1 * * * *"));
    }

    @Test
    @DisplayName("expression text is preserved and equality is by text")
    void textAndEquality() {
        CronExpression c = CronExpression.parse("0 0 * * *");
        assertEquals("0 0 * * *", c.getExpression());
        assertEquals("0 0 * * *", c.toString());
        assertEquals(c.hashCode(), CronExpression.parse("0 0 * * *").hashCode());
    }
}
