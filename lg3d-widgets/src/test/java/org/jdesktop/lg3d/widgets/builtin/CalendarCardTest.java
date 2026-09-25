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
package org.jdesktop.lg3d.widgets.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.jdesktop.lg3d.displayserver.desktop2d.CalendarModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link CalendarCard}'s pure geometry and interaction:
 * the pixel-to-date and pixel-to-nav hit-tests (derived from the same
 * {@link CalendarModel} grid the painter uses, so they never hardcode a
 * weekday), month navigation, and the day-double-click {@code onOpenDate}
 * hand-off. A fixed clock pins "today" to 2026-09-25 so the grid is
 * deterministic; nothing here touches Java 3D, a {@code SwingNode} or a
 * display.
 */
class CalendarCardTest {

    /** 2026-09-25T12:00Z: September 1 2026 is a Tuesday, the 25th a Friday. */
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneOffset.UTC);
    private static final YearMonth SEP_2026 = YearMonth.of(2026, 9);

    private static final int W = CalendarCard.WIDTH;
    private static final int H = CalendarCard.HEIGHT;

    private static CalendarCard newCard() {
        CalendarCard card = new CalendarCard(FIXED);
        card.setBounds(0, 0, W, H);
        return card;
    }

    /** Centre pixel of grid cell {@code (row, col)} for a content-top of {@code top}. */
    private static int[] cellCentre(int row, int col, int top) {
        int gt = CalendarCard.gridTop(top);
        int gb = CalendarCard.gridBottom(H);
        double colW = (W - 2 * CalendarCard.PAD_X) / (double) CalendarModel.DAYS;
        double rowH = (gb - gt) / (double) CalendarModel.WEEKS;
        int cx = CalendarCard.PAD_X + (int) (col * colW + colW / 2);
        int cy = gt + (int) (row * rowH + rowH / 2);
        return new int[] { cx, cy };
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the card reports its id, tick period and default size")
    void identity() {
        CalendarCard card = newCard();
        assertEquals("calendar", card.id());
        assertEquals(CalendarCard.ID, card.id());
        assertEquals(60_000L, card.tickPeriodMillis());
        assertEquals(W, card.getPreferredSize().width);
        assertEquals(H, card.getPreferredSize().height);
        assertEquals(SEP_2026, card.viewMonth(), "opens on the fixed clock's month");
    }

    @Test
    @DisplayName("tick() refreshes today headless without throwing")
    void tickIsHeadlessSafe() {
        CalendarCard card = newCard();
        card.attach(null, null, null);
        card.tick();
        card.start();
        card.stop();
        assertTrue(true);
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("prev/next month and the wheel step the displayed month")
    void monthNavigation() {
        CalendarCard card = newCard();
        card.prevMonth();
        assertEquals(SEP_2026.minusMonths(1), card.viewMonth());
        card.nextMonth();
        assertEquals(SEP_2026, card.viewMonth());
        card.onWheel(1);              // wheel down -> later month
        assertEquals(SEP_2026.plusMonths(1), card.viewMonth());
        card.onWheel(-1);             // wheel up -> earlier month
        assertEquals(SEP_2026, card.viewMonth());
    }

    @Test
    @DisplayName("gridTop/gridBottom bracket the six-week grid inside the card")
    void gridBounds() {
        int top = 22;
        assertEquals(top + CalendarCard.NAV_H + CalendarCard.HEAD_H, CalendarCard.gridTop(top));
        assertEquals(H - CalendarCard.BOTTOM_PAD, CalendarCard.gridBottom(H));
        assertTrue(CalendarCard.gridBottom(H) > CalendarCard.gridTop(top));
    }

    @Test
    @DisplayName("navAt resolves the prev/next arrows and nothing elsewhere")
    void navHitTest() {
        int top = 22;
        int midY = top + CalendarCard.NAV_H / 2;
        assertEquals(CalendarCard.NAV_PREV,
                CalendarCard.navAt(CalendarCard.PAD_X + 4, midY, W, top));
        assertEquals(CalendarCard.NAV_NEXT,
                CalendarCard.navAt(W - CalendarCard.PAD_X - 4, midY, W, top));
        assertEquals(CalendarCard.NAV_NONE, CalendarCard.navAt(W / 2, midY, W, top),
                "the title between the arrows is not a nav zone");
        assertEquals(CalendarCard.NAV_NONE, CalendarCard.navAt(CalendarCard.PAD_X + 4, top - 1, W, top),
                "above the nav row");
        assertEquals(CalendarCard.NAV_NONE,
                CalendarCard.navAt(CalendarCard.PAD_X + 4, top + CalendarCard.NAV_H, W, top),
                "below the nav row");
    }

    // ------------------------------------------------------------------
    // Day hit-testing (derived from the CalendarModel grid)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("dayAt maps every in-month cell centre to its date, padding to null")
    void dayHitTestMatchesModel() {
        int top = 22;
        int[][] weeks = CalendarModel.weeksOfMonth(SEP_2026);
        boolean sawInMonth = false;
        boolean sawPadding = false;
        for (int row = 0; row < CalendarModel.WEEKS; row++) {
            for (int col = 0; col < CalendarModel.DAYS; col++) {
                int[] c = cellCentre(row, col, top);
                LocalDate hit = CalendarCard.dayAt(c[0], c[1], W, H, top, SEP_2026);
                if (weeks[row][col] == CalendarModel.OUT_OF_MONTH) {
                    assertNull(hit, "padding cell (" + row + "," + col + ") must not resolve");
                    sawPadding = true;
                } else {
                    assertEquals(CalendarModel.cellDate(SEP_2026, row, col), hit,
                            "cell (" + row + "," + col + ")");
                    sawInMonth = true;
                }
            }
        }
        assertTrue(sawInMonth, "September 2026 has in-month cells");
        assertTrue(sawPadding, "the Monday-first grid pads September 2026");
    }

    @Test
    @DisplayName("dayAt returns null off the grid and for a null month")
    void dayHitTestEdges() {
        int top = 22;
        assertNull(CalendarCard.dayAt(0, 0, W, H, top, SEP_2026), "the top-left corner is off-grid");
        assertNull(CalendarCard.dayAt(W - 1, H - 1, W, H, top, SEP_2026), "the bottom-right is off-grid");
        assertNull(CalendarCard.dayAt(W / 2, top + 2, W, H, top, SEP_2026), "the nav row is not a day");
        assertNull(CalendarCard.dayAt(W / 2, H / 2, W, H, top, null), "a null month resolves to nothing");
        // Degenerate geometry (grid collapsed) must not divide by zero or throw.
        assertNull(CalendarCard.dayAt(5, 5, W, CalendarCard.gridTop(top), top, SEP_2026));
    }

    @Test
    @DisplayName("the today cell resolves to the fixed clock's date")
    void todayCellResolves() {
        int top = 22;
        LocalDate today = CalendarModel.today(FIXED);
        assertEquals(LocalDate.of(2026, 9, 25), today);
        // Sept 25 sits at grid index 25 from the Monday on/before Sept 1.
        int[] c = cellCentre(3, 4, top);
        assertEquals(today, CalendarCard.dayAt(c[0], c[1], W, H, top, SEP_2026));
    }

    // ------------------------------------------------------------------
    // Click routing (the seam the SwingNode mouse listener drives)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a double-click on a day cell fires onOpenDate with that date")
    void doubleClickOpensDate() {
        CalendarCard card = newCard();
        AtomicReference<LocalDate> opened = new AtomicReference<>();
        card.setOnOpenDate(opened::set);

        int top = card.bodyTop();
        int[][] weeks = CalendarModel.weeksOfMonth(card.viewMonth());
        int row = -1;
        int col = -1;
        outer:
        for (int r = 0; r < CalendarModel.WEEKS; r++) {
            for (int c = 0; c < CalendarModel.DAYS; c++) {
                if (weeks[r][c] != CalendarModel.OUT_OF_MONTH) {
                    row = r;
                    col = c;
                    break outer;
                }
            }
        }
        assertTrue(row >= 0, "expected at least one in-month cell");
        int[] centre = cellCentre(row, col, top);
        card.handleClick(centre[0], centre[1], 2);
        assertEquals(CalendarModel.cellDate(card.viewMonth(), row, col), opened.get());
    }

    @Test
    @DisplayName("a double-click on a padding cell opens nothing")
    void doubleClickOnPaddingIsIgnored() {
        CalendarCard card = newCard();
        AtomicReference<LocalDate> opened = new AtomicReference<>();
        card.setOnOpenDate(opened::set);

        int top = card.bodyTop();
        int[][] weeks = CalendarModel.weeksOfMonth(card.viewMonth());
        for (int r = 0; r < CalendarModel.WEEKS; r++) {
            for (int c = 0; c < CalendarModel.DAYS; c++) {
                if (weeks[r][c] == CalendarModel.OUT_OF_MONTH) {
                    int[] centre = cellCentre(r, c, top);
                    card.handleClick(centre[0], centre[1], 2);
                    assertNull(opened.get(), "padding cell (" + r + "," + c + ") must not open");
                    return;
                }
            }
        }
        assertNull(opened.get());
    }

    @Test
    @DisplayName("a single click on a nav arrow steps the month")
    void singleClickNavigates() {
        CalendarCard card = newCard();
        YearMonth start = card.viewMonth();
        int top = card.bodyTop();
        int midY = top + CalendarCard.NAV_H / 2;
        card.handleClick(CalendarCard.PAD_X + 4, midY, 1);       // prev arrow
        assertEquals(start.minusMonths(1), card.viewMonth());
        card.handleClick(W - CalendarCard.PAD_X - 4, midY, 1);   // next arrow
        assertEquals(start, card.viewMonth());
    }

    @Test
    @DisplayName("a double-click with no callback wired is a safe no-op")
    void doubleClickWithoutCallbackIsSafe() {
        CalendarCard card = newCard();
        card.setOnOpenDate(null);
        int[] centre = cellCentre(3, 4, card.bodyTop());
        card.handleClick(centre[0], centre[1], 2);   // must not throw
        assertNotNull(card.viewMonth());
    }
}
