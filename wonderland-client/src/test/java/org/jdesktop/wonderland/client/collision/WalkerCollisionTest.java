/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
package org.jdesktop.wonderland.client.collision;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
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

    @Test
    void aLaneOfTwoWallsRefusesAtTheNearOneNotTheFarOne() {
        // The multi-obstacle swept contract: the FIRST face met stops the
        // walker. Landing on the far wall's face would be a tunnel through
        // the near one -- the same defect the single-wall gate above kills,
        // one box further along. Registration order tests the chooser from
        // both directions: the far wall arrives first here.
        Obstacle far = Obstacle.of("farwall", 8f, 0.85f, 0f, 0.5f, 0.85f, 2f);
        Obstacle near = Obstacle.of("wall", 5f, 0.85f, 0f, 0.5f, 0.85f, 2f);
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(0, 0, 9, 0, List.of(far, near), 0.35f, 0f, 1.7f);
        assertThat(r.blocked()).isTrue();
        assertThat(r.hitId()).isEqualTo("wall");
        assertThat(r.x()).isEqualTo(4.15f);
    }

    @Test
    void walkingBackwardStopsAtTheFaceNearestTheWalker() {
        // The -axis travel: the walker east of the wall (grown 4.15..5.85)
        // stepping west lands tangent to its far face, never past it.
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(10, 0, 0, 0, wallAtX5(), 0.35f, 0f, 1.7f);
        assertThat(r.blocked()).isTrue();
        assertThat(r.hitId()).isEqualTo("wall");
        assertThat(r.x()).isEqualTo(5.85f);
    }

    @Test
    void aBackwardStepAlongZSweepsTheOtherAxisToo() {
        // Travelling -z down the wall's own lane (x=5 is inside its grown
        // x-range): the z sweep refuses at z = 2 + 0.35.
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(5, 10, 5, 0, wallAtX5(), 0.35f, 0f, 1.7f);
        assertThat(r.blocked()).isTrue();
        assertThat(r.z()).isEqualTo(2.35f);
        assertThat(r.hitId()).isEqualTo("wall");
    }

    @Test
    void aWalkerStartingInsideTheGrownBoxCanStepOutOfItRatherThanBeingWelded() {
        // The overlap escape hatch: beginning inside a box (center x=5 is
        // inside the grown 4.15..5.85) is the caller's emergency, not a
        // life sentence -- the step out is free rather than refused.
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(5, 0, 7, 0, wallAtX5(), 0.35f, 0f, 1.7f);
        assertThat(r.blocked()).isFalse();
        assertThat(r.x()).isEqualTo(7f);
    }

    @Test
    void aBoxBesideTheLaneIsNotInTheWay() {
        // Same wall, but the walker's fixed z=10 is outside its grown z-span
        // (-2.35..2.35): the lane test skips it before any face maths.
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(0, 10, 5, 10, wallAtX5(), 0.35f, 0f, 1.7f);
        assertThat(r.blocked()).isFalse();
        assertThat(r.x()).isEqualTo(5f);
    }

    @Test
    void aNullEntryInTheWorldIsSkippedNotFatal() {
        // The collection is the caller's; a hole must not decide the walk.
        List<Obstacle> holey = Arrays.asList(wallAtX5().get(0), null);
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(0, 0, 5, 0, holey, 0.35f, 0f, 1.7f);
        assertThat(r.blocked()).isTrue();
        assertThat(r.x()).isEqualTo(4.15f);
    }

    @Test
    void aBandThatMissesTheBodyIsFurnitureNotAWall() {
        // The blocksHeight false side INSIDE the sweep: a ceiling slab at
        // y 3..4 is above a 1.7 m head, so the walk passes under it.
        List<Obstacle> ceiling =
                List.of(Obstacle.of("slab", 5f, 3.5f, 0f, 0.5f, 0.5f, 2f));
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(0, 0, 9, 0, ceiling, 0.35f, 0f, 1.7f);
        assertThat(r.blocked()).isFalse();
        assertThat(r.x()).isEqualTo(9f);
    }

    @Test
    void aStepWhoseNoseJustTouchesTheGrownFaceIsNotACrossing() {
        // The segment [0, 4.15] meets the box interval only at its endpoint;
        // tangent contact is standing next to a wall, not inside one.
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(0, 0, 4.15f, 0, wallAtX5(), 0.35f, 0f, 1.7f);
        assertThat(r.blocked()).isFalse();
        assertThat(r.x()).isEqualTo(4.15f);
    }

    @Test
    void theNearWallWinsWhicheverOrderTheWorldRegisters() {
        // The chooser's rejection side: with the near wall seen FIRST, the
        // far wall's face must be refused as a candidate (a smaller bLo is
        // already the answer) -- and the verdict must not depend on the
        // cell-load order of an unordered world.
        Obstacle near = Obstacle.of("wall", 5f, 0.85f, 0f, 0.5f, 0.85f, 2f);
        Obstacle far = Obstacle.of("farwall", 8f, 0.85f, 0f, 0.5f, 0.85f, 2f);
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(0, 0, 9, 0, List.of(near, far), 0.35f, 0f, 1.7f);
        assertThat(r.blocked()).isTrue();
        assertThat(r.hitId()).isEqualTo("wall");
        assertThat(r.x()).isEqualTo(4.15f);
    }

    @Test
    void walkingBackwardMeetsTheSameLaneFromBehind() {
        // The segment [0, -10] never crosses the wall's grown interval
        // (4.15..5.85): stepping away from a wall is free, and the backward
        // chooser answers no-hit rather than welding the walker to the wall
        // they just turned away from.
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(0, 0, -10, 0, wallAtX5(), 0.35f, 0f, 1.7f);
        assertThat(r.blocked()).isFalse();
        assertThat(r.x()).isEqualTo(-10f);
    }

    @Test
    void theLargestFarFaceIsTheBackwardStopsignEvenAmongMany() {
        // Travelling -x past two walls: the FIRST face met is the EAST wall's
        // far face (8 + 0.5 + 0.35 = 8.85), not the deeper one -- and
        // registering the deep wall second must not steal the decision, the
        // mirror of the forward two-wall gate.
        Obstacle east = Obstacle.of("eastwall", 8f, 0.85f, 0f, 0.5f, 0.85f, 2f);
        Obstacle west = Obstacle.of("wall", 5f, 0.85f, 0f, 0.5f, 0.85f, 2f);
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(12, 0, 0, 0, List.of(east, west), 0.35f, 0f, 1.7f);
        assertThat(r.blocked()).isTrue();
        assertThat(r.hitId()).isEqualTo("eastwall");
        assertThat(r.x()).isEqualTo(8.85f);
    }

    @Test
    void aDiagonalIntoACornerNamesTheFirstRefusalAndSlidesIntoNeither() {
        // Both axes refused: the x answer keeps the hit name (the z answer
        // does not overwrite it -- the first wall met is what a behaviour
        // script is told about), and each axis lands at its own face.
        Obstacle wall = Obstacle.of("wall", 5f, 0.85f, 0f, 0.5f, 0.85f, 2f);
        Obstacle gate = Obstacle.of("gate", 4.7f, 0.85f, 5f, 0.5f, 0.85f, 2f);
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(0, 0, 6, 6, List.of(wall, gate), 0.35f, 0f, 1.7f);
        assertThat(r.blocked()).isTrue();
        assertThat(r.hitId()).as("the x sweep spoke first").isEqualTo("wall");
        assertThat(r.x()).isEqualTo(4.15f);
        assertThat(r.z()).isEqualTo(2.65f); // gate minZ 3 grown by the disc
    }

    @Test
    void theLaneRejectsOnBothSidesOfTheSpan() {
        // The z=+10 side was proven outside; the -10 side must also walk
        // free -- one comparison, two failure directions, both = no wall.
        WalkerCollision.Resolution r =
                WalkerCollision.resolve(0, -10, 5, -10, wallAtX5(), 0.35f, 0f, 1.7f);
        assertThat(r.blocked()).isFalse();
        assertThat(r.x()).isEqualTo(5f);
    }

    @Test
    void clearKnowsANullWorldIsVacantAndANearMissIsNotAContact() {
        // The standing-pose query, side by side with the sweep's own verdicts:
        // no world is clear by definition, a null entry is skipped, a band
        // that misses the body is furniture, and a footprint one disc-width
        // to the north is empty space.
        assertThat(WalkerCollision.clear(0f, 0f, null, 0.35f, 0f, 1.7f)).isTrue();
        assertThat(WalkerCollision.clear(5f, 0f, Arrays.asList(null, wallAtX5().get(0)),
                0.35f, 0f, 1.7f)).isFalse();
        List<Obstacle> ceiling =
                List.of(Obstacle.of("slab", 5f, 3.5f, 0f, 0.5f, 0.5f, 2f));
        assertThat(WalkerCollision.clear(5f, 0f, ceiling, 0.35f, 0f, 1.7f)).isTrue();
        assertThat(WalkerCollision.clear(5f, 5f, wallAtX5(), 0.35f, 0f, 1.7f))
                .as("x inside the grown span, z 1.65 outside it")
                .isTrue();
    }
}
