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
package org.jdesktop.lg3d.apps.wonderland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.jdesktop.lg3d.apps.wonderland.WonderlandWorldPanel.Loaded;
import org.jdesktop.lg3d.apps.wonderland.WonderlandWorldPanel.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the face of the Wonderland World app: what the panel shows
 * after a read that worked, after one that did not, and how a published flat
 * list becomes indented rows.
 *
 * <p>The panel is constructed but never shown, which is legal headless, and the
 * read is driven through the package-private seams ({@code read} /
 * {@code render}) rather than the button, so no background thread and no EDT
 * timing is involved. The transport is scripted, so no server and no network is
 * touched &mdash; the same discipline {@code OpenMeteoTest} applies to its own
 * app. How the rows actually paint inside a {@code SwingNode} or an MDI frame is
 * verified with the desktop probe, not here.</p>
 */
class WonderlandWorldPanelTest {

    /** The same two-level world {@code WorldCatalogTest} parses. */
    private static final String SAMPLE = "["
            + "{\"id\":\"1\",\"parent\":null,\"className\":\"org.jdesktop.wonderland.modules.cell.EnvironmentCell\",\"name\":\"Environment\"},"
            + "{\"id\":\"2\",\"parent\":\"1\",\"className\":\"org.jdesktop.wonderland.modules.cell.TestCell\",\"name\":\"Room\"},"
            + "{\"id\":\"3\",\"parent\":\"2\",\"className\":\"org.jdesktop.wonderland.modules.meshmodel.MeshModelCell\",\"name\":null}"
            + "]";

    /** A panel that does not reach out on its own, reading a fixed document. */
    private static WonderlandWorldPanel panelReturning(String body) {
        return new WonderlandWorldPanel(url -> body, false);
    }

    @Test
    @DisplayName("the address field opens on the configured server")
    void opensOnConfiguredServer() {
        WonderlandWorldPanel panel = panelReturning(SAMPLE);

        // The panel and the test read the same property, so this states the
        // wiring rather than a hard-coded localhost.
        assertEquals(WorldCatalog.defaultServer(), panel.serverText());
        assertTrue(WonderlandWorldPanel.isReadableAddress(panel.serverText()));
        panel.close();
    }

    @Test
    @DisplayName("a blank address is refused before any request is made")
    void blankAddressIsNotReadable() {
        assertFalse(WonderlandWorldPanel.isReadableAddress(null));
        assertFalse(WonderlandWorldPanel.isReadableAddress(""));
        assertFalse(WonderlandWorldPanel.isReadableAddress("   "));

        WonderlandWorldPanel panel = panelReturning(SAMPLE);
        // And a panel that has not rendered anything lists nothing, which is
        // what the guard leaves behind when it refuses an empty field.
        assertTrue(panel.rows().isEmpty());
        panel.close();
    }

    @Test
    @DisplayName("a loaded world lists its cells with depth and path")
    void rendersPublishedWorld() {
        WonderlandWorldPanel panel = panelReturning(SAMPLE);

        panel.render(panel.read("localhost:8080"));

        List<Row> rows = panel.rows();
        assertEquals(3, rows.size());
        assertEquals(List.of(0, 1, 2),
                List.of(rows.get(0).depth, rows.get(1).depth, rows.get(2).depth));
        assertEquals("Environment", rows.get(0).toString());
        assertEquals("MeshModelCell", rows.get(2).toString());
        assertEquals("Environment / Room", rows.get(1).path);
        assertNull(rows.get(0).cell.parent);
        assertEquals("1", rows.get(1).cell.parent);
        panel.close();
    }

    @Test
    @DisplayName("the status line is the census of what was actually read")
    void statusStatesTheCensus() {
        WonderlandWorldPanel panel = panelReturning(SAMPLE);

        panel.render(panel.read("localhost:8080"));

        assertEquals("3 cells \u00B7 3 levels \u00B7 root: Environment"
                + " \u00B7 http://localhost:8080/api/world/cells", panel.statusText());
        panel.close();
    }

    @Test
    @DisplayName("the first row is selected so the detail column is never blank")
    void firstRowIsSelectedForTheDetailColumn() {
        WonderlandWorldPanel panel = panelReturning(SAMPLE);

        panel.render(panel.read("localhost:8080"));

        assertEquals("1", panel.selectedRow().cell.id);
        // The breadcrumb follows the selection through the list listener.
        assertEquals("Environment", panel.detailPathText());
        panel.close();
    }

    @Test
    @DisplayName("an empty world is listed as empty, not as an error")
    void emptyWorldIsReportedHonestly() {
        WonderlandWorldPanel panel = panelReturning("[]");

        panel.render(panel.read("localhost:8080"));

        assertTrue(panel.rows().isEmpty());
        assertEquals("the world has no cells"
                + " \u00B7 http://localhost:8080/api/world/cells", panel.statusText());
        assertNull(panel.selectedRow());
        panel.close();
    }

    @Test
    @DisplayName("an unreachable server says so and clears the stale rows")
    void failureNamesTheServer() throws IOException {
        WonderlandWorldPanel panel = new WonderlandWorldPanel(url -> {
            throw new IOException("Connection refused");
        }, false);
        // A previous successful read must not keep showing once the server is gone.
        panel.render(new Loaded("localhost:8080", WorldCatalog.parse(SAMPLE), null));
        assertEquals(3, panel.rows().size());

        panel.render(panel.read("localhost:8080"));

        assertTrue(panel.rows().isEmpty());
        assertEquals("Cannot reach http://localhost:8080/api/world/cells"
                + " (Connection refused)", panel.statusText());
        assertNull(panel.selectedRow());
        panel.close();
    }

    @Test
    @DisplayName("a read never throws, whatever the transport did")
    void readNeverThrows() {
        WonderlandWorldPanel panel = new WonderlandWorldPanel(url -> {
            throw new IllegalStateException("the transport blew up");
        }, false);

        Loaded loaded = panel.read("localhost:8080");

        assertNull(loaded.world);
        // A non-IO failure is still reportable on the status strip.
        assertTrue(loaded.failure instanceof IOException);
        panel.render(loaded);
        assertTrue(panel.statusText().startsWith("Cannot reach "));
        panel.close();
    }

    @Test
    @DisplayName("close stops the fetch thread and runs the host's hook")
    void closeRunsTheHook() {
        boolean[] closed = new boolean[1];
        WonderlandWorldPanel panel = panelReturning(SAMPLE);
        panel.setOnClose(() -> closed[0] = true);

        panel.close();

        assertTrue(closed[0]);
        // A second close must not throw: a host may dispose the frame twice.
        panel.close();
    }

    @Test
    @DisplayName("a panel without a hook still closes")
    void closeWithoutHookIsSafe() {
        WonderlandWorldPanel panel = panelReturning(SAMPLE);
        panel.close();
        assertNull(panel.selectedRow());
    }
}
