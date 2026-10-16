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
 * invention. The one claim that took a live frame to notice was missing is the
 * anchor: a pool is the silhouette of the box from its own feet to where its
 * top lands down the ray, so its NEAR EDGE has to sit on the footprint. A
 * projector that gets the elevation trigonometry wrong (dividing by sin where
 * the geometry says tan) and scales the length off the footprint rather than off
 * the run draws a shadow that starts metres past the feet -- which is what these
 * tests now pin with exact arithmetic, because that defect looked like a shadow
 * and only measured like a lie.
 * What no test here claims: that a projected pool RENDERS -- the seat transform
 * and the yawed quad are the probe's, and the live check that a clinic shadow
 * points the right way belongs to a screencapture run.
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
        // lies along -X, longer the lower the sun. A 2m box under a ray whose
        // drop is half its run stands at tan(elev) = 0.5, so its top lands
        // 2/0.5 = 4m down the ray and the centre at half that.
        ShadowProjector.ProjectedShadow low = figure(-1, -0.5, 0);

        assertThat(low).isNotNull();
        assertThat(low.centerX()).as("the centre is halfway along a 4 m run")
                .isCloseTo(-2.0, within(1e-9));
        assertThat(low.halfLengthAlong()).isGreaterThan(low.halfFootAlong());
        assertThat(low.centerY()).as("the pool still lies on the floor").isZero();
    }

    @Test
    void aHighSunShrinksTheRunTowardTheFootprint() {
        ShadowProjector.ProjectedShadow high = figure(-1, -4, 0);
        ShadowProjector.ProjectedShadow low = figure(-1, -0.5, 0);

        assertThat(Math.abs(high.centerX())).as("tan(elev) = 4, so a 0.5 m run")
                .isCloseTo(0.25, within(1e-9));
        assertThat(Math.abs(high.centerX())).isLessThan(Math.abs(low.centerX()));
        assertThat(high.halfLengthAlong()).isLessThan(low.halfLengthAlong());
    }

    @Test
    void theHorizonSunIsCappedRatherThanDrawingAcrossTheWorld() {
        // A literally horizontal sun projects to infinity; the cap is what
        // keeps the pool a shadow and not a bandage the length of the campus.
        ShadowProjector.ProjectedShadow horizon = figure(-1, -1e-9, 0);

        assertThat(horizon).as("any downward at all still casts").isNotNull();
        double run = Math.sqrt(horizon.centerX() * horizon.centerX()
                + horizon.centerZ() * horizon.centerZ());

        assertThat(run).as("the centre sits halfway along a run of six heights")
                .isCloseTo(2.0 * 6.0 * 0.5, within(1e-9));
        assertThat(horizon.halfLengthAlong())
                .as("and the pool still reaches one footprint past each end")
                .isCloseTo(0.3 + 6.0, within(1e-9));
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
        // h/tan(45) = 2m down the -X ray, so the centre sits 1m off the feet.
        // This assertion used to read sqrt(2), which is what dividing by
        // sin(45) instead of tan(45) gives -- a test written against the bug it
        // was supposed to catch, and the reason a live frame was the only thing
        // left that could see the shadow detached from the person casting it.
        assertThat(run).as("half the top's -X travel of a 2m box at 45 degrees")
                .isCloseTo(1.0, within(1e-9));
        assertThat(s.translation()).containsExactly(s.centerX(), 0.0, s.centerZ());
    }

    @Test
    void thePoolStartsAtTheCastersFeetUnderEverySun() {
        // The anchor, stated as the invariant the eye actually checks: walk from
        // the centre back along the light by the pool's own half-length and you
        // land on the edge of the footprint, never past it -- for a low sun, a
        // steep one, and the capped horizon in between. A pool that fails this
        // is a dark oval lying on empty floor a metre or five from a person.
        for (double down : new double[] {0.05, 0.25, 0.5, 1.0, 2.0, 8.0}) {
            ShadowProjector.ProjectedShadow s = figure(-1, -down, 0);

            assertThat(s).as("a sun that drops at all still casts").isNotNull();
            assertThat(Math.abs(s.centerX()) - s.halfLengthAlong())
                    .as("near edge at the feet for a drop of " + down)
                    .isCloseTo(-s.halfFootAlong(), within(1e-9));
            assertThat(s.halfLengthAlong())
                    .as("and the far edge reaches the projected top")
                    .isGreaterThan(s.halfFootAlong());
        }
    }

    @Test
    void theSunsAzimuthSetsTheWidthAsWellAsTheDirection() {
        // A long thin caster (0.9 x 0.2 half-extents) lit along X must present
        // its SHORT side across the light and its long one down the run; lit
        // along Z the two swap. Reading the authored axes instead of the light's
        // would keep the pool the right area and the wrong shape the moment the
        // sun came in from the side, which is most of the day.
        ShadowProjector.ProjectedShadow alongX =
                ShadowProjector.project(-1, -1, 0, 0, 0, 0, 2.0, 0.9, 0.2);
        ShadowProjector.ProjectedShadow alongZ =
                ShadowProjector.project(0, -1, -1, 0, 0, 0, 2.0, 0.9, 0.2);

        assertThat(alongX.halfFootAlong()).as("down the run: the X half-extent")
                .isCloseTo(0.9, within(1e-9));
        assertThat(alongX.halfAcross()).as("across it: the Z half-extent")
                .isCloseTo(0.2, within(1e-9));
        assertThat(alongZ.halfFootAlong()).as("the quarter turn swaps them")
                .isCloseTo(0.2, within(1e-9));
        assertThat(alongZ.halfAcross()).isCloseTo(0.9, within(1e-9));
        assertThat(alongX.halves()).containsExactly(alongX.halfAcross(),
                alongX.halfFootAlong(), alongX.halfLengthAlong());
    }
}
