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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link WindowCycler}: MRU ordering (tracked most-recent-first, then
 * untracked appended), filtering of windows that are no longer present, the
 * fewer-than-two guard on {@link WindowCycler#open}, the forward/backward
 * highlight walk with wrap-around, and that commit/cancel close the session.
 * Runs headless: the cycler is pure logic over opaque window handles.
 */
class WindowCyclerTest {

    private static Desktop2DWindow window(String name) {
        return new Desktop2DWindow(name, null, new JPanel(), name);
    }

    @Test
    @DisplayName("touch orders the tracked windows most-recent-first")
    void touchOrdersMostRecentFirst() {
        WindowCycler cycler = new WindowCycler();
        Desktop2DWindow a = window("a");
        Desktop2DWindow b = window("b");
        Desktop2DWindow c = window("c");
        cycler.touch(a);
        cycler.touch(b);
        cycler.touch(c);
        assertEquals(List.of(c, b, a), cycler.ordered(List.of(a, b, c)));
    }

    @Test
    @DisplayName("re-touching a window promotes it to the front")
    void retouchPromotes() {
        WindowCycler cycler = new WindowCycler();
        Desktop2DWindow a = window("a");
        Desktop2DWindow b = window("b");
        Desktop2DWindow c = window("c");
        cycler.touch(a);
        cycler.touch(b);
        cycler.touch(c);
        cycler.touch(a);
        assertEquals(List.of(a, c, b), cycler.ordered(List.of(a, b, c)));
    }

    @Test
    @DisplayName("untracked present windows are appended in the given order")
    void untrackedAppended() {
        WindowCycler cycler = new WindowCycler();
        Desktop2DWindow a = window("a");
        Desktop2DWindow b = window("b");
        Desktop2DWindow c = window("c");
        cycler.touch(b);
        assertEquals(List.of(b, a, c), cycler.ordered(List.of(a, b, c)));
    }

    @Test
    @DisplayName("windows no longer present are filtered out")
    void absentFiltered() {
        WindowCycler cycler = new WindowCycler();
        Desktop2DWindow a = window("a");
        Desktop2DWindow b = window("b");
        Desktop2DWindow c = window("c");
        cycler.touch(a);
        cycler.touch(b);
        cycler.touch(c);
        assertEquals(List.of(c, a), cycler.ordered(List.of(a, c)));
    }

    @Test
    @DisplayName("a tracked window that is not present is ignored")
    void staleTrackedIgnored() {
        WindowCycler cycler = new WindowCycler();
        Desktop2DWindow gone = window("gone");
        Desktop2DWindow a = window("a");
        cycler.touch(gone);
        assertEquals(List.of(a), cycler.ordered(List.of(a)));
    }

    @Test
    @DisplayName("forget drops a window from the MRU history")
    void forgetDrops() {
        WindowCycler cycler = new WindowCycler();
        Desktop2DWindow a = window("a");
        Desktop2DWindow b = window("b");
        cycler.touch(a);
        cycler.touch(b);
        cycler.forget(b);
        // b is no longer tracked, so it is appended after the tracked a.
        assertEquals(List.of(a, b), cycler.ordered(List.of(a, b)));
    }

    @Test
    @DisplayName("null windows and null lists are tolerated")
    void nullSafe() {
        WindowCycler cycler = new WindowCycler();
        cycler.touch(null);
        cycler.forget(null);
        assertTrue(cycler.ordered(null).isEmpty());
    }

    @Test
    @DisplayName("open refuses when there are fewer than two windows")
    void openNeedsTwo() {
        WindowCycler cycler = new WindowCycler();
        assertFalse(cycler.open(List.of()));
        assertFalse(cycler.isActive());
        assertFalse(cycler.open(List.of(window("solo"))));
        assertFalse(cycler.isActive());
        assertNull(cycler.selected());
    }

    @Test
    @DisplayName("open activates and highlights the current window (index 0)")
    void openHighlightsCurrent() {
        WindowCycler cycler = new WindowCycler();
        Desktop2DWindow a = window("a");
        Desktop2DWindow b = window("b");
        assertTrue(cycler.open(List.of(a, b)));
        assertTrue(cycler.isActive());
        assertEquals(0, cycler.selectedIndex());
        assertEquals(List.of(a, b), cycler.items());
        assertSame(a, cycler.selected());
    }

    @Test
    @DisplayName("advance walks forward and wraps")
    void advanceWraps() {
        WindowCycler cycler = new WindowCycler();
        Desktop2DWindow a = window("a");
        Desktop2DWindow b = window("b");
        Desktop2DWindow c = window("c");
        cycler.open(List.of(a, b, c));
        cycler.advance();
        assertEquals(1, cycler.selectedIndex());
        cycler.advance();
        assertEquals(2, cycler.selectedIndex());
        cycler.advance();
        assertEquals(0, cycler.selectedIndex());
    }

    @Test
    @DisplayName("advanceBack walks backward and wraps without going negative")
    void advanceBackWraps() {
        WindowCycler cycler = new WindowCycler();
        Desktop2DWindow a = window("a");
        Desktop2DWindow b = window("b");
        Desktop2DWindow c = window("c");
        cycler.open(List.of(a, b, c));
        cycler.advanceBack();
        assertEquals(2, cycler.selectedIndex());
        cycler.advanceBack();
        assertEquals(1, cycler.selectedIndex());
    }

    @Test
    @DisplayName("advance and advanceBack are no-ops while inactive")
    void advanceNoOpWhenInactive() {
        WindowCycler cycler = new WindowCycler();
        cycler.advance();
        cycler.advanceBack();
        assertFalse(cycler.isActive());
        assertEquals(-1, cycler.selectedIndex());
    }

    @Test
    @DisplayName("commit returns the highlighted window and closes the session")
    void commitReturnsAndCloses() {
        WindowCycler cycler = new WindowCycler();
        Desktop2DWindow a = window("a");
        Desktop2DWindow b = window("b");
        cycler.open(List.of(a, b));
        cycler.advance();
        assertSame(b, cycler.commit());
        assertFalse(cycler.isActive());
        assertTrue(cycler.items().isEmpty());
    }

    @Test
    @DisplayName("commit while inactive returns null")
    void commitInactiveNull() {
        WindowCycler cycler = new WindowCycler();
        assertNull(cycler.commit());
    }

    @Test
    @DisplayName("cancel closes the session without selecting anything")
    void cancelCloses() {
        WindowCycler cycler = new WindowCycler();
        Desktop2DWindow a = window("a");
        Desktop2DWindow b = window("b");
        cycler.open(List.of(a, b));
        cycler.cancel();
        assertFalse(cycler.isActive());
        assertTrue(cycler.items().isEmpty());
        assertNull(cycler.selected());
    }
}
