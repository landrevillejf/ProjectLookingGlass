/*
 * The bake's arithmetic and its two measured anchors. The claims here are the
 * ones the pixels were argued from, so they are stated as numbers a later rig
 * change has to answer for: what the platform answers with today, that the
 * brightest face keeps the authored colour exactly, and that a face turning
 * away visibly darkens -- the last two being the whole of what P0.1 promises a
 * person can see.
 */
package org.jdesktop.wonderland.renderer.mesh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BakedRigTest {

    private BakedRig.Spec before;

    @BeforeEach
    void rememberTheRig() {
        before = BakedRig.spec();
    }

    @AfterEach
    void putItBack() {
        BakedRig.setSpec(before);
    }

    /**
     * The normal that looks straight into the key. The key direction is the
     * direction light TRAVELS (down and away, into -Z), so a surface facing it
     * has the opposite normal -- and the rig's whole point is that such a face
     * keeps the authored colour.
     */
    private static final float[] FACING = {-BakedRig.DEFAULT_KEY_DIRECTION[0],
            -BakedRig.DEFAULT_KEY_DIRECTION[1], -BakedRig.DEFAULT_KEY_DIRECTION[2]};
    /** A normal perpendicular to the key: dot exactly zero, so ambient alone. */
    private static final float[] EDGE = {0.899f, 0f, -0.231f};
    /** The back face: normal parallel to the key's travel, lit by nothing. */
    private static final float[] AWAY = BakedRig.DEFAULT_KEY_DIRECTION;

    @Test
    void thePerpendicularTestNormalReallyIsPerpendicularToTheDefaultKey() {
        // Guards the arithmetic below: if EDGE drifts off perpendicular, the
        // "edge-on sits at the ambient term" assertion stops proving anything.
        float dot = 0f;
        for (int i = 0; i < 3; i++) {
            dot += EDGE[i] * BakedRig.DEFAULT_KEY_DIRECTION[i];
        }
        assertThat(dot).isCloseTo(0f, within(1e-4f));
    }

    @Test
    void thePlatformFloorIsRecordedBecauseEveryOtherNumberIsCalibratedAgainstIt() {
        // Measured in WlProbe32: red (0.9,0.1,0.1) with no light and with a full
        // white ambient both read back (46,5,5) == authored x 0.2, GL's own
        // default global ambient. The constant is a pin, not a setting.
        assertThat(BakedRig.MEASURED_PLATFORM_AMBIENT_FLOOR).isEqualTo(0.2f);
    }

    @Test
    void theDefaultRigKeepsTheBrightestFaceExactlyAuthored() {
        // ambient + key == 1 by construction: a face looking at the key is the
        // colour the artist authored, so wiring this in cannot brighten or wash
        // the shipped world.
        assertThat(BakedRig.DEFAULT_AMBIENT + BakedRig.DEFAULT_KEY)
                .isCloseTo(1.0f, within(1e-6f));
        assertThat(BakedRig.factor(FACING[0], FACING[1], FACING[2]))
                .isCloseTo(1.0f, within(1e-4f));
    }

    @Test
    void aFaceTurningAwayDarkensAndTheTwoExtremesAreFarEnoughApartToBeSeen() {
        float facing = BakedRig.factor(FACING[0], FACING[1], FACING[2]);
        float edge = BakedRig.factor(EDGE[0], EDGE[1], EDGE[2]);
        float away = BakedRig.factor(AWAY[0], AWAY[1], AWAY[2]);

        assertThat(facing).isCloseTo(1.0f, within(1e-4f));
        // edge-on and back-facing both sit at the ambient term: the key adds
        // nothing once the dot stops being negative
        assertThat(edge).isCloseTo(BakedRig.DEFAULT_AMBIENT, within(1e-4f));
        assertThat(away).isCloseTo(BakedRig.DEFAULT_AMBIENT, within(1e-4f));
        // and that spread is what makes the rig visible at all: a 28 % swing on
        // a 229-level surface is ~64 codes, several times what readRaster
        // quantisation can invent
        assertThat(facing - away).isCloseTo(0.28f, within(1e-3f));
    }

    @Test
    void aNormalWithNoLengthGetsTheAmbientTermInsteadOfANanOrBlack() {
        assertThat(BakedRig.factor(0f, 0f, 0f))
                .isCloseTo(BakedRig.DEFAULT_AMBIENT, within(1e-6f));
    }

    @Test
    void theFactorIsCappedSoARigThatAsksForMoreThanOneCannotBlowOut() {
        BakedRig.setSpec(new BakedRig.Spec(0.9f, 0.9f, 0f, 0f, -1f));
        assertThat(BakedRig.factor(0f, 0f, 1f)).isEqualTo(1.0f);   // faces the key
        assertThat(BakedRig.factor(0f, 0f, -1f)).isCloseTo(0.9f, within(1e-6f));
    }

    @Test
    void theIdentityRigLeavesEveryNormalAlone() {
        BakedRig.setSpec(BakedRig.Spec.none());
        assertThat(BakedRig.factor(FACING[0], FACING[1], FACING[2])).isEqualTo(1.0f);
        assertThat(BakedRig.factor(AWAY[0], AWAY[1], AWAY[2])).isEqualTo(1.0f);
        assertThat(BakedRig.isIdentity(BakedRig.Spec.none())).isTrue();
        assertThat(BakedRig.isIdentity(new BakedRig.Spec(0.72f, 0.28f, 0, 0, -1))).isFalse();
    }

    @Test
    void theKeyDirectionIsNormalisedAndADegenerateOneFallsBackToDown() {
        // (0,-2,-4) is the same ray as the default's family; the length must not
        // leak into the intensity, which is what a non-normalised dot would do.
        BakedRig.Spec longKey = new BakedRig.Spec(0.5f, 0.5f, 0f, -2f, -4f);
        BakedRig.Spec unitKey = new BakedRig.Spec(0.5f, 0.5f, 0f, 0f, -1f);
        assertThat(BakedRig.factor(0f, -0.447f, -0.894f, longKey))
                .isCloseTo(BakedRig.factor(0f, -0.447f, -0.894f, unitKey), within(1e-3f));

        BakedRig.setSpec(new BakedRig.Spec(0.4f, 0.6f, 0f, 0f, 0f));
        assertThat(BakedRig.spec().direction()).containsExactly(0f, 0f, -1f);
        // with the fallback direction travelling down -Z, a +Z normal faces the
        // key and takes the full term
        assertThat(BakedRig.factor(0f, 0f, 1f)).isCloseTo(1.0f, within(1e-5f));
    }

    @Test
    void aTexturedSurfaceCarriesTheFactorAloneBecauseItsColourIsTheTexture() {
        BakedRig.setSpec(new BakedRig.Spec(0.5f, 0.5f, 0f, 0f, -1f));
        // vertex 0 faces the key (+Z), vertex 1 turns away from it
        float[] colors = BakedRig.vertexColors(new float[] {0, 0, 1, 0, 0, -1}, null);
        assertThat(colors).hasSize(6);
        assertThat(colors[0]).isCloseTo(1.0f, within(1e-5f));
        assertThat(colors[1]).isCloseTo(1.0f, within(1e-5f));
        assertThat(colors[2]).isCloseTo(1.0f, within(1e-5f));
        assertThat(colors[3]).isCloseTo(0.5f, within(1e-5f)); // the back face, ambient only
    }

    @Test
    void anUntexturedSurfaceCarriesTheAuthoredColourTimesTheFactor() {
        BakedRig.setSpec(new BakedRig.Spec(0.5f, 0.5f, 0f, 0f, -1f));
        float[] authored = {0.9f, 0.1f, 0.1f, 0.9f, 0.1f, 0.1f};
        float[] colors = BakedRig.vertexColors(new float[] {0, 0, 1, 0, 0, -1}, authored);
        assertThat(colors[0]).isCloseTo(0.9f, within(1e-5f));   // facing: authored x 1.0
        assertThat(colors[1]).isCloseTo(0.1f, within(1e-5f));
        assertThat(colors[3]).isCloseTo(0.45f, within(1e-5f));  // away: authored x 0.5
        assertThat(colors[4]).isCloseTo(0.05f, within(1e-5f));
    }

    @Test
    void nothingToShadeIsNoColourArrayAtAllRatherThanAnEmptyOne() {
        // null is the factory's signal to not ask for COLOR_3, so the geometry
        // keeps today's format when there is neither a normal nor a colour.
        assertThat(BakedRig.vertexColors(null, null)).isNull();
        assertThat(BakedRig.vertexColors(new float[0], null)).isNull();
    }

    @Test
    void activeIsTheFlagAndTheRigTogether() {
        BakedRig.setSpec(BakedRig.Spec.none());
        // identity rig: the bake does nothing, so it must report itself off even
        // though the property says on (nothing set in this JVM)
        assertThat(BakedRig.active()).isFalse();
        BakedRig.setSpec(new BakedRig.Spec(0.72f, 0.28f, 0f, 0f, -1f));
        assertThat(BakedRig.active()).isTrue();
    }

    @Test
    void settingTheRigToNullRestoresTheDefaultRatherThanANullPointer() {
        BakedRig.setSpec(null);
        assertThat(BakedRig.spec().ambient()).isEqualTo(BakedRig.DEFAULT_AMBIENT);
        assertThat(BakedRig.spec().key()).isEqualTo(BakedRig.DEFAULT_KEY);
    }
}
