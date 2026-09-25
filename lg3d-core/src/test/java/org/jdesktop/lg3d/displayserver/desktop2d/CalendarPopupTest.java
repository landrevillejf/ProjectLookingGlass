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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link CalendarPopup}'s headless behaviour: with a null anchor and a
 * fixed clock it builds and rebuilds the month grid without a display, month
 * navigation moves the view, and the pure {@link CalendarPopup#agendaFor} filter
 * keeps only the notifications raised on the given day.
 */
class CalendarPopupTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    private static Clock fixedClock() {
        return Clock.fixed(TODAY.atStartOfDay(ZONE).toInstant(), ZONE);
    }

    private static long millisOn(LocalDate date, int hourUtc) {
        return date.atStartOfDay(ZONE).plusHours(hourUtc).toInstant().toEpochMilli();
    }

    private static CalendarPopup newPopup() {
        return new CalendarPopup(null, null, fixedClock());
    }

    @Test
    @DisplayName("a new popup opens on the current month and has content")
    void opensOnCurrentMonth() {
        CalendarPopup popup = newPopup();
        assertEquals(YearMonth.of(2026, 3), popup.viewMonth());
        assertNotNull(popup.menu());
        assertTrue(popup.menu().getComponentCount() > 0, "header + grid + agenda");
    }

    @Test
    @DisplayName("next and prev move the viewed month and rebuild")
    void monthNavigation() {
        CalendarPopup popup = newPopup();
        popup.next();
        assertEquals(YearMonth.of(2026, 4), popup.viewMonth());
        popup.prev();
        popup.prev();
        assertEquals(YearMonth.of(2026, 2), popup.viewMonth());
        assertTrue(popup.menu().getComponentCount() > 0, "rebuilt without error");
    }

    @Test
    @DisplayName("navigation rolls across a year boundary")
    void navigationCrossesYear() {
        CalendarPopup popup = newPopup();
        popup.prev();
        popup.prev();
        popup.prev();
        assertEquals(YearMonth.of(2025, 12), popup.viewMonth());
    }

    @Test
    @DisplayName("rebuild is idempotent and safe to call directly")
    void rebuildIsSafe() {
        CalendarPopup popup = newPopup();
        int before = popup.menu().getComponentCount();
        popup.rebuild();
        assertEquals(before, popup.menu().getComponentCount());
    }

    @Test
    @DisplayName("agendaFor keeps only the notifications raised on the day")
    void agendaFiltersByDay() {
        Notification onDay1 = new Notification(1, "Today one", null,
                Notification.Kind.INFO, millisOn(TODAY, 9));
        Notification onDay2 = new Notification(2, "Today two", "body",
                Notification.Kind.WARNING, millisOn(TODAY, 17));
        Notification otherDay = new Notification(3, "Yesterday", null,
                Notification.Kind.INFO, millisOn(TODAY.minusDays(1), 23));

        List<Notification> todays = CalendarPopup.agendaFor(
                List.of(otherDay, onDay1, onDay2), TODAY, ZONE);

        assertEquals(2, todays.size());
        assertTrue(todays.contains(onDay1));
        assertTrue(todays.contains(onDay2));
        assertTrue(!todays.contains(otherDay), "the other day is excluded");
    }

    @Test
    @DisplayName("agendaFor tolerates null/empty inputs")
    void agendaToleratesNulls() {
        assertTrue(CalendarPopup.agendaFor(null, TODAY, ZONE).isEmpty());
        assertTrue(CalendarPopup.agendaFor(List.of(), TODAY, ZONE).isEmpty());
        Notification n = new Notification(1, "x", null,
                Notification.Kind.INFO, millisOn(TODAY, 9));
        assertTrue(CalendarPopup.agendaFor(List.of(n), null, ZONE).isEmpty());
        assertTrue(CalendarPopup.agendaFor(List.of(n), TODAY, null).isEmpty());
    }
}
