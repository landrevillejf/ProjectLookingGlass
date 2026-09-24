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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SessionManager}: capturing the open windows into a snapshot
 * (identity, bounds and the minimised/maximised flags), skipping a window that
 * carries no relaunch command, lifting a degenerate size so it still round-trips,
 * and the save/load/clear round trip through an in-memory {@link SessionStore}
 * fake — so nothing touches the real user preferences. Reads window state only;
 * runs headless.
 */
class SessionManagerTest {

    /** In-memory stand-in for the preferences-backed store. */
    private static final class FakeStore implements SessionStore {
        private SessionSnapshot saved = SessionSnapshot.EMPTY;
        private boolean nullOnLoad;

        @Override
        public void save(SessionSnapshot snapshot) {
            this.saved = snapshot;
        }

        @Override
        public SessionSnapshot load() {
            return nullOnLoad ? null : saved;
        }

        @Override
        public void clear() {
            saved = SessionSnapshot.EMPTY;
        }

        SessionSnapshot saved() {
            return saved;
        }
    }

    private static Desktop2DWindow window(String name, String command, String icon,
                                          int x, int y, int w, int h) {
        Desktop2DWindow win =
                new Desktop2DWindow(name, null, new JPanel(), name, command, icon);
        win.setBounds(x, y, w, h);
        return win;
    }

    @Test
    @DisplayName("capture records each window's identity, bounds and state")
    void captureBasic() {
        Desktop2DWindow a = window("Calculator", "java a.Calculator", "calc.png",
                10, 20, 300, 200);
        Desktop2DWindow b = window("Terminal", "java b.Terminal", null,
                40, 50, 400, 250);
        SessionSnapshot snapshot = SessionManager.capture(Arrays.asList(a, b));
        assertEquals(2, snapshot.size());
        WindowRecord ra = snapshot.windows().get(0);
        assertEquals("Calculator", ra.appName());
        assertEquals("java a.Calculator", ra.command());
        assertEquals("calc.png", ra.iconResource());
        assertEquals(10, ra.x());
        assertEquals(20, ra.y());
        assertEquals(300, ra.width());
        assertEquals(200, ra.height());
        assertNull(snapshot.windows().get(1).iconResource());
    }

    @Test
    @DisplayName("a minimised window is captured with its iconified flag set")
    void captureIconified() throws Exception {
        Desktop2DWindow win = window("Calc", "java a.Calc", null, 5, 5, 100, 100);
        win.setIcon(true);
        WindowRecord record =
                SessionManager.capture(List.of(win)).windows().get(0);
        assertTrue(record.iconified());
    }

    @Test
    @DisplayName("a window with no relaunch command is skipped")
    void captureSkipsNoCommand() {
        Desktop2DWindow restorable = window("Calc", "java a.Calc", null, 0, 0, 100, 100);
        Desktop2DWindow adHoc = window("Folder", null, null, 0, 0, 100, 100);
        SessionSnapshot snapshot = SessionManager.capture(Arrays.asList(adHoc, restorable));
        assertEquals(1, snapshot.size());
        assertEquals("Calc", snapshot.windows().get(0).appName());
    }

    @Test
    @DisplayName("a blank command is treated as unrestorable too")
    void captureSkipsBlankCommand() {
        Desktop2DWindow blank = window("X", "   ", null, 0, 0, 100, 100);
        assertTrue(SessionManager.capture(List.of(blank)).isEmpty());
    }

    @Test
    @DisplayName("a degenerate size is lifted to 1px so it still round-trips")
    void captureLiftsDegenerateSize() {
        Desktop2DWindow win = window("Calc", "java a.Calc", null, 7, 8, 0, 0);
        WindowRecord record =
                SessionManager.capture(List.of(win)).windows().get(0);
        assertEquals(1, record.width());
        assertEquals(1, record.height());
        assertEquals(7, record.x());
        assertEquals(8, record.y());
    }

    @Test
    @DisplayName("an empty or null window list captures to the empty snapshot")
    void captureEmpty() {
        assertTrue(SessionManager.capture(Collections.emptyList()).isEmpty());
        assertTrue(SessionManager.capture(null).isEmpty());
        assertTrue(SessionManager.capture(
                List.of(window("X", null, null, 0, 0, 10, 10))).isEmpty());
    }

    @Test
    @DisplayName("a null window in the list is ignored")
    void captureIgnoresNullWindow() {
        Desktop2DWindow win = window("Calc", "java a.Calc", null, 0, 0, 100, 100);
        SessionSnapshot snapshot = SessionManager.capture(Arrays.asList(null, win));
        assertEquals(1, snapshot.size());
    }

    @Test
    @DisplayName("save persists the capture; load returns it; clear empties it")
    void saveLoadClear() {
        FakeStore store = new FakeStore();
        SessionManager manager = new SessionManager(store);
        Desktop2DWindow win = window("Calc", "java a.Calc", "c.png", 1, 2, 300, 200);

        assertTrue(manager.load().isEmpty());

        manager.save(List.of(win));
        assertEquals(1, store.saved().size());
        assertEquals("Calc", manager.load().windows().get(0).appName());

        manager.clear();
        assertTrue(manager.load().isEmpty());
        assertTrue(store.saved().isEmpty());
    }

    @Test
    @DisplayName("save round-trips through the store's encoded form")
    void saveRoundTripsThroughEncoding() {
        // A store that encodes and decodes, exactly as the preferences-backed
        // one does, so the capture survives the persisted String form.
        SessionStore encoding = new SessionStore() {
            private String persisted = "";

            @Override
            public void save(SessionSnapshot snapshot) {
                persisted = snapshot.encode();
            }

            @Override
            public SessionSnapshot load() {
                return SessionSnapshot.decode(persisted);
            }

            @Override
            public void clear() {
                persisted = "";
            }
        };
        SessionManager manager = new SessionManager(encoding);
        manager.save(List.of(
                window("My | App", "java a.A --x=\"p|q;r\"", "i c.png",
                        100, 50, 640, 480)));
        WindowRecord restored = manager.load().windows().get(0);
        assertEquals("My | App", restored.appName());
        assertEquals("java a.A --x=\"p|q;r\"", restored.command());
        assertEquals("i c.png", restored.iconResource());
        assertEquals(640, restored.width());
    }

    @Test
    @DisplayName("a store that yields null on load is treated as empty")
    void loadNullIsEmptied() {
        FakeStore store = new FakeStore();
        store.nullOnLoad = true;
        assertTrue(new SessionManager(store).load().isEmpty());
    }
}
