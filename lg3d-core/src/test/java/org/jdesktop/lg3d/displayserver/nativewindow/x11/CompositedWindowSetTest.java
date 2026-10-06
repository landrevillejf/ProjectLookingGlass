/**
 * Project Looking Glass
 *
 * Copyright (c) 2026 Project Looking Glass contributors.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.jdesktop.lg3d.displayserver.nativewindow.x11.CompositedWindowSet.FocusPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link CompositedWindowSet} — the pure multi-window focus
 * and stacking model (Phase C). It holds no X or Java 3D state, so every
 * add/remove/raise/lower/activate/pointer policy decision is pinned directly.
 */
class CompositedWindowSetTest {

    private static CompositedWindowSet pointer() {
        return new CompositedWindowSet(FocusPolicy.POINTER);
    }

    private static CompositedWindowSet click() {
        return new CompositedWindowSet(FocusPolicy.CLICK);
    }

    @Test
    @DisplayName("a null policy defaults to POINTER")
    void nullPolicyDefaultsToPointer() {
        assertEquals(FocusPolicy.POINTER, new CompositedWindowSet(null).getFocusPolicy());
    }

    @Test
    @DisplayName("each add goes to the top of the stack and takes focus")
    void addStacksAndFocuses() {
        CompositedWindowSet set = pointer();
        set.add(1, "A");
        set.add(2, "B");
        set.add(3, "C");

        assertEquals(3, set.size());
        assertEquals(Arrays.asList(3, 2, 1), set.stackTopDown());
        assertEquals(Integer.valueOf(3), set.focused());
        assertTrue(set.contains(2));
        assertEquals("B", set.titleOf(2));
    }

    @Test
    @DisplayName("re-adding an existing window raises it and refreshes its title")
    void reAddRaisesAndRetitles() {
        CompositedWindowSet set = pointer();
        set.add(1, "A");
        set.add(2, "B");
        set.add(3, "C");

        set.add(1, "A2");

        assertEquals(3, set.size());                       // not duplicated
        assertEquals(Arrays.asList(1, 3, 2), set.stackTopDown());
        assertEquals("A2", set.titleOf(1));
        assertEquals(Integer.valueOf(1), set.focused());
    }

    @Test
    @DisplayName("removing the focused window moves focus to the new top")
    void removeFocusedMovesToNextTop() {
        CompositedWindowSet set = pointer();
        set.add(1, "A");
        set.add(2, "B");
        set.add(3, "C");

        set.remove(3);

        assertEquals(Integer.valueOf(2), set.focused());
        assertEquals(Arrays.asList(2, 1), set.stackTopDown());
        assertFalse(set.contains(3));
    }

    @Test
    @DisplayName("removing a non-focused window leaves the focus untouched")
    void removeNonFocusedKeepsFocus() {
        CompositedWindowSet set = pointer();
        set.add(1, "A");
        set.add(2, "B");
        set.add(3, "C");

        set.remove(1);

        assertEquals(Integer.valueOf(3), set.focused());
        assertEquals(Arrays.asList(3, 2), set.stackTopDown());
    }

    @Test
    @DisplayName("removing the last window clears the focus")
    void removeLastClearsFocus() {
        CompositedWindowSet set = pointer();
        set.add(1, "A");
        set.remove(1);
        assertNull(set.focused());
        assertEquals(0, set.size());
    }

    @Test
    @DisplayName("raise moves a window to the top; lower moves it to the bottom")
    void raiseAndLower() {
        CompositedWindowSet set = pointer();
        set.add(1, "A");
        set.add(2, "B");
        set.add(3, "C"); // stack top-down: 3,2,1

        set.raise(1);
        assertEquals(Arrays.asList(1, 3, 2), set.stackTopDown());

        set.lower(1);
        assertEquals(Arrays.asList(3, 2, 1), set.stackTopDown());

        // raise/lower of an unknown window is a no-op.
        set.raise(999);
        set.lower(999);
        assertEquals(Arrays.asList(3, 2, 1), set.stackTopDown());
    }

    @Test
    @DisplayName("activate raises and focuses under either policy")
    void activateRaisesAndFocuses() {
        CompositedWindowSet set = click();
        set.add(1, "A");
        set.add(2, "B");
        set.add(3, "C"); // focus 3

        set.activate(1);

        assertEquals(Integer.valueOf(1), set.focused());
        assertEquals(Arrays.asList(1, 3, 2), set.stackTopDown());
        // activate of an unknown window is a no-op.
        set.activate(999);
        assertEquals(Integer.valueOf(1), set.focused());
    }

    @Test
    @DisplayName("pointerEnter focuses under POINTER but is ignored under CLICK")
    void pointerEnterRespectsPolicy() {
        CompositedWindowSet p = pointer();
        p.add(1, "A");
        p.add(2, "B"); // focus 2
        p.pointerEnter(1);
        assertEquals(Integer.valueOf(1), p.focused());
        // Focus-follows-pointer does NOT restack.
        assertEquals(Arrays.asList(2, 1), p.stackTopDown());

        CompositedWindowSet c = click();
        c.add(1, "A");
        c.add(2, "B"); // focus 2
        c.pointerEnter(1);
        assertEquals(Integer.valueOf(2), c.focused()); // unchanged
    }

    @Test
    @DisplayName("retitle updates the title only for a known window")
    void retitleKnownOnly() {
        CompositedWindowSet set = pointer();
        set.add(1, "A");
        set.retitle(1, "A2");
        assertEquals("A2", set.titleOf(1));
        set.retitle(999, "nope"); // no-op, no throw
        assertFalse(set.contains(999));
    }

    @Test
    @DisplayName("clear empties the set and the focus")
    void clearEmpties() {
        CompositedWindowSet set = pointer();
        set.add(1, "A");
        set.add(2, "B");
        set.clear();
        assertEquals(0, set.size());
        assertNull(set.focused());
        assertTrue(set.stackTopDown().isEmpty());
    }

    @Test
    @DisplayName("stackTopDown and windowsBottomUp are unmodifiable views")
    void viewsAreUnmodifiable() {
        CompositedWindowSet set = pointer();
        set.add(1, "A");
        List<Integer> top = set.stackTopDown();
        assertThrows(UnsupportedOperationException.class, () -> top.add(9));
        assertThrows(UnsupportedOperationException.class,
            () -> set.windowsBottomUp().put(9, "X"));
    }
}
