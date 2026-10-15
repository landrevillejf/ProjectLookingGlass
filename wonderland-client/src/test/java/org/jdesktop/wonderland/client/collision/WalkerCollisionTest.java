/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
package org.jdesktop.wonderland.client.collision;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1.1: the collision decision the 2006 stub never made, proven headless. The
 * whole phase claim is that a walker can no longer move through a wall and that
 * an empty world behaves exactly as before -- both are asserted here.
 */
class WalkerCollisionTest {

    private static List<Obstacle> wallAtX5() {
        // A 1m-tall, 4m-wide wall centred at x=5, spanning z=-2..2, y=0..1.7.
        return List.of(Obstacle.of("wall", 5f, 0.85f, 0f, 0.5f, 0.85f, 2f));
    }

    @Test
    void anEmptyWorldReturnsTheRequestedPosition() {
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(0, 0, 10, 10, List.of(), 0.35f, 0f, 1.7f);
        assertThat(r.x()).isEqualTo(10f);
        assertThat(r.z()).isEqualTo(10f);
        assertThat(r.blocked()).isFalse();
        assertThat(r.hitId()).isNull();
    }

    @Test
    void aNullWorldIsTreatedAsEmptySoThePreP1StubPathIsPreserved() {
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(0, 0, 3, 4, null, 0.35f, 0f, 1.7f);
        assertThat(r.blocked()).isFalse();
        assertThat(r.x()).isEqualTo(3f);
        assertThat(r.z()).isEqualTo(4f);
    }

    @Test
    void aStepIntoAWallLandsTangentToItsFaceRatherThanPassingThrough() {
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(0, 0, 5, 0, wallAtX5(), 0.35f, 0f, 1.7f);
        assertThat(r.blocked()).isTrue();
        assertThat(r.hitId()).isEqualTo("wall");
        // The wall's near face (x=4.5) grown by the 0.35 radius stops the walker
        // tangent to it at x=4.15, not at the start and never past it.
        assertThat(r.x()).isEqualTo(4.15f);
    }

    @Test
    void aLongStepCannotTunnelThroughAThinWall() {
        // The defect the swept rewrite kills: asking to land on the far side of
        // a thin wall in one step used to read "clear" because only the endpoint
        // was tested. The whole segment is judged now, so it is refused.
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(0, 0, 100, 0, wallAtX5(), 0.35f, 0f, 1.7f);
        assertThat(r.blocked()).isTrue();
        assertThat(r.x()).isEqualTo(4.15f); // stopped at the face, not teleported to 100
    }

    @Test
    void aStepParallelToAWallSlidesRatherThanStopping() {
        // Start already beside the wall at x=4 (clear of it), move only along z.
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(4f, 0f, 4f, 1.5f, wallAtX5(), 0.35f, 0f, 1.7f);
        assertThat(r.blocked()).isFalse();
        assertThat(r.z()).isEqualTo(1.5f);
    }

    @Test
    void furnitureLowEnoughToStepOverDoesNotBlockAStandingPoseAtTableHeight() {
        // A 0.4m-tall stool; a walker whose feet are at 0.5m (already above it)
        // is not stopped by it, because the band misses the body.
        List<Obstacle> stool = List.of(Obstacle.of("stool", 5f, 0.2f, 0f, 0.5f, 0.2f, 0.5f));
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(0, 0, 5, 0, stool, 0.35f, 0.5f, 2.2f);
        assertThat(r.blocked()).isFalse();
    }

    @Test
    void theDiscRadiusNosesIntoTheCornerEarlyInTheWalkersFavor() {
        List<Obstacle> world = wallAtX5();
        // The wall's left face is x=4.5; grown by the 0.35 disc radius the
        // blocking edge sits at x=4.15. Standing short of it is free...
        assertThat(WalkerCollision.clear(4.0f, 0f, world, 0.35f, 0f, 1.7f)).isTrue();
        // ...and reaching past 4.15 is refused before the body would touch it.
        assertThat(WalkerCollision.clear(4.2f, 0f, world, 0.35f, 0f, 1.7f)).isFalse();
    }
}
