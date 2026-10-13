/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-desktop M6c2: headless tests for the pick / input routing rule.
 *
 * The router is the pure half of the desktop's input path: it never picks, it
 * decides. The HitSource seam carries the Java 3D PickResult in the live desktop,
 * and here it is a recorded list of cell ids -- so every branch of "the nearest
 * mounted, shown cell that asked for this gesture" is asserted in CI.
 */
package org.jdesktop.wonderland.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.jdesktop.wonderland.client.cell.CellRenderer;
import org.jdesktop.wonderland.common.cell.CellStatus;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link PickRouter}: the hit-list walk, the three-part
 * filter, and the interest registry.
 */
class PickRouterTest {

    /** The bridge never asks the renderer anything; the stub is only non-null. */
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

    /** A HitSource stub that replays one list, and records the coordinates. */
    private static final class ScriptedHits implements HitSource {
        private final List<String> cells;
        private final boolean throwOnAsk;
        private double lastX;
        private double lastY;
        private int asks;

        ScriptedHits(String... cells) {
            this(Arrays.asList(cells), false);
        }

        ScriptedHits(List<String> cells, boolean throwOnAsk) {
            this.cells = cells;
            this.throwOnAsk = throwOnAsk;
        }

        @Override
        public List<String> cellsUnder(double screenX, double screenY) {
            asks++;
            lastX = screenX;
            lastY = screenY;
            if (throwOnAsk) {
                throw new IllegalStateException("canvas torn down mid-pick");
            }
            return cells;
        }
    }

    private CellFrameBridge bridge;

    @BeforeEach
    void setUp() {
        // The router never reaches the scene graph: it only asks the bridge what
        // is mounted and shown, so the mounter here is a silent stand-in.
        bridge = new CellFrameBridge(new FrameMounter() {
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
            }
        });
    }

    @Test
    void nullBridgeIsRejected() {
        assertThatThrownBy(() -> new PickRouter(null, new ScriptedHits("A")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nearestInterestedCellWins() {
        bridge.mountCell("near", STUB_RENDERER);
        bridge.mountCell("far", STUB_RENDERER);
        PickRouter router = new PickRouter(bridge,
                new ScriptedHits("near", "far"));
        router.register("far", PickIntent.SELECT);
        router.register("near", PickIntent.SELECT);

        PickDecision decision = router.route(12.0, 34.0, PickIntent.SELECT);

        assertThat(decision.cellId()).isEqualTo("near");
        assertThat(decision.consumed()).isTrue();
        assertThat(decision.intent()).isEqualTo(PickIntent.SELECT);
    }

    @Test
    void aCloserCellWithoutInterestIsSkippedAndTheNextCellClaimsTheGesture() {
        bridge.mountCell("decor", STUB_RENDERER);
        bridge.mountCell("widget", STUB_RENDERER);
        PickRouter router = new PickRouter(bridge,
                new ScriptedHits("decor", "widget"));
        router.register("widget", PickIntent.HOVER);

        assertThat(router.route(0, 0, PickIntent.HOVER).cellId()).isEqualTo("widget");
        assertThat(router.route(0, 0, PickIntent.SELECT).consumed()).isFalse();
    }

    @Test
    void aHiddenCellCannotBePickedEvenWhenItIsInterested() {
        bridge.mountCell("A", STUB_RENDERER);
        PickRouter router = new PickRouter(bridge, new ScriptedHits("A"));
        router.register("A", PickIntent.DRAG);
        assertThat(router.route(0, 0, PickIntent.DRAG).cellId()).isEqualTo("A");

        bridge.setCellVisible("A", false);

        PickDecision decision = router.route(0, 0, PickIntent.DRAG);
        assertThat(decision.cellId()).isNull();
        assertThat(decision.consumed()).isFalse();
    }

    @Test
    void anUnmountedHitIsIgnored() {
        PickRouter router = new PickRouter(bridge, new ScriptedHits("ghost"));
        router.register("ghost", PickIntent.SELECT);

        assertThat(router.route(0, 0, PickIntent.SELECT).consumed()).isFalse();
    }

    @Test
    void emptySpaceIsAPassthroughAndTheSourceIsAskedOnce() {
        ScriptedHits hits = new ScriptedHits(Collections.<String>emptyList(), false);
        PickRouter router = new PickRouter(bridge, hits);

        assertThat(router.route(7.0, 9.0, PickIntent.SELECT).consumed()).isFalse();
        assertThat(hits.asks).isEqualTo(1);
        assertThat(hits.lastX).isEqualTo(7.0);
        assertThat(hits.lastY).isEqualTo(9.0);
    }

    @Test
    void noHitSourceMeansEveryGestureStaysWithTheDesktop() {
        PickRouter router = new PickRouter(bridge, null);
        router.register("A", PickIntent.SELECT);

        assertThat(router.route(0, 0, PickIntent.SELECT).consumed()).isFalse();
    }

    @Test
    void theEventDrivenConstructorRoutesAHandedOverHitList() {
        // lg3d picks, the desktop hands over the cells: this is the shape the
        // world layer uses, so its passthrough-for-every-point answer is the
        // documented one, not a silent failure.
        bridge.mountCell("A", STUB_RENDERER);
        PickRouter router = new PickRouter(bridge);
        router.register("A", PickIntent.SELECT);

        assertThat(router.route(0, 0, PickIntent.SELECT).consumed()).isFalse();
        assertThat(router.routeHits(List.of("A"), PickIntent.SELECT).cellId())
                .isEqualTo("A");
    }

    @Test
    void aPickFailureOnATornDownCanvasIsAPassthroughNotAnException() {
        bridge.mountCell("A", STUB_RENDERER);
        PickRouter router = new PickRouter(bridge,
                new ScriptedHits(Collections.emptyList(), true));
        router.register("A", PickIntent.SELECT);

        assertThat(router.route(0, 0, PickIntent.SELECT).consumed()).isFalse();
    }

    @Test
    void nullIntentAndNullHitsAreBothPassthroughs() {
        PickRouter router = new PickRouter(bridge, new ScriptedHits("A"));

        assertThat(router.route(0, 0, null).consumed()).isFalse();
        assertThat(router.routeHits(null, PickIntent.SELECT).consumed()).isFalse();
    }

    @Test
    void nullEntriesInTheHitListAreSteppedOver() {
        bridge.mountCell("A", STUB_RENDERER);
        PickRouter router = new PickRouter(bridge, new ScriptedHits(null, "A"));
        router.register("A", PickIntent.GOTO);

        assertThat(router.routeHits(Arrays.asList(null, "A"), PickIntent.GOTO)
                .cellId()).isEqualTo("A");
        assertThat(router.route(0, 0, PickIntent.GOTO).cellId()).isEqualTo("A");
    }

    @Test
    void registeringTheSameCellTwiceAddsItsIntents() {
        bridge.mountCell("A", STUB_RENDERER);
        PickRouter router = new PickRouter(bridge, new ScriptedHits("A"));
        router.register("A", PickIntent.SELECT);
        router.register("A", PickIntent.DRAG);

        assertThat(router.isInterested("A", PickIntent.SELECT)).isTrue();
        assertThat(router.isInterested("A", PickIntent.DRAG)).isTrue();
        assertThat(router.isInterested("A", PickIntent.HOVER)).isFalse();
    }

    @Test
    void unregisterStopsRoutingToTheCell() {
        bridge.mountCell("A", STUB_RENDERER);
        PickRouter router = new PickRouter(bridge, new ScriptedHits("A"));
        router.register("A", PickIntent.SELECT);
        router.unregister("A");

        assertThat(router.route(0, 0, PickIntent.SELECT).consumed()).isFalse();
        assertThat(router.registeredCells()).isEmpty();
    }

    @Test
    void aReloadKeepsTheDeclaredIntents() {
        bridge.mountCell("A", STUB_RENDERER);
        PickRouter router = new PickRouter(bridge, new ScriptedHits("A"));
        router.register("A", PickIntent.SELECT);

        bridge.unmountCell("A");
        assertThat(router.route(0, 0, PickIntent.SELECT).consumed()).isFalse();

        bridge.mountCell("A", STUB_RENDERER);
        assertThat(router.route(0, 0, PickIntent.SELECT).cellId()).isEqualTo("A");
    }

    @Test
    void clearDropsEveryInterest() {
        bridge.mountCell("A", STUB_RENDERER);
        bridge.mountCell("B", STUB_RENDERER);
        PickRouter router = new PickRouter(bridge, new ScriptedHits("A", "B"));
        router.register("A", PickIntent.SELECT);
        router.register("B", PickIntent.SELECT);
        assertThat(router.registeredCells()).containsExactly("A", "B");

        router.clear();

        assertThat(router.registeredCells()).isEmpty();
        assertThat(router.route(0, 0, PickIntent.SELECT).consumed()).isFalse();
    }

    @Test
    void registeringWithoutACellIdOrAnIntentIsRejected() {
        PickRouter router = new PickRouter(bridge, new ScriptedHits("A"));

        assertThatThrownBy(() -> router.register(null, PickIntent.SELECT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> router.register("", PickIntent.SELECT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> router.register("A"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> router.register("A", (PickIntent[]) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> router.register("A", (PickIntent) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unregisteringAnUnknownCellIsHarmless() {
        PickRouter router = new PickRouter(bridge, new ScriptedHits("A"));
        router.register("A", PickIntent.SELECT);

        router.unregister(null);
        router.unregister("ghost");

        assertThat(router.registeredCells()).containsExactly("A");
    }
}
