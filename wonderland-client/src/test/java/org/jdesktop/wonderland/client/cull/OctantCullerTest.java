/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
package org.jdesktop.wonderland.client.cull;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0.2: the potential-visible-set culler is proven here with no Display and no
 * Java 3D -- exactly the claim the phase rests on: the same query the renderer
 * will call is correct as pure data.
 */
class OctantCullerTest {

    @Test
    void emptyWorldIsAnEmptyAnswer() {
        OctantCuller culler = new OctantCuller(10f);
        PotentialVisibleSet pvs = culler.query(0, 0, 0, 100);
        assertThat(pvs.visibleCount()).isZero();
        assertThat(pvs.total()).isZero();
        assertThat(pvs.cullRatio()).isEqualTo(0.0);
    }

    @Test
    void aCellNearTheEyeSurvivesAndAFarOneIsCulled() {
        OctantCuller culler = new OctantCuller(8f);
        culler.register("near", 0f, 0f, 0f, 1f);
        culler.register("far", 500f, 0f, 0f, 1f);

        PotentialVisibleSet pvs = culler.query(0f, 0f, 0f, 50f);

        assertThat(pvs.contains("near")).isTrue();
        assertThat(pvs.contains("far")).isFalse();
        assertThat(pvs.visibleCount()).isEqualTo(1);
        assertThat(pvs.total()).isEqualTo(2);
        assertThat(pvs.cullRatio()).isEqualTo(0.5);
    }

    @Test
    void boundingRadiusExtendsReachSoAGrazingCellIsKept() {
        OctantCuller culler = new OctantCuller(16f);
        // Centre is 30m away but its 5m radius means its face is 25m out.
        culler.register("big", 30f, 0f, 0f, 5f);
        assertThat(culler.query(0f, 0f, 0f, 25f).contains("big")).isTrue();
        assertThat(culler.query(0f, 0f, 0f, 20f).contains("big")).isFalse();
    }

    @Test
    void theAnswerIsIndependentOfBucketSize() {
        // cellSize only changes the work, never the set -- assert that directly,
        // because a bucket bug would show up as a coarse-vs-fine disagreement.
        float[] sizes = {1f, 4f, 17f, 64f};
        PotentialVisibleSet reference = null;
        for (float size : sizes) {
            OctantCuller culler = new OctantCuller(size);
            culler.register("a", 0f, 0f, 0f, 1f);
            culler.register("b", 12f, 3f, -4f, 2f);
            culler.register("c", 900f, 0f, 0f, 1f);
            PotentialVisibleSet pvs = culler.query(5f, 0f, 0f, 20f);
            if (reference == null) {
                reference = pvs;
            } else {
                assertThat(pvs.ids()).isEqualTo(reference.ids());
            }
        }
        assertThat(reference.ids()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void sameIdRegisteredTwiceCountsAsOneVisibleCellButTwoVolumes() {
        OctantCuller culler = new OctantCuller(10f);
        culler.register("room", 0f, 0f, 0f, 1f);
        culler.register("room", 40f, 0f, 0f, 1f);
        assertThat(culler.size()).isEqualTo(2);
        // From the eye only the first volume is in range; the id appears once.
        PotentialVisibleSet pvs = culler.query(0f, 0f, 0f, 5f);
        assertThat(pvs.ids()).containsExactly("room");
        assertThat(pvs.total()).isEqualTo(2);
    }

    @Test
    void clearResetsForTheNextWorldLoad() {
        OctantCuller culler = new OctantCuller(10f);
        culler.register("x", 0f, 0f, 0f, 1f);
        culler.clear();
        assertThat(culler.size()).isZero();
        assertThat(culler.query(0f, 0f, 0f, 1000f).visibleCount()).isZero();
    }

    @Test
    void nonPositiveCellSizeIsRejected() {
        assertThatThrownBy(() -> new OctantCuller(0f))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OctantCuller(-1f))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNegativeRadiusIsClampedNotCrashing() {
        OctantCuller culler = new OctantCuller(10f);
        culler.register("p", 0f, 0f, 0f, -5f); // treated as a point
        assertThat(culler.query(0f, 0f, 0f, 1f).contains("p")).isTrue();
        assertThat(culler.query(3f, 0f, 0f, 1f).contains("p")).isFalse();
    }
}
