/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.5: the environment maths, headless.
 *
 * Binding a cube texture and running the per-fragment program is what no test
 * may claim -- that is the fork's ShaderAppearance path, validated only in a
 * probe run. What IS provable here is the arithmetic that program will one day
 * execute: the reflect vector, Snell's refraction and its total-internal-
 * reflection refusal, the Schlick fresnel between the two extremes, the cube
 * face a direction lands on, and the two blends. These cases pin the identities
 * (ior 1 bends nothing, coverage 0 is the exact base pixel) and the physically
 * right behaviour (refraction bends toward the normal, grazing reflects fully).
 */
package org.jdesktop.wonderland.renderer.shade;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EnvMapKernelTest {

    private static final float EPS = 1e-4f;

    @Test
    void aRayMeetingAFlatMirrorBouncesStraightBack() {
        // incident travels down -Z onto a surface whose normal faces the eye (+Z):
        // the reflection returns up +Z, the faithful straight-on mirror.
        float[] r = EnvMapKernel.reflect(new float[] {0f, 0f, -1f}, new float[] {0f, 0f, 1f});

        assertThat(r).isNotNull();
        assertThat(r[0]).isCloseTo(0f, within(EPS));
        assertThat(r[1]).isCloseTo(0f, within(EPS));
        assertThat(r[2]).isCloseTo(1f, within(EPS));
    }

    @Test
    void reflectRefusesADegenerateInputRatherThanInventingNaN() {
        assertThat(EnvMapKernel.reflect(new float[] {0f, 0f, 0f}, new float[] {0f, 1f, 0f}))
                .as("a zero incident ray has no direction to reflect")
                .isNull();
        assertThat(EnvMapKernel.reflect(null, new float[] {0f, 1f, 0f})).isNull();
    }

    @Test
    void anEqualMediumLetsTheRayThroughUnbent() {
        // eta == 1 (no index jump) must return the incident direction itself:
        // the identity every refraction test leans on.
        float[] through = EnvMapKernel.refract(new float[] {0f, 0f, -1f},
                new float[] {0f, 0f, 1f}, 1f);

        assertThat(through).isNotNull();
        assertThat(through[0]).isCloseTo(0f, within(EPS));
        assertThat(through[1]).isCloseTo(0f, within(EPS));
        assertThat(through[2]).isCloseTo(-1f, within(EPS));
    }

    @Test
    void enteringDenserGlassBendsTheRayTowardTheNormal() {
        // A shallow ray (mostly tangential) entering glass from air keeps its
        // downward sense but shrinks its horizontal slide -- Snell toward the
        // normal. N faces the eye (+Y), incident travels down into the surface.
        float[] refr = EnvMapKernel.refract(new float[] {1f, -1f, 0f},
                new float[] {0f, 1f, 0f}, 1f / 1.5f);

        assertThat(refr).isNotNull();
        float tangentialIn = (float) (1 / Math.sqrt(2));
        assertThat(Math.abs(refr[0])).as("horizontal component shrinks toward the normal")
                .isLessThan(tangentialIn);
        assertThat(refr[1]).as("still travelling down into the medium").isNegative();
    }

    @Test
    void grazingInsideGlassIsTotalInternalReflection() {
        // Dense to rare (eta > 1) at a shallow angle: no transmitted ray exists,
        // so the kernel answers null -- exactly what the fragment program reads
        // as a zero-length refract() and resolves by falling back to reflection.
        float[] refr = EnvMapKernel.refract(new float[] {1f, -0.05f, 0f},
                new float[] {0f, 1f, 0f}, 1.5f);

        assertThat(refr).as("past the critical angle there is no refraction").isNull();
    }

    @Test
    void fresnelSitsBetweenTheNormalReflectanceAndAFullMirror() {
        // Head-on at ior 1.5 gives the Schlick base F0 = ((1-1.5)/(1+1.5))^2 = 0.04;
        // grazing (cos 0) approaches 1 -- water is a mirror at the horizon.
        assertThat(EnvMapKernel.fresnel(1f, 1.5f)).isCloseTo(0.04f, within(1e-3f));
        assertThat(EnvMapKernel.fresnel(0f, 1.5f)).isCloseTo(1f, within(EPS));
        // An equal medium reflects nothing head-on (F0 == 0) yet still mirrors
        // at the horizon, and the result is always a usable [0,1] weight.
        assertThat(EnvMapKernel.fresnel(1f, 1f)).isCloseTo(0f, within(EPS));
        assertThat(EnvMapKernel.fresnel(2f, 1.5f)).as("cos above 1 clamps to head-on")
                .isCloseTo(0.04f, within(1e-3f));
        assertThat(EnvMapKernel.fresnel(-1f, 1.5f)).as("cos below 0 clamps to grazing")
                .isCloseTo(1f, within(EPS));
    }

    @Test
    void eachFaceAxisSamplesItsCubeFaceAtTheCentre() {
        // The canonical cube selection: a direction straight down a face axis
        // lands on that face at s == t == 0.5, the anchor every lookup shares.
        assertThat(EnvMapKernel.cubeFace(new float[] {1f, 0f, 0f}).face())
                .isEqualTo(EnvMapKernel.FACE_POS_X);
        assertThat(EnvMapKernel.cubeFace(new float[] {-1f, 0f, 0f}).face())
                .isEqualTo(EnvMapKernel.FACE_NEG_X);
        assertThat(EnvMapKernel.cubeFace(new float[] {0f, 1f, 0f}).face())
                .isEqualTo(EnvMapKernel.FACE_POS_Y);
        assertThat(EnvMapKernel.cubeFace(new float[] {0f, -1f, 0f}).face())
                .isEqualTo(EnvMapKernel.FACE_NEG_Y);
        assertThat(EnvMapKernel.cubeFace(new float[] {0f, 0f, 1f}).face())
                .isEqualTo(EnvMapKernel.FACE_POS_Z);
        assertThat(EnvMapKernel.cubeFace(new float[] {0f, 0f, -1f}).face())
                .isEqualTo(EnvMapKernel.FACE_NEG_Z);

        EnvMapKernel.CubeSample cx = EnvMapKernel.cubeFace(new float[] {1f, 0f, 0f});
        assertThat(cx.s()).isCloseTo(0.5f, within(EPS));
        assertThat(cx.t()).isCloseTo(0.5f, within(EPS));
    }

    @Test
    void anOffAxisDirectionStaysInsideTheUnitSquare() {
        EnvMapKernel.CubeSample s = EnvMapKernel.cubeFace(new float[] {2f, 1f, 1f});

        assertThat(s.face()).as("x is dominant and positive").isEqualTo(EnvMapKernel.FACE_POS_X);
        assertThat(s.s()).isBetween(0f, 1f);
        assertThat(s.t()).isBetween(0f, 1f);
        assertThat(EnvMapKernel.cubeFace(new float[] {0f, 0f, 0f})).isNull();
    }

    @Test
    void mixIsTheExactIdentityAtBothEnds() {
        int red = 0xFFFF0000;   // opaque red
        int blue = 0xFF0000FF;  // opaque blue

        assertThat(EnvMapKernel.mix(red, blue, 0f)).isEqualTo(red);
        assertThat(EnvMapKernel.mix(red, blue, 1f)).isEqualTo(blue);
        int half = EnvMapKernel.mix(red, blue, 0.5f);
        assertThat((half >> 16) & 0xFF).as("red halves toward blue")
                .isLessThan(0xFF);
        assertThat(half & 0xFF).as("blue rises toward red").isGreaterThan(0);
    }

    @Test
    void compositeHonoursTheCoverageAndFresnelIdentities() {
        int base = 0xFF102030;
        int refl = 0xFFA0B0C0;
        int refr = 0xFF010203;

        assertThat(EnvMapKernel.composite(base, refl, refr, 0.7f, 0f))
                .as("zero coverage is the exact base pixel -- no environment shows")
                .isEqualTo(base);
        assertThat(EnvMapKernel.composite(base, refl, refr, 1f, 1f))
                .as("full fresnel and full coverage is pure reflection")
                .isEqualTo(refl);
        assertThat(EnvMapKernel.composite(base, refl, refr, 0f, 1f))
                .as("zero fresnel and full coverage is pure refraction")
                .isEqualTo(refr);
    }
}
