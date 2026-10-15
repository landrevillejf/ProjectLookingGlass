/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
package org.jdesktop.wonderland.client.cull;

import java.util.List;
import org.jdesktop.wonderland.client.cull.EyeProbe.EyePose;
import org.jdesktop.wonderland.client.cull.EyeProbe.Framing;
import org.jdesktop.wonderland.client.cull.EyeProbe.Target;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5.3: the framing question that CAN be answered without a display, answered
 * here. The live shading still needs the eye probe; whether a cell is in front
 * of the eye at all does not, and formalising that is what lets the P3.1 camera
 * rig and every later visual phase be checked in CI instead of by a hand.
 */
class EyeProbeTest {

    // Eye at the origin, looking down +Z, a 90-degree vertical field.
    private static final EyePose EYE = new EyePose(0, 0, 0, 0, 0, 1, 90f);

    @Test
    void straightAheadIsInFrame() {
        assertThat(EyeProbe.inFrame(EYE, new Target("ahead", 0, 0, 10, 1))).isTrue();
    }

    @Test
    void behindTheEyeIsOut() {
        assertThat(EyeProbe.inFrame(EYE, new Target("behind", 0, 0, -10, 1))).isFalse();
    }

    @Test
    void hardToTheSideIsOutsideA90DegreeField() {
        // 90 deg off the +Z axis; half-field is 45, so it is out.
        assertThat(EyeProbe.inFrame(EYE, new Target("side", 10, 0, 0, 0.5f))).isFalse();
    }

    @Test
    void withinTheHalfFieldIsIn() {
        // ~30 deg off axis (x=5, z=8.66), inside the 45 deg half-field.
        assertThat(EyeProbe.inFrame(EYE, new Target("near-axis", 5, 0, 8.66f, 0))).isTrue();
    }

    @Test
    void aBigSphereStraddlingTheEdgeIsStillFramed() {
        // Centre just outside the cone, but its radius reaches in -- framing errs
        // generous so a sliver on screen is never dropped.
        EyePose eye = new EyePose(0, 0, 0, 0, 0, 1, 20f); // narrow 20 deg field
        Target grazing = new Target("graze", 3f, 0f, 4f, 2.5f); // ~37 deg off, 25 deg subtends
        assertThat(EyeProbe.inFrame(eye, grazing)).isTrue();
    }

    @Test
    void theEyeInsideACellFramesIt() {
        assertThat(EyeProbe.inFrame(EYE, new Target("here", 0, 0, 0, 5))).isTrue();
    }

    @Test
    void aZeroLookDirectionFramesNothing() {
        EyePose blind = new EyePose(0, 0, 0, 0, 0, 0, 90f);
        assertThat(EyeProbe.inFrame(blind, new Target("x", 0, 0, 5, 1))).isFalse();
    }

    @Test
    void frameMarksEveryTargetItIsGiven() {
        Framing f = EyeProbe.frame(EYE, List.of(
                new Target("ahead", 0, 0, 10, 1),
                new Target("behind", 0, 0, -10, 1),
                new Target("angle", 5, 0, 8.66f, 0)));
        assertThat(f.total()).isEqualTo(3);
        assertThat(f.isInFrame("ahead")).isTrue();
        assertThat(f.isInFrame("behind")).isFalse();
        assertThat(f.isInFrame("angle")).isTrue();
        assertThat(f.visibleCount()).isEqualTo(2);
    }
}
