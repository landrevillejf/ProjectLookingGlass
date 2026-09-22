/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.wg.switcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SwitcherController}'s cycle state machine: opening on the first
 * advance, forward/backward stepping with wrap, commit-activates-and-closes,
 * cancel-closes-without-activating, and the "fewer than two windows" no-op.
 * Pure logic; runs headless.
 */
class SwitcherControllerTest {

    /** Minimal {@link SwitcherModel} with a fixed item list and an activation log. */
    private static final class FakeModel implements SwitcherModel {
        private final List<SwitcherItem> items;
        SwitcherItem activated;
        int activateCount;

        FakeModel(List<SwitcherItem> items) {
            this.items = items;
        }

        @Override
        public List<SwitcherItem> items() {
            return items;
        }

        @Override
        public void activate(SwitcherItem item) {
            activated = item;
            activateCount++;
        }

        @Override
        public String triggerKeySpec() {
            return "control alt TAB";
        }
    }

    private static List<SwitcherItem> items(int n) {
        List<SwitcherItem> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new SwitcherItem(new Object(), "win" + i, null));
        }
        return list;
    }

    @Test
    @DisplayName("a fresh controller is inactive with no selection")
    void freshState() {
        SwitcherController c = new SwitcherController(new FakeModel(items(3)));
        assertFalse(c.isActive());
        assertEquals(-1, c.getIndex());
        assertNull(c.currentItem());
        assertTrue(c.items().isEmpty());
    }

    @Test
    @DisplayName("advance opens on index 1 (the window Alt+Tab jumps to first)")
    void advanceOpensOnNext() {
        List<SwitcherItem> model = items(3);
        SwitcherController c = new SwitcherController(new FakeModel(model));
        assertTrue(c.advance());
        assertTrue(c.isActive());
        assertEquals(1, c.getIndex());
        assertSame(model.get(1), c.currentItem());
        assertEquals(3, c.items().size());
    }

    @Test
    @DisplayName("repeated advance steps forward and wraps")
    void advanceWraps() {
        SwitcherController c = new SwitcherController(new FakeModel(items(3)));
        c.advance();
        assertEquals(1, c.getIndex());
        c.advance();
        assertEquals(2, c.getIndex());
        c.advance();
        assertEquals(0, c.getIndex(), "wraps back to the start");
    }

    @Test
    @DisplayName("advanceBack opens on the last item and wraps backward")
    void advanceBackWraps() {
        SwitcherController c = new SwitcherController(new FakeModel(items(3)));
        assertTrue(c.advanceBack());
        assertEquals(2, c.getIndex());
        c.advanceBack();
        assertEquals(1, c.getIndex());
        c.advanceBack();
        assertEquals(0, c.getIndex());
        c.advanceBack();
        assertEquals(2, c.getIndex(), "wraps to the end");
    }

    @Test
    @DisplayName("commit activates the highlighted window and closes")
    void commitActivates() {
        List<SwitcherItem> model = items(3);
        FakeModel fake = new FakeModel(model);
        SwitcherController c = new SwitcherController(fake);
        c.advance();
        c.advance();                 // now index 2
        c.commit();
        assertEquals(1, fake.activateCount);
        assertSame(model.get(2), fake.activated);
        assertFalse(c.isActive());
        assertEquals(-1, c.getIndex());
        assertNull(c.currentItem());
    }

    @Test
    @DisplayName("cancel closes without activating anything")
    void cancelDoesNotActivate() {
        FakeModel fake = new FakeModel(items(3));
        SwitcherController c = new SwitcherController(fake);
        c.advance();
        c.cancel();
        assertEquals(0, fake.activateCount);
        assertFalse(c.isActive());
        assertTrue(c.items().isEmpty());
    }

    @Test
    @DisplayName("commit/cancel on an inactive controller do nothing")
    void inertWhenInactive() {
        FakeModel fake = new FakeModel(items(3));
        SwitcherController c = new SwitcherController(fake);
        c.commit();
        c.cancel();
        assertEquals(0, fake.activateCount);
        assertFalse(c.isActive());
    }

    @Test
    @DisplayName("fewer than two windows: advance is a no-op")
    void singleWindowNoOp() {
        FakeModel one = new FakeModel(items(1));
        SwitcherController c = new SwitcherController(one);
        assertFalse(c.advance());
        assertFalse(c.isActive());
        assertFalse(c.advanceBack());
        assertFalse(c.isActive());
    }

    @Test
    @DisplayName("no windows (or a null list): advance is a no-op")
    void noWindowsNoOp() {
        SwitcherController empty =
                new SwitcherController(new FakeModel(items(0)));
        assertFalse(empty.advance());
        SwitcherController nul = new SwitcherController(new FakeModel(null));
        assertFalse(nul.advance());
        assertFalse(nul.isActive());
    }
}
