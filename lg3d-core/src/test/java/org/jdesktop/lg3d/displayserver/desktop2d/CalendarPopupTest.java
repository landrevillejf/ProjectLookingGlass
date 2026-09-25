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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Component;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
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

    @Test
    @DisplayName("prev/next are menu items whose action navigates the month")
    void navMenuItemsDriveMonth() {
        CalendarPopup popup = newPopup();
        JMenuItem next = findNavItem(popup.menu(), "next");
        assertNotNull(next, "next is a JMenuItem, which a popup dispatches real clicks to");
        assertNotNull(findNavItem(popup.menu(), "prev"));
        // Drive the wired action the way a real selection does (null anchor, so
        // the re-show is skipped and this stays headless).
        next.getActionListeners()[0].actionPerformed(null);
        assertEquals(YearMonth.of(2026, 4), popup.viewMonth());
        // rebuild() replaces the items, so re-find before each navigation
        findNavItem(popup.menu(), "prev").getActionListeners()[0].actionPerformed(null);
        findNavItem(popup.menu(), "prev").getActionListeners()[0].actionPerformed(null);
        assertEquals(YearMonth.of(2026, 2), popup.viewMonth());
    }

    @Test
    @DisplayName("the grid tints a weekend day differently from an ordinary weekday")
    void gridTintsWeekend() {
        CalendarPopup popup = newPopup();
        // menu layout: prev(0), title(1), next(2), separator(3), grid(4), ...
        JPanel grid = (JPanel) popup.menu().getComponent(4);
        Color saturday = foregroundOf(grid, "14"); // 2026-03-14 is a Saturday
        Color weekday = foregroundOf(grid, "17");  // 2026-03-17 is an ordinary Tuesday
        assertNotNull(saturday);
        assertNotNull(weekday);
        assertNotEquals(weekday, saturday,
                "the weekend day is tinted while an ordinary weekday is not");
    }

    @Test
    @DisplayName("double-clicking a day cell asks to open that date")
    void doubleClickOpensDate() {
        AtomicReference<LocalDate> opened = new AtomicReference<>();
        CalendarPopup popup = new CalendarPopup(null, null, fixedClock());
        popup.setOnOpenDate(opened::set);
        popup.rebuild();
        JLabel cell = cellForDay(popup, "20"); // 2026-03-20, an ordinary Friday
        assertNotNull(cell);
        // A double-click reaches the cell as a mouseClicked with clickCount 2.
        fireClick(cell, 2);
        assertEquals(LocalDate.of(2026, 3, 20), opened.get());
    }

    @Test
    @DisplayName("a single click on a day cell does not open the agenda")
    void singleClickDoesNotOpenDate() {
        AtomicReference<LocalDate> opened = new AtomicReference<>();
        CalendarPopup popup = new CalendarPopup(null, null, fixedClock());
        popup.setOnOpenDate(opened::set);
        popup.rebuild();
        JLabel cell = cellForDay(popup, "20");
        assertNotNull(cell);
        fireClick(cell, 1);
        assertEquals(null, opened.get(), "only a double-click opens the date");
    }

    @Test
    @DisplayName("a padded (neighbouring-month) cell is not clickable")
    void paddedCellIsNotClickable() {
        CalendarPopup popup = newPopup();
        // 2026-03-01 is a Sunday, so the grid's first week row opens with
        // padding cells for the previous month; those carry no text and, being
        // out of month, no double-click listener.
        JPanel grid = (JPanel) popup.menu().getComponent(4);
        JLabel padded = null;
        for (int i = 7; i < 14; i++) { // 0-6 are the weekday headers
            JLabel l = (JLabel) grid.getComponent(i);
            if (l.getText().isEmpty()) {
                padded = l;
                break;
            }
        }
        assertNotNull(padded, "the March 2026 grid pads its first week");
        assertEquals(0, padded.getMouseListeners().length,
                "a padded cell carries no double-click listener");
    }

    private static JLabel cellForDay(CalendarPopup popup, String dayText) {
        JPanel grid = (JPanel) popup.menu().getComponent(4);
        for (Component c : grid.getComponents()) {
            if (c instanceof JLabel l && dayText.equals(l.getText())) {
                return l;
            }
        }
        return null;
    }

    /** Dispatches a mouseClicked of the given click count to the cell's listeners. */
    private static void fireClick(JLabel cell, int clickCount) {
        MouseEvent e = new MouseEvent(cell, MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, 0, 0, clickCount, false);
        for (MouseListener l : cell.getMouseListeners()) {
            l.mouseClicked(e);
        }
    }

    private static JMenuItem findNavItem(JPopupMenu menu, String name) {
        for (Component c : menu.getComponents()) {
            if (c instanceof JMenuItem mi && name.equals(mi.getName())) {
                return mi;
            }
        }
        return null;
    }

    private static Color foregroundOf(JPanel grid, String dayText) {
        for (Component c : grid.getComponents()) {
            if (c instanceof JLabel l && dayText.equals(l.getText())) {
                return l.getForeground();
            }
        }
        return null;
    }
}
