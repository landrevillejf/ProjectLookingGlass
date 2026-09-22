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
package org.jdesktop.lg3d.displayserver.desktop2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JPanel;
import org.jdesktop.lg3d.wg.switcher.SwitcherItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link Desktop2DSwitcherModel}: MRU-ordered enumeration of the
 * desktop's internal frames, activation through the {@code WindowSource} seam,
 * and the label fallback. Uses real (headless) {@link Desktop2DWindow}s and a
 * fake source, so no live desktop is required.
 */
class Desktop2DSwitcherModelTest {

    /** Records activation and serves a fixed window list. */
    private static final class FakeSource
            implements Desktop2DSwitcherModel.WindowSource {
        final List<Desktop2DWindow> windows = new ArrayList<>();
        Desktop2DWindow focused;
        int focusCount;

        @Override
        public List<Desktop2DWindow> openWindows() {
            return new ArrayList<>(windows);
        }

        @Override
        public void focusWindow(Desktop2DWindow window) {
            focused = window;
            focusCount++;
        }
    }

    private static Desktop2DWindow window(String title, String appName) {
        return new Desktop2DWindow(title, null, new JPanel(), appName);
    }

    @Test
    @DisplayName("items are the open windows in MRU order")
    void itemsInMruOrder() {
        FakeSource source = new FakeSource();
        Desktop2DWindow a = window("A", "a");
        Desktop2DWindow b = window("B", "b");
        Desktop2DWindow c = window("C", "c");
        source.windows.addAll(Arrays.asList(a, b, c));

        Desktop2DSwitcherModel model = new Desktop2DSwitcherModel(source);
        // c was used most recently, then b, then a.
        model.touch(a);
        model.touch(b);
        model.touch(c);

        List<SwitcherItem> items = model.items();
        assertEquals(3, items.size());
        assertSame(c, items.get(0).getWindow());
        assertSame(b, items.get(1).getWindow());
        assertSame(a, items.get(2).getWindow());
        assertEquals("C", items.get(0).getName(), "the label is the window title");
    }

    @Test
    @DisplayName("with no MRU history the present order is kept")
    void itemsWithoutHistory() {
        FakeSource source = new FakeSource();
        Desktop2DWindow a = window("A", "a");
        Desktop2DWindow b = window("B", "b");
        source.windows.addAll(Arrays.asList(a, b));
        Desktop2DSwitcherModel model = new Desktop2DSwitcherModel(source);
        List<SwitcherItem> items = model.items();
        assertSame(a, items.get(0).getWindow());
        assertSame(b, items.get(1).getWindow());
    }

    @Test
    @DisplayName("a blank title falls back to the application name")
    void blankTitleFallsBackToAppName() {
        FakeSource source = new FakeSource();
        source.windows.add(window("", "FileManager"));
        Desktop2DSwitcherModel model = new Desktop2DSwitcherModel(source);
        assertEquals("FileManager", model.items().get(0).getName());
    }

    @Test
    @DisplayName("no open windows yields an empty item list")
    void noWindows() {
        Desktop2DSwitcherModel model =
                new Desktop2DSwitcherModel(new FakeSource());
        assertTrue(model.items().isEmpty());
    }

    @Test
    @DisplayName("activate focuses the window and promotes it in the MRU")
    void activateFocusesAndPromotes() {
        FakeSource source = new FakeSource();
        Desktop2DWindow a = window("A", "a");
        Desktop2DWindow b = window("B", "b");
        source.windows.addAll(Arrays.asList(a, b));
        Desktop2DSwitcherModel model = new Desktop2DSwitcherModel(source);
        model.touch(a);
        model.touch(b);                 // MRU: b, a

        SwitcherItem itemA = new SwitcherItem(a, "A", null);
        model.activate(itemA);

        assertEquals(1, source.focusCount);
        assertSame(a, source.focused);
        // a was just activated, so it is now most-recently used.
        assertSame(a, model.items().get(0).getWindow());
    }

    @Test
    @DisplayName("activate ignores null and foreign items")
    void activateIgnoresForeignItems() {
        FakeSource source = new FakeSource();
        source.windows.add(window("A", "a"));
        Desktop2DSwitcherModel model = new Desktop2DSwitcherModel(source);
        model.activate(null);
        model.activate(new SwitcherItem(new Object(), "not a window", null));
        assertEquals(0, source.focusCount, "no window was focused");
    }

    @Test
    @DisplayName("forget drops a closed window from MRU promotion")
    void forgetRemovesFromMru() {
        FakeSource source = new FakeSource();
        Desktop2DWindow a = window("A", "a");
        Desktop2DWindow b = window("B", "b");
        Desktop2DSwitcherModel model = new Desktop2DSwitcherModel(source);
        model.touch(a);
        model.touch(b);
        model.forget(a);
        // Only b remains open; a's stale MRU entry must not resurface.
        source.windows.add(b);
        assertEquals(1, model.items().size());
        assertSame(b, model.items().get(0).getWindow());
    }

    @Test
    @DisplayName("the trigger is Ctrl+Alt+Tab, not the host-grabbed Alt+Tab")
    void triggerSpec() {
        Desktop2DSwitcherModel model =
                new Desktop2DSwitcherModel(new FakeSource());
        assertEquals("control alt TAB", model.triggerKeySpec());
        assertEquals(Desktop2DSwitcherModel.TRIGGER, model.triggerKeySpec());
    }
}
