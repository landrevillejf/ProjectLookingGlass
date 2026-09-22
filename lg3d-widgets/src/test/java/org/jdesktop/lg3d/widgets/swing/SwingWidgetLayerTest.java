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
package org.jdesktop.lg3d.widgets.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.nio.file.Path;
import java.util.List;
import javax.swing.JDesktopPane;
import org.jdesktop.lg3d.widgets.api.WidgetConfigStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the 2D widget host: placing and removing cards on a
 * {@link JDesktopPane}, the shared {@link WidgetConfigStore} persistence (so a
 * layout saved under the 3D desktop is honoured here), the default seeding and
 * unknown-type skipping on load, and the {@code install}/{@code current}/
 * {@code uninstall} lifecycle {@code Desktop2D} drives reflectively.
 *
 * <p>Everything runs headless: a {@code JDesktopPane} and the pure-Swing cards
 * need no X display, which is the whole premise of the 2D widget layer. The
 * tests use the two-arg constructor with a temp-file config so they never touch
 * the real {@code ~/.config/lg3d/widgets.properties}.</p>
 */
class SwingWidgetLayerTest {

    @TempDir
    Path tmp;

    private SwingWidgetLayer layer;

    @AfterEach
    void tearDown() {
        // Never leak the static 'current' (or its scheduler) between tests.
        SwingWidgetLayer.uninstall();
        if (layer != null) {
            layer.dispose();
            layer = null;
        }
    }

    private JDesktopPane desktop() {
        JDesktopPane d = new JDesktopPane();
        d.setSize(800, 600);
        return d;
    }

    private SwingWidgetLayer newLayer(JDesktopPane d, String file) {
        layer = new SwingWidgetLayer(d, new WidgetConfigStore(tmp.resolve(file)));
        return layer;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("addWidget places a card on the pane and persists it")
    void addWidgetPlacesAndPersists() {
        JDesktopPane d = desktop();
        SwingWidgetLayer l = newLayer(d, "add.properties");

        String id = l.addWidget("clock", 0.5f, 0.5f);
        assertNotNull(id);
        assertTrue(l.instanceIds().contains(id));
        assertEquals("clock", l.typeOf(id));
        assertEquals(1, d.getComponentCount(), "the card is a child of the pane");

        WidgetConfigStore store = l.config();
        assertTrue(store.instances().contains(id));
        assertEquals("clock", store.getType(id));
        assertEquals(0.5f, store.getX(id, -1f));
        assertEquals(0.5f, store.getY(id, -1f));
    }

    @Test
    @DisplayName("addWidget rejects an unknown type")
    void addWidgetRejectsUnknownType() {
        SwingWidgetLayer l = newLayer(desktop(), "unknown.properties");
        assertNull(l.addWidget("nope", 0.5f, 0.5f));
        assertTrue(l.instanceIds().isEmpty());
    }

    @Test
    @DisplayName("removeWidget takes the card off the pane and forgets it")
    void removeWidgetForgets() {
        JDesktopPane d = desktop();
        SwingWidgetLayer l = newLayer(d, "remove.properties");

        String id = l.addWidget("cpu", 0.3f, 0.3f);
        l.removeWidget(id);

        assertFalse(l.instanceIds().contains(id));
        assertFalse(l.config().instances().contains(id));
        assertNull(l.config().getType(id));
        assertEquals(0, d.getComponentCount());
    }

    @Test
    @DisplayName("addWidgetAtFreeSpot cascades new widgets apart")
    void addWidgetAtFreeSpotCascades() {
        SwingWidgetLayer l = newLayer(desktop(), "free.properties");
        String a = l.addWidgetAtFreeSpot("clock");
        String b = l.addWidgetAtFreeSpot("cpu");
        String c = l.addWidgetAtFreeSpot("memory");

        assertNotNull(a);
        assertNotNull(b);
        assertNotNull(c);
        assertEquals(3, l.instanceIds().size());
        assertNotEquals(l.config().getX(a, -1f), l.config().getX(b, -1f),
                "the cascade must not stack widgets on top of each other");
    }

    @Test
    @DisplayName("loadPersisted seeds the two defaults on a fresh config")
    void loadPersistedSeedsDefaults() {
        SwingWidgetLayer l = newLayer(desktop(), "seed.properties");
        assertTrue(l.instanceIds().isEmpty(), "nothing is placed before load");

        l.loadPersisted();

        assertEquals(2, l.instanceIds().size());
        List<String> types = l.instanceIds().stream().map(l::typeOf).toList();
        assertTrue(types.contains("clock"));
        assertTrue(types.contains("temperature"));
    }

    @Test
    @DisplayName("loadPersisted restores a layout saved by the other desktop")
    void loadPersistedRestoresSavedLayout() {
        // Simulate a layout written earlier (e.g. by the 3D WidgetHost).
        WidgetConfigStore seed = new WidgetConfigStore(tmp.resolve("saved.properties"));
        seed.addInstance("cpu-1");
        seed.setType("cpu-1", "cpu");
        seed.setPosition("cpu-1", 0.3f, 0.4f);
        seed.save();

        SwingWidgetLayer l = newLayer(desktop(), "saved.properties");
        l.loadPersisted();

        assertTrue(l.instanceIds().contains("cpu-1"));
        assertEquals("cpu", l.typeOf("cpu-1"));
    }

    @Test
    @DisplayName("loadPersisted skips instances of an unknown/retired type")
    void loadPersistedSkipsUnknownType() {
        WidgetConfigStore seed = new WidgetConfigStore(tmp.resolve("bogus.properties"));
        seed.addInstance("old-1");
        seed.setType("old-1", "retired-widget");
        seed.setPosition("old-1", 0.2f, 0.2f);
        seed.addInstance("clock-1");
        seed.setType("clock-1", "clock");
        seed.setPosition("clock-1", 0.5f, 0.5f);
        seed.save();

        SwingWidgetLayer l = newLayer(desktop(), "bogus.properties");
        l.loadPersisted();

        assertFalse(l.instanceIds().contains("old-1"), "unknown type is skipped");
        assertTrue(l.instanceIds().contains("clock-1"), "known type is kept");
    }

    @Test
    @DisplayName("relayout keeps every card inside the pane")
    void relayoutKeepsCardsInsideDesktop() {
        JDesktopPane d = desktop();
        SwingWidgetLayer l = newLayer(d, "relayout.properties");
        l.addWidget("clock", 0.5f, 0.5f);

        l.relayout();   // must not throw

        Component card = d.getComponent(0);
        assertTrue(card.getX() >= 0 && card.getY() >= 0);
        assertTrue(card.getX() + card.getWidth() <= d.getWidth());
        assertTrue(card.getY() + card.getHeight() <= d.getHeight());
    }

    @Test
    @DisplayName("instanceIds returns a copy, not the live map")
    void instanceIdsIsACopy() {
        SwingWidgetLayer l = newLayer(desktop(), "copy.properties");
        l.addWidget("clock", 0.5f, 0.5f);

        List<String> ids = l.instanceIds();
        ids.clear();

        assertEquals(1, l.instanceIds().size(), "internal state is unaffected");
    }

    @Test
    @DisplayName("dispose removes every card and empties the layer")
    void disposeClearsEverything() {
        JDesktopPane d = desktop();
        layer = new SwingWidgetLayer(d, new WidgetConfigStore(tmp.resolve("dispose.properties")));
        layer.addWidget("clock", 0.5f, 0.5f);
        layer.addWidget("cpu", 0.3f, 0.3f);

        layer.dispose();

        assertTrue(layer.instanceIds().isEmpty());
        assertEquals(0, d.getComponentCount());
    }

    @Test
    @DisplayName("install/current/uninstall manage the live layer")
    void staticLifecycle() {
        String oldHome = System.getProperty("user.home");
        // Redirect user.home so install() seeds a throwaway config, not the real
        // ~/.config/lg3d/widgets.properties.
        System.setProperty("user.home", tmp.toString());
        try {
            assertNull(SwingWidgetLayer.current());
            SwingWidgetLayer.install(null);          // safe no-op
            assertNull(SwingWidgetLayer.current());

            SwingWidgetLayer.install(desktop());
            SwingWidgetLayer live = SwingWidgetLayer.current();
            assertNotNull(live);
            assertEquals(2, live.instanceIds().size(),
                    "a fresh home seeds the clock + temperature defaults");

            SwingWidgetLayer.uninstall();
            assertNull(SwingWidgetLayer.current());
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }
}
