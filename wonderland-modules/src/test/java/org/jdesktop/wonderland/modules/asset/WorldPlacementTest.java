/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules (assetmgr): the placement arithmetic that put the meeting
 * complex back on its own ground.
 *
 * WorldPlacement folds a world's parent-relative cell transforms into world
 * coordinates. That multiplication order -- stretch, then turn, then move, a
 * child measured through its parent -- is the whole feature, and every branch of
 * its tolerance (a missing parent, a cycle, a self-parent, an absent <scale>) is a
 * shipped world that used to render as an empty blue field. The frames prove the
 * worlds look right; these prove the numbers are right and that a half-broken tree
 * degrades to flat placement rather than hanging or collapsing to a point.
 */
package org.jdesktop.wonderland.modules.asset;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import java.util.List;
import java.util.Map;
import org.jdesktop.wonderland.common.cell.state.CellServerState;
import org.jdesktop.wonderland.common.cell.state.ForeignCellServerState;
import org.jdesktop.wonderland.common.cell.state.PositionComponentServerState;
import org.jdesktop.wonderland.modules.asset.WorldPlacement.Absolute;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import org.junit.jupiter.api.Test;

class WorldPlacementTest {

    private static final float EPS = 1e-4f;

    /** A cell whose file records the given translation, optional rotation and scale. */
    private static WorldPlacement.Cell cell(String path, String parent,
            Vector3f translation, Quaternion rotation, Vector3f scale) {
        CellServerState state = new ForeignCellServerState();
        PositionComponentServerState position = new PositionComponentServerState();
        if (translation != null) {
            position.setTranslation(translation);
        }
        if (rotation != null) {
            position.setRotation(rotation);
        }
        if (scale != null) {
            position.setScaling(scale);
        }
        state.addComponentServerState(position);
        return new WorldPlacement.Cell(path, parent, state);
    }

    private static WorldPlacement.Cell at(String path, String parent, float x, float y, float z) {
        return cell(path, parent, new Vector3f(x, y, z), null, null);
    }

    private static void assertVec(Vector3f actual, float x, float y, float z) {
        assertThat(actual.x).isCloseTo(x, within(EPS));
        assertThat(actual.y).isCloseTo(y, within(EPS));
        assertThat(actual.z).isCloseTo(z, within(EPS));
    }

    // ------------------------------------------------------------- scaling ----
    @Test
    void anAbsentScaleIsTheIdentityNotACollapse() {
        // A missing <scale> read as (0,0,0) makes a degenerate matrix Java 3D
        // refuses at attach time -- one empty element costing the world everything.
        assertVec(WorldPlacement.scalingOf(null), 1f, 1f, 1f);
        PositionComponentServerState noScale = new PositionComponentServerState();
        assertVec(WorldPlacement.scalingOf(noScale), 1f, 1f, 1f);
        noScale.setScaling(new Vector3f(2f, 3f, 4f));
        assertVec(WorldPlacement.scalingOf(noScale), 2f, 3f, 4f);
    }

    // -------------------------------------------------------------- local ----
    @Test
    void aCellWithNoPositionComponentContributesTheIdentity() {
        Absolute identity = WorldPlacement.localOf(new ForeignCellServerState());
        assertVec(identity.translation(), 0f, 0f, 0f);
        assertVec(identity.scale(), 1f, 1f, 1f);
        assertVec(WorldPlacement.localOf(null).translation(), 0f, 0f, 0f);
    }

    @Test
    void localOfReadsTheAuthoredPlacementVerbatim() {
        Absolute local = WorldPlacement.localOf(
                cell("p", null, new Vector3f(3f, 4f, 5f), null, new Vector3f(2f, 2f, 2f)).state());
        assertVec(local.translation(), 3f, 4f, 5f);
        assertVec(local.scale(), 2f, 2f, 2f);
    }

    @Test
    void aPositionWithNoTranslationOrTurnReadsAsTheDefaultOnThoseAxes() {
        // A <position-component> whose translation/rotation adapters produced null
        // (a fragment the author left empty): localOf must fall back to zero and
        // identity rather than propagate a null into the compose.
        CellServerState state = new ForeignCellServerState();
        PositionComponentServerState position = new PositionComponentServerState();
        position.setTranslation(null);
        position.setRotation(null);
        position.setScaling(new Vector3f(2f, 2f, 2f));
        state.addComponentServerState(position);
        Absolute local = WorldPlacement.localOf(state);
        assertVec(local.translation(), 0f, 0f, 0f);
        assertVec(local.scale(), 2f, 2f, 2f);
        assertThat(WorldPlacement.yawOf(local)).isEqualTo(0.0);
    }

    // ------------------------------------------------------------ compose ----
    @Test
    void aChildIsStretchedAndTurnedByItsParentBeforeItIsMoved() {
        // parent: scale 2, no turn, at (10,0,0); child at (5,0,0) -> (20,0,0):
        // the child's 5 is doubled by the parent's stretch, then added to 10.
        Absolute parent = new Absolute(new Vector3f(10f, 0f, 0f), new Quaternion(),
                new Vector3f(2f, 2f, 2f));
        Absolute child = new Absolute(new Vector3f(5f, 0f, 0f), new Quaternion(),
                new Vector3f(1f, 1f, 1f));
        assertVec(WorldPlacement.compose(parent, child).translation(), 20f, 0f, 0f);
    }

    @Test
    void aParentTurnRotatesTheChildOffItsAuthoredAxis() {
        // parent turned 90 deg about +Y at the origin; child authored at +X (5,0,0)
        // lands at -Z, and the composed heading is the parent's.
        Quaternion turnY = new Quaternion().fromAngleAxis((float) (Math.PI / 2),
                new Vector3f(0f, 1f, 0f));
        Absolute parent = new Absolute(new Vector3f(), turnY, new Vector3f(1f, 1f, 1f));
        Absolute child = new Absolute(new Vector3f(5f, 0f, 0f), new Quaternion(),
                new Vector3f(1f, 1f, 1f));
        Absolute composed = WorldPlacement.compose(parent, child);
        assertVec(composed.translation(), 0f, 0f, -5f);
        assertThat(WorldPlacement.yawOf(composed)).isCloseTo(Math.PI / 2, within(1e-3));
    }

    @Test
    void scalesMultiplyDownTheChain() {
        Absolute parent = new Absolute(new Vector3f(), new Quaternion(), new Vector3f(2f, 2f, 2f));
        Absolute child = new Absolute(new Vector3f(), new Quaternion(), new Vector3f(3f, 1f, 1f));
        assertVec(WorldPlacement.compose(parent, child).scale(), 6f, 2f, 2f);
    }

    // ------------------------------------------------------------- resolve ----
    @Test
    void anEmptyWorldResolvesToNothing() {
        assertThat(WorldPlacement.resolve(List.of())).isEmpty();
    }

    @Test
    void aRootCellIsPlacedByItsOwnNumbers() {
        Map<String, Absolute> out = WorldPlacement.resolve(
                List.of(at("root", null, 7f, 0f, 3f)));
        assertVec(out.get("root").translation(), 7f, 0f, 3f);
    }

    @Test
    void aChildLandsWhereItsParentPutIt() {
        // The meeting-complex case: a field at x=32 inside a complex at x=-38.
        List<WorldPlacement.Cell> cells = List.of(
                at("complex", null, -38f, 0f, -53f),
                at("field", "complex", 32f, 0f, 75f));
        Map<String, Absolute> out = WorldPlacement.resolve(cells);
        assertVec(out.get("field").translation(), -6f, 0f, 22f);
    }

    @Test
    void aMissingParentDegradesToFlatPlacement() {
        // A half-read world is no worse than before: the orphan is placed by its
        // own numbers, exactly what the old flat seeding did.
        Map<String, Absolute> out = WorldPlacement.resolve(
                List.of(at("orphan", "absent-parent", 4f, 5f, 6f)));
        assertVec(out.get("orphan").translation(), 4f, 5f, 6f);
    }

    @Test
    void aSelfParentIsTreatedAsARoot() {
        Map<String, Absolute> out = WorldPlacement.resolve(
                List.of(at("loop", "loop", 1f, 2f, 3f)));
        assertVec(out.get("loop").translation(), 1f, 2f, 3f);
    }

    @Test
    void aCycleIsBrokenAtTheRepeatRatherThanHangingTheLoader() {
        // A<->B: neither is a real root, but the walk must terminate and keep the
        // twenty good cells around them. It does -- the repeat returns identity.
        List<WorldPlacement.Cell> cells = List.of(
                at("A", "B", 1f, 0f, 0f),
                at("B", "A", 0f, 1f, 0f),
                at("fine", null, 9f, 9f, 9f));
        Map<String, Absolute> out = WorldPlacement.resolve(cells);
        assertThat(out).containsKeys("A", "B", "fine");
        assertVec(out.get("fine").translation(), 9f, 9f, 9f);
        // A is B folded under itself with the cycle cut: finite, not NaN.
        assertThat(Float.isNaN(out.get("A").translation().x)).isFalse();
    }

    @Test
    void aNullAndPathlessCellAreSkipped() {
        Map<String, Absolute> out = WorldPlacement.resolve(
                java.util.Arrays.asList(null,
                        new WorldPlacement.Cell(null, null, new ForeignCellServerState())));
        assertThat(out).isEmpty();
    }

    // --------------------------------------------------------------- yaw ----
    @Test
    void yawOfNullAndIdentityAreZero() {
        assertThat(WorldPlacement.yawOf(null)).isEqualTo(0.0);
        assertThat(WorldPlacement.yawOf(Absolute.identity())).isEqualTo(0.0);
    }

    @Test
    void yawOfADegenerateQuaternionIsZeroNotNaN() {
        Absolute zero = new Absolute(new Vector3f(), new Quaternion(0f, 0f, 0f, 0f),
                new Vector3f(1f, 1f, 1f));
        assertThat(WorldPlacement.yawOf(zero)).isEqualTo(0.0);
    }

    @Test
    void yawReadsTheHeadingOffAVerticalTurn() {
        Quaternion q = new Quaternion().fromAngleAxis((float) Math.toRadians(30),
                new Vector3f(0f, 1f, 0f));
        Absolute turned = new Absolute(new Vector3f(), q, new Vector3f(1f, 1f, 1f));
        assertThat(WorldPlacement.yawOf(turned)).isCloseTo(Math.toRadians(30), within(1e-3));
    }

    // ---------------------------------------------------------- cellsOf ----
    @Test
    void cellsOfCarriesPathParentAndStateThrough() {
        CellServerState state = new ForeignCellServerState();
        WorldContents.LoadedCell loaded =
                new WorldContents.LoadedCell(state, null, "a/b.xml", "a.xml");
        List<WorldPlacement.Cell> cells = WorldPlacement.cellsOf(List.of(loaded));
        assertThat(cells).hasSize(1);
        assertThat(cells.get(0).path()).isEqualTo("a/b.xml");
        assertThat(cells.get(0).parentPath()).isEqualTo("a.xml");
        assertThat(cells.get(0).state()).isSameAs(state);
    }
}
