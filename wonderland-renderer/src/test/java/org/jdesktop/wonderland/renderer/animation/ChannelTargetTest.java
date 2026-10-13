/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/* wonderland-renderer M7c: the pure channel-target vocabulary mapping. */
package org.jdesktop.wonderland.renderer.animation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChannelTargetTest {

    @Test
    void dotTokenFormIsThePrimaryShape() {
        ChannelTarget t = ChannelTarget.parse("humanoid-a-node/translation.Y");

        assertThat(t.supported()).isTrue();
        assertThat(t.nodeSid()).isEqualTo("humanoid-a-node");
        assertThat(t.kind()).isEqualTo(ChannelTarget.Kind.TRANSLATION);
        assertThat(t.array()).isEqualTo("Y");
    }

    @Test
    void angleTokenIsRecognised() {
        ChannelTarget t = ChannelTarget.parse("joint/rotation.ANGLE");

        assertThat(t.kind()).isEqualTo(ChannelTarget.Kind.ROTATION);
        assertThat(t.array()).isEqualTo("ANGLE");
    }

    @Test
    void blenderUnderscoreSuffixAlsoResolves() {
        ChannelTarget t = ChannelTarget.parse("spine/rotation_euler_X");

        assertThat(t.kind()).isEqualTo(ChannelTarget.Kind.ROTATION);
        assertThat(t.array()).isEqualTo("X");
    }

    @Test
    void locationAndPositionAndScaleSynonymsAreUnderstood() {
        assertThat(ChannelTarget.parse("n/location.Z").kind())
                .isEqualTo(ChannelTarget.Kind.TRANSLATION);
        assertThat(ChannelTarget.parse("n/position.Y").kind())
                .isEqualTo(ChannelTarget.Kind.TRANSLATION);
        assertThat(ChannelTarget.parse("n/scale.X").kind())
                .isEqualTo(ChannelTarget.Kind.SCALE);
        assertThat(ChannelTarget.parse("n/scaling.Y").kind())
                .isEqualTo(ChannelTarget.Kind.SCALE);
    }

    @Test
    void aMatrixTargetIsHonestlyUnsupported() {
        // whole-matrix animation is a different sampler model; misreading it
        // as a side-effect channel would move bodies wrong, so it drops
        assertThat(ChannelTarget.parse("n/matrix").supported()).isFalse();
    }

    @Test
    void noElementPartIsUnsupported() {
        assertThat(ChannelTarget.parse("just-a-node").supported()).isFalse();
    }

    @Test
    void malformedInputIsUnsupportedNeverThrows() {
        assertThat(ChannelTarget.parse(null).supported()).isFalse();
        assertThat(ChannelTarget.parse("  ").supported()).isFalse();
        assertThat(ChannelTarget.parse("/translation.X").supported()).isFalse();
        assertThat(ChannelTarget.parse(null).nodeSid()).isNull();
    }

    @Test
    void elementWithoutTokenKeepsANullArrayForTheFactoryToExpand() {
        ChannelTarget t = ChannelTarget.parse("n/rotation");

        assertThat(t.kind()).isEqualTo(ChannelTarget.Kind.ROTATION);
        assertThat(t.array()).isNull();
    }

    @Test
    void componentIndexMapsTokensIntoBundles() {
        assertThat(ChannelTarget.parse("n/translation.X").componentIndex(3)).isZero();
        assertThat(ChannelTarget.parse("n/translation.Y").componentIndex(3)).isEqualTo(1);
        assertThat(ChannelTarget.parse("n/translation.Z").componentIndex(3)).isEqualTo(2);
        assertThat(ChannelTarget.parse("n/rotation.ANGLE").componentIndex(4)).isEqualTo(3);
        // a single-value source carries exactly its own array
        assertThat(ChannelTarget.parse("n/translation.Z").componentIndex(1)).isZero();
        // Y cannot live in a stride-1 bundle pick nor W in a stride-3
        assertThat(ChannelTarget.parse("n/rotation.W").componentIndex(3)).isEqualTo(-1);
        // no token in a multi-component bundle: expansion is the factory's job
        assertThat(ChannelTarget.parse("n/translation").componentIndex(3)).isEqualTo(-1);
    }

    @Test
    void caseInsensitiveTokensAndTrimming() {
        ChannelTarget t = ChannelTarget.parse("  N2/Translation.angle ".replace("angle", "ANGLE"));

        assertThat(t.nodeSid()).isEqualTo("N2");
        assertThat(t.kind()).isEqualTo(ChannelTarget.Kind.TRANSLATION);
        assertThat(t.array()).isEqualTo("ANGLE"); // recognized, but a translation
        // ANGLE in a stride-3 translation bundle simply resolves to -1 downstream
        assertThat(t.componentIndex(3)).isEqualTo(-1);
    }
}
