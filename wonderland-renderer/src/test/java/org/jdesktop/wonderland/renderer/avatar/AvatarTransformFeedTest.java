/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M6b: how the composed pose matrix folds base + goto +
 * collision.
 *
 * This is the M6b half of M6a's WorldTransformMath: given the same
 * row-major double[16] output contract, the avatar adds two orthogonal
 * decisions on top -- goto replaces the base entirely, and the collision
 * height is added to translation-Y (index 7 in the vecmath convention that
 * WorldTransformMath.translationOf documents). The probe's Transform3D
 * receives whatever this method returns, so the whole numeric surface has to
 * be pinned here in the headless JVM.
 */
package org.jdesktop.wonderland.renderer.avatar;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class AvatarTransformFeedTest {

    @Test
    void aNullStateAndNullBaseYieldsTheIdentity() {
        double[] row = AvatarTransformFeed.compose(null, null);

        assertThat(row).hasSize(16);
        assertThat(row[0]).isEqualTo(1.0);
        assertThat(row[5]).isEqualTo(1.0);
        assertThat(row[10]).isEqualTo(1.0);
        assertThat(row[15]).isEqualTo(1.0);
        assertThat(row[3]).isZero();
        assertThat(row[7]).isZero();
        assertThat(row[11]).isZero();
    }

    @Test
    void aFreshStateWithNoCollisionAndNoGotoMirrorsTheBaseCellTransform() {
        // The pass-through case: the composition adds nothing, so the output
        // is exactly what WorldTransformMath.fromCellTransform produced for
        // the base. Pinning this proves the avatar feed does not silently
        // perturb an untriggered pose.
        CellTransform base = new CellTransform();
        base.setTranslation(new Vector3f(1f, 2f, 3f));
        AvatarTriggerState state = new AvatarTriggerState();

        double[] viaFeed = AvatarTransformFeed.compose(base, state);
        double[] viaBase = org.jdesktop.wonderland.renderer.transform
                .WorldTransformMath.fromCellTransform(base);

        assertThat(viaFeed).containsExactly(viaBase, offset(1e-9));
    }

    @Test
    void aPositiveCollisionHeightLiftsTranslationYOnly() {
        // The vecmath column convention: index 7 is m13 (row 1, col 3), the
        // Y component of the translation. X and Z translations must not move.
        CellTransform base = new CellTransform();
        base.setTranslation(new Vector3f(1f, 2f, 3f));
        AvatarTriggerState state = new AvatarTriggerState();
        state.applyCollision(0.5f, true);

        double[] row = AvatarTransformFeed.compose(base, state);

        assertThat(row[3]).isCloseTo(1.0, offset(1e-9));
        assertThat(row[7]).isCloseTo(2.5, offset(1e-9));
        assertThat(row[11]).isCloseTo(3.0, offset(1e-9));
    }

    @Test
    void aNegativeCollisionHeightSinksTranslationY() {
        // A peer under a low lintel -- the state class documents the sign
        // pass-through, the feed test asserts it survives the fold.
        CellTransform base = new CellTransform();
        base.setTranslation(new Vector3f(0f, 1f, 0f));
        AvatarTriggerState state = new AvatarTriggerState();
        state.applyCollision(-0.25f, true);

        double[] row = AvatarTransformFeed.compose(base, state);

        assertThat(row[7]).isCloseTo(0.75, offset(1e-9));
    }

    @Test
    void turningCollisionOffAfterAnOnLeavesTheBasePoseUntouched() {
        CellTransform base = new CellTransform();
        base.setTranslation(new Vector3f(0f, 3f, 0f));
        AvatarTriggerState state = new AvatarTriggerState();
        state.applyCollision(2.0f, true);
        state.applyCollision(2.0f, false);

        double[] row = AvatarTransformFeed.compose(base, state);

        assertThat(row[7]).isCloseTo(3.0, offset(1e-9));
    }

    @Test
    void aGotoOverrideReplacesTheBasePoseEntirely() {
        // The plan's "goto short-circuits the base" rule: even when the cell
        // is at (99, 99, 99), a goto to (1, 2, 3) lands at (1, 2, 3). The
        // base's rotation and translation are both dropped because the feed
        // rebuilds from the goto quaternion (null -> identity) and the goto
        // position, not from the base.
        CellTransform base = new CellTransform();
        base.setTranslation(new Vector3f(99f, 99f, 99f));
        AvatarTriggerState state = new AvatarTriggerState();
        state.applyGoto(new Vector3f(1f, 2f, 3f), null);

        double[] row = AvatarTransformFeed.compose(base, state);

        assertThat(row[3]).isCloseTo(1.0, offset(1e-9));
        assertThat(row[7]).isCloseTo(2.0, offset(1e-9));
        assertThat(row[11]).isCloseTo(3.0, offset(1e-9));
        // a null look means identity rotation, so the diagonal is 1 and the
        // off-diagonal rotation entries are 0.
        assertThat(row[0]).isCloseTo(1.0, offset(1e-9));
        assertThat(row[5]).isCloseTo(1.0, offset(1e-9));
        assertThat(row[10]).isCloseTo(1.0, offset(1e-9));
        assertThat(row[1]).isCloseTo(0.0, offset(1e-9));
    }

    @Test
    void aGotoWithANonNullLookRotatesThePoseAroundTheGotoPosition() {
        // A 90-degree rotation about Y: the identity x-axis column becomes
        // the -Z direction. The translation stays at the goto position --
        // rotation is about the local origin, not the world origin, so the
        // pose lands where the server said.
        CellTransform base = new CellTransform();
        base.setTranslation(new Vector3f(50f, 50f, 50f));
        AvatarTriggerState state = new AvatarTriggerState();
        Quaternion look = new Quaternion();
        look.fromAngleAxis((float) (Math.PI / 2.0), new Vector3f(0f, 1f, 0f));
        state.applyGoto(new Vector3f(0f, 1f, 0f), look);

        double[] row = AvatarTransformFeed.compose(base, state);

        // After a 90-degree rotation about Y, vecmath's row-major layout has
        // m00 = cos(pi/2) = 0 and m02 = sin(pi/2) = 1. The Y translation is
        // the goto position's y (the identity look rotation does not affect
        // the last column).
        assertThat(row[0]).isCloseTo(0.0, offset(1e-6));
        assertThat(row[2]).isCloseTo(1.0, offset(1e-6));
        assertThat(row[7]).isCloseTo(1.0, offset(1e-6));
    }

    @Test
    void aGotoCombinedWithCollisionStillLiftsTranslationY() {
        // Both signals alive: the goto wins over the base pose for X/Z, and
        // the collision height is then ADDED to the goto position's Y. This
        // is the plan's rule 3 -- the offset survives both branches.
        AvatarTriggerState state = new AvatarTriggerState();
        state.applyGoto(new Vector3f(4f, 5f, 6f), null);
        state.applyCollision(0.5f, true);

        double[] row = AvatarTransformFeed.compose(null, state);

        assertThat(row[3]).isCloseTo(4.0, offset(1e-9));
        assertThat(row[7]).isCloseTo(5.5, offset(1e-9));
        assertThat(row[11]).isCloseTo(6.0, offset(1e-9));
    }

    @Test
    void aNullStateBehavesLikeAFreshStateOnTheBasePose() {
        // A caller (a unit test, or a probe reading a partially-built scene)
        // that passes null state gets the plain base transform, not a crash.
        CellTransform base = new CellTransform();
        base.setTranslation(new Vector3f(2f, 3f, 4f));

        double[] row = AvatarTransformFeed.compose(base, null);

        assertThat(row[3]).isCloseTo(2.0, offset(1e-9));
        assertThat(row[7]).isCloseTo(3.0, offset(1e-9));
        assertThat(row[11]).isCloseTo(4.0, offset(1e-9));
    }
}
