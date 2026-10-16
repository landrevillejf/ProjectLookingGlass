/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * The pure half of DepthBlitFormat: the depth-format decision, asserted headless.
 *
 * DepthBlitFormat.query reads two numbers off a live GL framebuffer and cannot run
 * without a display (a GLContext cannot be constructed in a headless test JVM -- the
 * repo-wide rule for Jogamp objects), so query is probe-only, verified by the P2.5 JOGL
 * probe. But the decision those two numbers drive -- which depth internal format a blit
 * from a framebuffer of a given depth size and component type will actually accept -- is
 * pure, and it is the whole fix: the SSAO and DOF passes used to hardcode 24-bit, which
 * faults (GL_INVALID_OPERATION, silently copying nothing) against the 32-bit-float depth a
 * plain JOGL GLCanvas gets on Mesa, blackening the frame. Every branch of that decision is
 * pinned below, including the exact size=32 / type=GL_FLOAT the probe reported for the
 * window framebuffer, so a regression back to a hardcoded format cannot pass unnoticed.
 */
package org.jdesktop.wonderland.renderer.post;

import static org.assertj.core.api.Assertions.assertThat;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL2ES3;

import org.junit.jupiter.api.Test;

class DepthBlitFormatTest {

    @Test
    void aThirtyTwoBitFloatDepthChoosesTheFloatFormat() {
        // The exact numbers the P2.5 probe read off the JOGL window framebuffer
        // (size=32, componentType=GL_FLOAT): the case the hardcoded 24-bit pass
        // blackened on. It must resolve to the 32F format that blits cleanly.
        DepthBlitFormat fmt = DepthBlitFormat.choose(32, GL2.GL_FLOAT);
        assertThat(fmt.internalFormat).isEqualTo(GL2ES3.GL_DEPTH_COMPONENT32F);
        assertThat(fmt.pixelType).isEqualTo(GL2.GL_FLOAT);
    }

    @Test
    void aWiderFloatDepthStillChoosesTheFloatFormat() {
        // >=32, not ==32: a hypothetical deeper float target takes the same format.
        DepthBlitFormat fmt = DepthBlitFormat.choose(48, GL2.GL_FLOAT);
        assertThat(fmt.internalFormat).isEqualTo(GL2ES3.GL_DEPTH_COMPONENT32F);
        assertThat(fmt.pixelType).isEqualTo(GL2.GL_FLOAT);
    }

    @Test
    void aThirtyTwoBitNormalisedDepthChoosesTheWidestIntegerFormat() {
        DepthBlitFormat fmt = DepthBlitFormat.choose(32, GL2.GL_UNSIGNED_NORMALIZED);
        assertThat(fmt.internalFormat).isEqualTo(GL2.GL_DEPTH_COMPONENT32);
        assertThat(fmt.pixelType).isEqualTo(GL2.GL_UNSIGNED_INT);
    }

    @Test
    void aTwentyFourBitDepthChoosesTheReferenceFormat() {
        // The Java 3D GLFBODrawable's depth: the format the passes always built, so
        // the pre-existing host keeps the pre-existing behaviour.
        DepthBlitFormat fmt = DepthBlitFormat.choose(24, GL2.GL_UNSIGNED_NORMALIZED);
        assertThat(fmt.internalFormat).isEqualTo(GL2.GL_DEPTH_COMPONENT24);
        assertThat(fmt.pixelType).isEqualTo(GL2.GL_UNSIGNED_INT);
    }

    @Test
    void aSixteenBitDepthChoosesTheNarrowIntegerFormatAndShortType() {
        DepthBlitFormat fmt = DepthBlitFormat.choose(16, GL2.GL_UNSIGNED_NORMALIZED);
        assertThat(fmt.internalFormat).isEqualTo(GL2.GL_DEPTH_COMPONENT16);
        assertThat(fmt.pixelType).isEqualTo(GL2.GL_UNSIGNED_SHORT);
    }

    @Test
    void aFloatComponentTypeBelowThirtyTwoBitsFallsThroughToTheSizeBranches() {
        // The float format is only chosen at >=32; a (nonsensical but possible)
        // 24-bit float target is not asked for a float format it cannot have, so it
        // falls through to the size>=24 integer branch.
        DepthBlitFormat fmt = DepthBlitFormat.choose(24, GL2.GL_FLOAT);
        assertThat(fmt.internalFormat).isEqualTo(GL2.GL_DEPTH_COMPONENT24);
        assertThat(fmt.pixelType).isEqualTo(GL2.GL_UNSIGNED_INT);
    }

    @Test
    void aTargetWithNoReportedDepthFallsBackToTheReferenceFormat() {
        // size 0 is what a refused or absent depth query leaves behind; the fallback
        // is the 24-bit default, which is at worst the pre-existing behaviour rather
        // than a fault or a throw.
        DepthBlitFormat fmt = DepthBlitFormat.choose(0, 0);
        assertThat(fmt.internalFormat).isEqualTo(GL2.GL_DEPTH_COMPONENT24);
        assertThat(fmt.pixelType).isEqualTo(GL2.GL_UNSIGNED_INT);
    }

    @Test
    void anOutOfRangeDepthSizeFallsBackToTheReferenceFormat() {
        DepthBlitFormat fmt = DepthBlitFormat.choose(-1, GL2.GL_UNSIGNED_NORMALIZED);
        assertThat(fmt.internalFormat).isEqualTo(GL2.GL_DEPTH_COMPONENT24);
        assertThat(fmt.pixelType).isEqualTo(GL2.GL_UNSIGNED_INT);
    }
}
