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
package org.jdesktop.lg3d.scenemanager.utils.switcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jdesktop.lg3d.wg.switcher.SwitcherItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link Frame3DSwitcherModel}: MRU-ordered enumeration of the open
 * window handles, activation through the {@code WindowSource} seam, and the
 * trigger spec. Uses opaque {@link Object} handles and a fake source, so no
 * Java 3D scene graph or live desktop is required — the same headless approach
 * as {@code Desktop2DSwitcherModelTest}.
 */
class Frame3DSwitcherModelTest {

    /** Records activation and serves a fixed window list. */
    private static final class FakeSource
            implements Frame3DSwitcherModel.WindowSource {
        final List<SwitcherItem> windows = new ArrayList<>();
        Object focused;
        int focusCount;

        @Override
        public List<SwitcherItem> openWindows() {
            return new ArrayList<>(windows);
        }

        @Override
        public void focusWindow(Object window) {
            focused = window;
            focusCount++;
        }
    }

    /** A {@code WindowSource} whose {@link #openWindows()} returns null. */
    private static final class NullSource
            implements Frame3DSwitcherModel.WindowSource {
        @Override
        public List<SwitcherItem> openWindows() {
            return null;
        }

        @Override
        public void focusWindow(Object window) {
        }
    }

    private static SwitcherItem item(Object handle, String name) {
        return new SwitcherItem(handle, name, null, null);
    }

    @Test
    @DisplayName("items are the open windows in MRU order")
    void itemsInMruOrder() {
        FakeSource source = new FakeSource();
        Object a = new Object();
        Object b = new Object();
        Object c = new Object();
        source.windows.addAll(Arrays.asList(
                item(a, "A"), item(b, "B"), item(c, "C")));

        Frame3DSwitcherModel model = new Frame3DSwitcherModel(source);
        // c was used most recently, then b, then a.
        model.touch(a);
        model.touch(b);
        model.touch(c);

        List<SwitcherItem> items = model.items();
        assertEquals(3, items.size());
        assertSame(c, items.get(0).getWindow());
        assertSame(b, items.get(1).getWindow());
        assertSame(a, items.get(2).getWindow());
        assertEquals("C", items.get(0).getName(), "the label is the window name");
    }

    @Test
    @DisplayName("with no MRU history the present order is kept")
    void itemsWithoutHistory() {
        FakeSource source = new FakeSource();
        Object a = new Object();
        Object b = new Object();
        source.windows.addAll(Arrays.asList(item(a, "A"), item(b, "B")));
        Frame3DSwitcherModel model = new Frame3DSwitcherModel(source);
        List<SwitcherItem> items = model.items();
        assertSame(a, items.get(0).getWindow());
        assertSame(b, items.get(1).getWindow());
    }

    @Test
    @DisplayName("a null window list is treated as empty")
    void nullWindowListIsEmpty() {
        Frame3DSwitcherModel model = new Frame3DSwitcherModel(new NullSource());
        assertTrue(model.items().isEmpty());
    }

    @Test
    @DisplayName("no open windows yields an empty item list")
    void noWindows() {
        Frame3DSwitcherModel model =
                new Frame3DSwitcherModel(new FakeSource());
        assertTrue(model.items().isEmpty());
    }

    @Test
    @DisplayName("activate focuses the window and promotes it in the MRU")
    void activateFocusesAndPromotes() {
        FakeSource source = new FakeSource();
        Object a = new Object();
        Object b = new Object();
        SwitcherItem itemA = item(a, "A");
        source.windows.addAll(Arrays.asList(itemA, item(b, "B")));
        Frame3DSwitcherModel model = new Frame3DSwitcherModel(source);
        model.touch(a);
        model.touch(b);                 // MRU: b, a

        model.activate(itemA);

        assertEquals(1, source.focusCount);
        assertSame(a, source.focused);
        // a was just activated, so it is now most-recently used.
        assertSame(a, model.items().get(0).getWindow());
    }

    @Test
    @DisplayName("activate ignores a null item")
    void activateIgnoresNull() {
        FakeSource source = new FakeSource();
        source.windows.add(item(new Object(), "A"));
        Frame3DSwitcherModel model = new Frame3DSwitcherModel(source);
        model.activate(null);
        assertEquals(0, source.focusCount, "no window was focused");
    }

    @Test
    @DisplayName("forget drops a closed window from MRU promotion")
    void forgetRemovesFromMru() {
        FakeSource source = new FakeSource();
        Object a = new Object();
        Object b = new Object();
        Frame3DSwitcherModel model = new Frame3DSwitcherModel(source);
        model.touch(a);
        model.touch(b);
        model.forget(a);
        // Only b remains open; a's stale MRU entry must not resurface.
        source.windows.add(item(b, "B"));
        assertEquals(1, model.items().size());
        assertSame(b, model.items().get(0).getWindow());
    }

    @Test
    @DisplayName("the trigger is plain Alt+Tab, since lg3d owns the display")
    void triggerSpec() {
        Frame3DSwitcherModel model =
                new Frame3DSwitcherModel(new FakeSource());
        assertEquals("alt TAB", model.triggerKeySpec());
        assertEquals(Frame3DSwitcherModel.TRIGGER, model.triggerKeySpec());
    }
}
