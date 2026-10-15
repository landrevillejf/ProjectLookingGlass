/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
package org.jdesktop.wonderland.client.collision;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1.1: the two questions one Obstacle answers — does this band reach a body,
 * and does this disc touch this footprint. The kernel's sweeps are proven in
 * WalkerCollisionTest; this suite pins the record's own geometry, every side
 * of each comparison, because "just outside the grown face" and "just inside"
 * are one float apart and both must be decided the same way in every lane.
 */
class ObstacleTest {

    private static Obstacle wall() {
        // The same wall the sweep tests walk into: x 4.5..5.5, y 0..1.7, z -2..2.
        return Obstacle.of("wall", 5f, 0.85f, 0f, 0.5f, 0.85f, 2f);
    }

    @Test
    void aBandReachesDownFarEnoughAndUpFarEnoughToBlock() {
        Obstacle w = wall();
        assertThat(w.blocksHeight(0f, 1.7f)).isTrue();
        assertThat(w.blocksHeight(1.5f, 3.2f))
                .as("a walker whose feet are below the ceiling still hits it")
                .isTrue();
    }

    @Test
    void aBandEntirelyAboveTheHeadIsNotInTheWay() {
        Obstacle ceiling = Obstacle.of("slab", 0f, 3.5f, 0f, 1f, 0.5f, 1f); // y 3..4
        assertThat(ceiling.blocksHeight(0f, 1.7f)).isFalse();
    }

    @Test
    void aBandEntirelyBelowTheFeetIsSteppedOver() {
        Obstacle mat = Obstacle.of("mat", 0f, 0.05f, 0f, 1f, 0.05f, 1f); // y 0..0.1
        assertThat(mat.blocksHeight(0.5f, 2.2f)).isFalse();
    }

    @Test
    void theDiscOverlapsEverySideOfTheGrownFootprintAndNoneOutsideIt() {
        Obstacle w = wall(); // grown by 0.35: x 4.15..5.85, z -2.35..2.35
        assertThat(w.overlaps(5f, 0f, 0.35f)).isTrue();
        assertThat(w.overlaps(4.0f, 0f, 0.35f))
                .as("west of the grown face")
                .isFalse();
        assertThat(w.overlaps(6.0f, 0f, 0.35f))
                .as("east of it")
                .isFalse();
        assertThat(w.overlaps(5f, -2.5f, 0.35f))
                .as("north of the grown span")
                .isFalse();
        assertThat(w.overlaps(5f, 2.5f, 0.35f))
                .as("and south")
                .isFalse();
    }

    @Test
    void theGrownEdgeIsExclusiveStandingTangentIsLegal() {
        // Strict inequalities: a disc centre exactly on the grown face does
        // not overlap — the walker may stand flush against the wall's reach,
        // and it is the sweep's landing clamp that keeps it out of the body.
        Obstacle w = wall();
        assertThat(w.overlaps(4.15f, 0f, 0.35f)).isFalse();
        assertThat(w.overlaps(4.16f, 0f, 0.35f)).isTrue();
    }

    @Test
    void theCentreHalfExtentFormBuildsTheSameBoxTheRawCtorDoes() {
        assertThat(Obstacle.of("wall", 5f, 0.85f, 0f, 0.5f, 0.85f, 2f))
                .isEqualTo(new Obstacle("wall", 4.5f, 5.5f, 0f, 1.7f, -2f, 2f));
    }
}
