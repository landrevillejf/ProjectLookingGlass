/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c: headless tests for the pure CellFrameBridge logic.
 *
 * The bridge is the mount/unmount/transform decision layer; it never touches a
 * Jogamp scene-graph type -- it hands a CellRenderer (a headless interface) to
 * an injected FrameMounter. So the whole class is testable in CI with a
 * recording stub. WonderlandWorldContainer (the probe-only FrameMounter that
 * does the instanceof-based BranchGroup extraction) is exercised by the in-JVM
 * desktop probe, not here.
 */
package org.jdesktop.wonderland.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the pure {@link CellFrameBridge} mapping logic. Drives the
 * bridge with a recording {@link FrameMounter} stub; never constructs a Jogamp
 * scene-graph object.
 */
class CellFrameBridgeTest {

    /**
     * A non-null {@link CellRenderer} sentinel that satisfies the bridge's null
     * guard. The {@link RecordingMounter} never calls methods on the renderer --
     * it only records that mount() was invoked -- so this stub exists purely to
     * be non-null.
     */
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

    private RecordingMounter mounter;
    private CellFrameBridge bridge;

    @BeforeEach
    void setUp() {
        mounter = new RecordingMounter();
        bridge = new CellFrameBridge(mounter);
    }

    @Test
    void nullMounterThrowsIllegalArgument() {
        assertThatThrownBy(() -> new CellFrameBridge(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mountCellDelegatesToMounter() {
        bridge.mountCell("cell-1", STUB_RENDERER);
        assertThat(mounter.mountCalls).containsExactly("cell-1");
        assertThat(bridge.isMounted("cell-1")).isTrue();
    }

    @Test
    void nullRendererSkipsMount() {
        bridge.mountCell("cell-1", null);
        assertThat(mounter.mountCalls).isEmpty();
        assertThat(bridge.isMounted("cell-1")).isFalse();
    }

    @Test
    void nullCellIdSkipsMount() {
        bridge.mountCell(null, STUB_RENDERER);
        assertThat(mounter.mountCalls).isEmpty();
    }

    @Test
    void doubleMountIsIgnored() {
        bridge.mountCell("A", STUB_RENDERER);
        bridge.mountCell("A", STUB_RENDERER);
        assertThat(mounter.mountCalls).hasSize(1);
    }

    @Test
    void unmountMountedCell() {
        bridge.mountCell("A", STUB_RENDERER);
        bridge.unmountCell("A");
        assertThat(mounter.unmountCalls).containsExactly("A");
        assertThat(bridge.isMounted("A")).isFalse();
    }

    @Test
    void unmountUnmountedCellIsIgnored() {
        bridge.unmountCell("ghost");
        assertThat(mounter.unmountCalls).isEmpty();
    }

    @Test
    void unmountNullIsIgnored() {
        bridge.unmountCell(null);
        assertThat(mounter.unmountCalls).isEmpty();
    }

    @Test
    void updateTransformOnMountedCell() {
        bridge.mountCell("A", STUB_RENDERER);
        CellTransform t = new CellTransform();
        bridge.updateTransform("A", t);
        assertThat(mounter.transformCalls).hasSize(1);
        assertThat(mounter.transformCalls.get(0).cellId()).isEqualTo("A");
        assertThat(mounter.transformCalls.get(0).feed()).hasSize(16);
    }

    @Test
    void updateTransformCarriesTranslationInLastColumn() {
        bridge.mountCell("A", STUB_RENDERER);
        CellTransform t = new CellTransform();
        t.setTranslation(new com.jme.math.Vector3f(1f, 2f, 3f));
        bridge.updateTransform("A", t);
        double[] feed = mounter.transformCalls.get(0).feed();
        // Row-major layout keeps the offset in the last COLUMN: indices 3/7/11.
        assertThat(feed[3]).isEqualTo(1.0);
        assertThat(feed[7]).isEqualTo(2.0);
        assertThat(feed[11]).isEqualTo(3.0);
    }

    @Test
    void updateTransformOnUnmountedCellIsIgnored() {
        bridge.updateTransform("ghost", new CellTransform());
        assertThat(mounter.transformCalls).isEmpty();
    }

    @Test
    void updateTransformNullCellIdIsIgnored() {
        bridge.updateTransform(null, new CellTransform());
        assertThat(mounter.transformCalls).isEmpty();
    }

    @Test
    void updateTransformNullTransformYieldsIdentityFeed() {
        bridge.mountCell("A", STUB_RENDERER);
        bridge.updateTransform("A", null);
        assertThat(mounter.transformCalls).hasSize(1);
        assertThat(mounter.transformCalls.get(0).feed()).containsExactly(
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1);
    }

    @Test
    void setVisibleOnMountedCell() {
        bridge.mountCell("A", STUB_RENDERER);
        bridge.setCellVisible("A", false);
        assertThat(mounter.visibilityCalls).hasSize(1);
        assertThat(mounter.visibilityCalls.get(0).visible()).isFalse();
    }

    @Test
    void setVisibleOnUnmountedCellIsIgnored() {
        bridge.setCellVisible("ghost", true);
        assertThat(mounter.visibilityCalls).isEmpty();
    }

    @Test
    void mountedCellIdsSnapshot() {
        bridge.mountCell("A", STUB_RENDERER);
        bridge.mountCell("B", STUB_RENDERER);
        assertThat(bridge.mountedCellIds()).containsExactly("A", "B");
        bridge.unmountCell("A");
        assertThat(bridge.mountedCellIds()).containsExactly("B");
    }

    @Test
    void mountedCellIdsIsUnmodifiableSnapshot() {
        bridge.mountCell("A", STUB_RENDERER);
        assertThatThrownBy(() -> bridge.mountedCellIds().add("C"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unmountAll() {
        bridge.mountCell("A", STUB_RENDERER);
        bridge.mountCell("B", STUB_RENDERER);
        bridge.unmountAll();
        assertThat(mounter.unmountCalls).containsExactly("A", "B");
        assertThat(bridge.mountedCellIds()).isEmpty();
    }

    @Test
    void isMountedNullIsFalse() {
        assertThat(bridge.isMounted(null)).isFalse();
    }

    // ---- helpers -------------------------------------------------------------

    /**
     * A recording {@link FrameMounter} that captures the cell ids and transform
     * feeds it is handed, without constructing any Jogamp scene-graph object.
     * The {@link CellRenderer} argument is never dereferenced.
     */
    private static final class RecordingMounter implements FrameMounter {
        final List<String> mountCalls = new ArrayList<>();
        final List<String> unmountCalls = new ArrayList<>();
        final List<TransformCall> transformCalls = new ArrayList<>();
        final List<VisibilityCall> visibilityCalls = new ArrayList<>();

        @Override
        public void mount(String cellId, CellRenderer renderer) {
            mountCalls.add(cellId);
        }

        @Override
        public void unmount(String cellId) {
            unmountCalls.add(cellId);
        }

        @Override
        public void applyTransform(String cellId, double[] rowMajorFeed) {
            transformCalls.add(new TransformCall(cellId, rowMajorFeed));
        }

        @Override
        public void setVisible(String cellId, boolean visible) {
            visibilityCalls.add(new VisibilityCall(cellId, visible));
        }
    }

    private record TransformCall(String cellId, double[] feed) {}
    private record VisibilityCall(String cellId, boolean visible) {}
}
