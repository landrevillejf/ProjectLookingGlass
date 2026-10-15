/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.4: the projector maths, headless.
 *
 * Each assert below is a physical statement about parallel light, checked as
 * arithmetic: a low sun lengthens, a high sun shortens, the shadow runs AWAY
 * from where the light comes from, and the two cases that must fall back to
 * the round pool (no light run, vertical light) answer null rather than an
 * invention. What no test here claims: that a projected pool RENDERS -- the
 * seat transform and the yawed quad are the probe's, and the live check that
 * a clinic shadow points the right way belongs to a screencapture run.
 */
package org.jdesktop.wonderland.renderer.view;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ShadowProjectorTest {

    /** A 2m figure at the origin on y=0, foot half-extents 0.3. */
    private static ShadowProjector.ProjectedShadow figure(double lx, double ly, double lz) {
        return ShadowProjector.project(lx, ly, lz, 0, 0, 0, 2.0, 0.3, 0.3);
    }

    @Test
    void aVerticalLightCastsNoProjectionAndKeepsTheRoundPool() {
        // Straight-down sun, and no light at all: the blob under the feet is
        // not a fallback here, it is the physically right answer.
        assertThat(ShadowProjector.project(0, -1, 0, 0, 0, 0, 2, 0.3, 0.3)).isNull();
        assertThat(ShadowProjector.project(0, 0, 0, 0, 0, 0, 2, 0.3, 0.3)).isNull();
        assertThat(ShadowProjector.project(0, 1, 0, 0, 0, 0, 2, 0.3, 0.3))
                .as("an uplight puts no pool on the floor either").isNull();
    }

    @Test
    void aLowSunRunsTheShadowLongAwayFromTheLight() {
        // Light travelling -X and downward (a sun in the +X sky): the shadow
        // lies along -X, longer the lower the sun.
        ShadowProjector.ProjectedShadow low = figure(-1, -0.5, 0);

        assertThat(low).isNotNull();
        assertThat(low.centerX()).isLessThan(0.0);
        assertThat(low.halfLengthZ()).isGreaterThan(low.halfFootZ());
        assertThat(low.centerY()).as("the pool still lies on the floor").isZero();
    }

    @Test
    void aHighSunShrinksTheRunTowardTheFootprint() {
        ShadowProjector.ProjectedShadow high = figure(-1, -4, 0);
        ShadowProjector.ProjectedShadow low = figure(-1, -0.5, 0);

        assertThat(Math.abs(high.centerX())).isLessThan(Math.abs(low.centerX()));
        assertThat(high.halfLengthZ()).isLessThan(low.halfLengthZ());
    }

    @Test
    void theHorizonSunIsCappedRatherThanDrawingAcrossTheWorld() {
        // A literally horizontal sun projects to infinity; the cap is what
        // keeps the pool a shadow and not a bandage the length of the campus.
        ShadowProjector.ProjectedShadow horizon = figure(-1, -1e-9, 0);

        assertThat(horizon).as("any downward at all still casts").isNotNull();
        double run = Math.sqrt(horizon.centerX() * horizon.centerX()
                + horizon.centerZ() * horizon.centerZ());

        assertThat(run).as("a horizon sun runs the capped length")
                .isLessThanOrEqualTo(2.0 * 6.0 * 0.5 + 1e-6);
        assertThat(horizon.halfLengthZ()).isCloseTo(0.3 * 6.0,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void theYawTurnsTheQuadAlongTheLightsRun() {
        // The quad is modelled with its stretch on the run axis; the yaw is
        // the angle from +Z to the light's travel direction, so a ray running
        // -X is a 90-degree turn (either sign turns the symmetric pool the
        // same way) and a +Z run is none.
        ShadowProjector.ProjectedShadow toX = figure(-1, -1, 0);
        ShadowProjector.ProjectedShadow toZ = figure(0, -1, 1);

        assertThat(Math.abs(toX.yawDeg())).isCloseTo(90.0, within(1e-6));
        assertThat(toZ.yawDeg()).isCloseTo(0.0, within(1e-6));
        assertThat(figure(0, -1, -1).yawDeg()).isCloseTo(180.0, within(1e-6));
    }

    @Test
    void aFlatThingOnTheFloorHasNothingToCast() {
        assertThat(ShadowProjector.project(-1, -1, 0, 0, 0, 0, 0.0, 0.3, 0.3)).isNull();
        assertThat(ShadowProjector.project(-1, -1, 0, 0, 0, 0, -0.5, 0.3, 0.3))
                .as("a box below its own floor casts nothing").isNull();
    }

    @Test
    void theCentreOfThePoolSitsHalfwayAlongTheCastRun() {
        // Box physics, not point physics: the feet stay put, the top of a
        // height-h box travels h/tan(elevation); the pool's centre is midway
        // along that run so the near end of the shadow starts AT the caster.
        ShadowProjector.ProjectedShadow s = figure(-1, -1, 0); // 45 deg sun
        double run = Math.sqrt(s.centerX() * s.centerX() + s.centerZ() * s.centerZ());

        // Exact arithmetic, not a hand-wave: at 45 degrees a 2m top travels
        // h/tan(45) = 2m down the -X ray, and the centre is at half that run,
        // sqrt(2) away from the origin.
        assertThat(run).as("half the top's -X travel of a 2m box at 45 degrees")
                .isCloseTo(Math.sqrt(2.0), within(1e-9));
        assertThat(s.translation()).containsExactly(s.centerX(), 0.0, s.centerZ());
    }
}
