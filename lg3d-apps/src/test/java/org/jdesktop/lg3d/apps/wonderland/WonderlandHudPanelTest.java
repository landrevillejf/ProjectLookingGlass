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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.jdesktop.wonderland.desktop.CellFrameBridge;
import org.jdesktop.wonderland.desktop.FrameMounter;
import org.jdesktop.wonderland.desktop.WorldHudModel;
import org.jdesktop.wonderland.desktop.WorldInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the desktop's world HUD: the two truths it joins (what the
 * world publishes, what the session mounted), and the one control it offers.
 *
 * <p>The panel is built and never shown, which is legal headless, and the read is
 * driven through the package-private {@code apply} seam rather than the Refresh
 * button, so no background thread and no EDT timing is involved. The bridge is a
 * real {@link CellFrameBridge} over a recording {@link FrameMounter} &mdash; the
 * same seam {@code wonderland-desktop} tests through &mdash; so a toggle here is
 * asserted all the way down to the call that would move the scene-graph Switch.
 * How the rows actually paint through a {@code SwingNode} is the probe's
 * business, not this suite's.</p>
 */
class WonderlandHudPanelTest {

    /** The same three-level world the catalog and panel tests parse. */
    private static final String SAMPLE = "["
            + "{\"id\":\"1\",\"parent\":null,\"className\":\"org.jdesktop.wonderland.modules.cell.EnvironmentCell\",\"name\":\"Environment\"},"
            + "{\"id\":\"2\",\"parent\":\"1\",\"className\":\"org.jdesktop.wonderland.modules.cell.TestCell\",\"name\":\"Room\"},"
            + "{\"id\":\"3\",\"parent\":\"2\",\"className\":\"org.jdesktop.wonderland.modules.meshmodel.MeshModelCell\",\"name\":null}"
            + "]";

    /** The renderer is only ever tested for null by the bridge. */
    private static final CellRenderer STUB_RENDERER = new CellRenderer() {
        @Override
        public void cellTransformUpdate(CellTransform transform) {
        }

        @Override
        public void setStatus(CellStatus status, boolean increasing) {
        }

        @Override
        public CellStatus getStatus() {
            return null;
        }
    };

    /** Records the visibility writes, so a toggle is proven down to the Switch. */
    private static final class RecordingMounter implements FrameMounter {
        final List<String> visibility = new ArrayList<>();

        @Override
        public void mount(String cellId, CellRenderer renderer) {
        }

        @Override
        public void unmount(String cellId) {
        }

        @Override
        public void applyTransform(String cellId, double[] rowMajorFeed) {
        }

        @Override
        public void setVisible(String cellId, boolean visible) {
            visibility.add(cellId + "=" + visible);
        }
    }

    private RecordingMounter mounter;
    private CellFrameBridge bridge;
    private WorldHudModel model;

    private WonderlandHudPanel panel() {
        mounter = new RecordingMounter();
        bridge = new CellFrameBridge(mounter);
        model = new WorldHudModel(bridge);
        // autoRead false: the tests feed apply() by hand, so no thread runs.
        return new WonderlandHudPanel(model, bridge, () -> inventory(SAMPLE), false);
    }

    private static WorldInventory inventory(String body) throws IOException {
        return WorldCatalogInventory.of(WorldCatalog.parse(body));
    }

    /** Loads the published world into the panel, as a read would. */
    private void load(WonderlandHudPanel panel, String body) throws IOException {
        panel.apply(inventory(body), null);
    }

    @Test
    @DisplayName("the panel refuses a join with no parts")
    void nullsAreRejected() {
        WonderlandHudPanel built = panel();
        assertThrows(IllegalArgumentException.class,
                () -> new WonderlandHudPanel(null, bridge, () -> inventory(SAMPLE), false));
        assertThrows(IllegalArgumentException.class,
                () -> new WonderlandHudPanel(model, null, () -> inventory(SAMPLE), false));
        assertThrows(IllegalArgumentException.class,
                () -> new WonderlandHudPanel(model, bridge, null, false));
        built.close();
    }

    @Test
    @DisplayName("a read lists every published cell with its joined state")
    void publishedCellsAreListed() throws IOException {
        WonderlandHudPanel panel = panel();
        bridge.mountCell("2", STUB_RENDERER);

        load(panel, SAMPLE);

        List<WorldHudModel.Row> rows = panel.rows();
        assertEquals(3, rows.size());
        assertEquals(List.of("1", "2", "3"),
                List.of(rows.get(0).cellId(), rows.get(1).cellId(), rows.get(2).cellId()));
        assertEquals(List.of(0, 1, 2),
                List.of(rows.get(0).depth(), rows.get(1).depth(), rows.get(2).depth()));
        assertEquals(WorldHudModel.CellState.PENDING, rows.get(0).state());
        assertEquals(WorldHudModel.CellState.MOUNTED, rows.get(1).state());
        panel.close();
    }

    @Test
    @DisplayName("the status strip is the model's own census")
    void statusIsTheCensus() throws IOException {
        WonderlandHudPanel panel = panel();
        bridge.mountCell("2", STUB_RENDERER);

        load(panel, SAMPLE);

        // One sentence, computed by the model, so the list and the strip can
        // never report different worlds.
        assertEquals("3 cells: 1 shown, 0 hidden, 2 pending", panel.statusText());
        assertEquals(model.summary(), panel.statusText());
        panel.close();
    }

    @Test
    @DisplayName("the mounted truth shows before any read lands")
    void mountedCellsAppearWithoutARead() {
        WonderlandHudPanel panel = panel();

        bridge.mountCell("7", STUB_RENDERER);
        // The constructor painted from the model already; a re-render picks up
        // the mount the way a live session's cellLoaded would.
        panel.apply(null, null);

        assertEquals(1, panel.rows().size());
        assertEquals("7", panel.rows().get(0).cellId());
        assertEquals(WorldHudModel.CellState.MOUNTED, panel.rows().get(0).state());
        assertEquals("1 cells: 1 shown, 0 hidden, 0 pending", panel.statusText());
        panel.close();
    }

    @Test
    @DisplayName("hiding a mounted cell writes through the bridge and re-joins")
    void hideWritesThroughTheBridge() throws IOException {
        WonderlandHudPanel panel = panel();
        bridge.mountCell("2", STUB_RENDERER);
        load(panel, SAMPLE);
        panel.select("2");

        assertEquals("Hide", panel.toggleText());
        assertTrue(panel.isToggleEnabled());
        panel.toggleSelected();

        assertEquals(List.of("2=false"), mounter.visibility);
        assertFalse(bridge.isVisible("2"));
        assertEquals(WorldHudModel.CellState.HIDDEN, panel.rows().get(1).state());
        assertEquals("3 cells: 0 shown, 1 hidden, 2 pending", panel.statusText());
        // The same row now offers the way back.
        assertEquals("Show", panel.toggleText());
        assertTrue(panel.isToggleEnabled());

        panel.toggleSelected();
        assertEquals(List.of("2=false", "2=true"), mounter.visibility);
        assertTrue(bridge.isVisible("2"));
        panel.close();
    }

    @Test
    @DisplayName("a pending cell is listed but cannot be toggled")
    void pendingRowHasNoToggle() throws IOException {
        WonderlandHudPanel panel = panel();
        load(panel, SAMPLE);
        panel.select("1");

        assertFalse(panel.isToggleEnabled());
        panel.toggleSelected();

        // Nothing was mounted, so nothing may be written to the scene graph --
        // the button would otherwise claim a control the session does not offer.
        assertTrue(mounter.visibility.isEmpty());
        assertFalse(bridge.isMounted("1"));
        panel.close();
    }

    @Test
    @DisplayName("a mounted cell the world never published is still listed")
    void unlistedMountIsStillShown() throws IOException {
        WonderlandHudPanel panel = panel();
        bridge.mountCell("9", STUB_RENDERER);

        load(panel, SAMPLE);

        List<WorldHudModel.Row> rows = panel.rows();
        assertEquals(4, rows.size());
        assertEquals("9", rows.get(3).cellId());
        // No inventory entry, so the id stands in as the label and the class is
        // unknown: a scene graph the user sees is never left unaccounted for.
        assertEquals("9", rows.get(3).label());
        assertNull(rows.get(3).className());
        assertEquals(WorldHudModel.CellState.MOUNTED, rows.get(3).state());
        panel.close();
    }

    @Test
    @DisplayName("a failed read keeps the rows and says why")
    void failedReadKeepsRows() throws IOException {
        WonderlandHudPanel panel = panel();
        load(panel, SAMPLE);
        assertEquals(3, panel.rows().size());

        panel.apply(null, new IOException("HTTP 500"));

        assertTrue(panel.statusText().startsWith("Cannot read the world (HTTP 500"));
        // A refresh that failed is not evidence the world emptied.
        assertEquals(3, panel.rows().size());
        panel.close();
    }

    @Test
    @DisplayName("an empty world reads as zero, not as an error")
    void emptyWorldReadsAsZero() throws IOException {
        WonderlandHudPanel panel = panel();

        load(panel, "[]");

        assertTrue(panel.rows().isEmpty());
        assertNull(panel.selectedCellId());
        assertFalse(panel.isToggleEnabled());
        assertEquals("0 cells: 0 shown, 0 hidden, 0 pending", panel.statusText());
        panel.close();
    }

    @Test
    @DisplayName("the selection survives a refresh that still lists the cell")
    void selectionSurvivesRefresh() throws IOException {
        WonderlandHudPanel panel = panel();
        load(panel, SAMPLE);
        panel.select("2");

        load(panel, SAMPLE);

        assertEquals("2", panel.selectedCellId());
        // The first row is selected when the cell has gone, so the toggle is
        // never left pointing at nothing while rows exist.
        load(panel, "[{\"id\":\"1\",\"parent\":null,\"className\":\"x.EnvironmentCell\",\"name\":\"Environment\"}]");
        assertEquals("1", panel.selectedCellId());
        panel.close();
    }

    @Test
    @DisplayName("close stops the reader and runs the host's hook once")
    void closeIsIdempotent() {
        WonderlandHudPanel panel = panel();
        int[] hooks = new int[1];
        panel.setOnClose(() -> hooks[0]++);

        panel.close();
        panel.close();

        assertEquals(1, hooks[0]);
    }
}
