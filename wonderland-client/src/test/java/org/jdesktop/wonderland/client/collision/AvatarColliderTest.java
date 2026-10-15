/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
package org.jdesktop.wonderland.client.collision;

import com.jme.math.Quaternion;
import com.jme.math.Vector3f;
import java.util.List;
import org.jdesktop.wonderland.common.cell.CellTransform;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1.1: the stateful clamp adapter over real {@link CellTransform}s. Proves the
 * two properties the move path depends on: an unregistered world passes every
 * request straight through, and a registered wall stops the walker while
 * preserving the height and rotation it asked to keep.
 */
class AvatarColliderTest {

    private static CellTransform at(float x, float y, float z) {
        return new CellTransform(new Quaternion(), new Vector3f(x, y, z));
    }

    @Test
    void noObstaclesMeansNoClampAndTheWalkerTracksItsRequestedSpot() {
        AvatarCollider collider = new AvatarCollider(0f, 0f);
        assertThat(collider.isEmpty()).isTrue();
        CellTransform move = at(100f, 1.5f, -30f);
        assertThat(collider.clamp(move)).isNull();
        assertThat(move.getTranslation(null).x).isEqualTo(100f);
        assertThat(collider.x()).isEqualTo(100f);
        assertThat(collider.z()).isEqualTo(-30f);
    }

    @Test
    void aWallStopsTheHorizontalMoveButKeepsHeightAndRememberedPosition() {
        AvatarCollider collider = new AvatarCollider(0f, 0f);
        collider.setObstacles(List.of(Obstacle.of("wall", 5f, 0.85f, 0f, 0.5f, 0.85f, 2f)));

        CellTransform move = at(6f, 1.2f, 0f); // straight at the wall
        String hit = collider.clamp(move);

        assertThat(hit).isEqualTo("wall");
        // Swept, so the walker lands tangent to the near face (x=4.15), not frozen
        // at the start and never past it.
        assertThat(move.getTranslation(null).x).isEqualTo(4.15f);
        assertThat(move.getTranslation(null).y).isEqualTo(1.2f); // height untouched
        assertThat(collider.x()).isEqualTo(4.15f);
    }

    @Test
    void successiveStepsAccumulateFromTheLastLegalPosition() {
        AvatarCollider collider = new AvatarCollider(0f, 0f);
        collider.setObstacles(List.of(Obstacle.of("wall", 5f, 0.85f, 0f, 0.5f, 0.85f, 2f)));
        // First nudge is legal (short of the grown face at x=4.15).
        collider.clamp(at(4f, 0f, 0f));
        assertThat(collider.x()).isEqualTo(4f);
        // From there a hard shove into the wall is refused at the face; the walker
        // advances only to 4.15, not to 9.
        collider.clamp(at(9f, 0f, 0f));
        assertThat(collider.x()).isEqualTo(4.15f);
    }
}
