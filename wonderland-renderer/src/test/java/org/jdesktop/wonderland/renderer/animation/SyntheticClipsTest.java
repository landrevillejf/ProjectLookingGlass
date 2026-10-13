/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/* wonderland-renderer M7c: the synthetic fallback trio. */
package org.jdesktop.wonderland.renderer.animation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SyntheticClipsTest {

    private static final String NODE = "humanoid-b-node";

    @Test
    void defaultsCarryTheThreeWireNames() {
        Map<String, AnimationClip> clips = SyntheticClips.defaults(NODE);

        assertThat(clips).containsOnlyKeys("idle", "walking", "waving");
        assertThat(clips.get("idle").name()).isEqualTo("idle");
    }

    @Test
    void everyClipAnimatesTheNodeItWasGiven() {
        for (AnimationClip clip : SyntheticClips.defaults("spine-7").values()) {
            assertThat(clip.animatedNodes()).containsExactly("spine-7");
        }
    }

    @Test
    void idleIsASlowSmallBob() {
        AnimationClip idle = SyntheticClips.idle(NODE);

        assertThat(idle.duration()).isEqualTo(4.0);
        assertThat(idle.sampleAt(2.0).get(NODE).m13).isCloseTo(0.02, within(1e-9));
        assertThat(idle.sampleAt(0.0).get(NODE).m13).isZero();
    }

    @Test
    void walkingBobsAndTiltsAtStrideRate() {
        AnimationClip walk = SyntheticClips.walking(NODE);

        assertThat(walk.duration()).isEqualTo(1.0);
        var mid = walk.sampleAt(0.5).get(NODE);
        assertThat(mid.m13).isCloseTo(0.06, within(1e-9));   // the bob peak
        assertThat(mid.m01).isCloseTo(-Math.sin(-0.05), within(1e-9)); // the Z tilt
    }

    @Test
    void wavingIsAOneShotTiltThatStartsAndEndsNeutral() {
        AnimationClip wave = SyntheticClips.waving(NODE);

        assertThat(wave.duration()).isEqualTo(1.2);
        assertThat(wave.sampleAt(0.0).get(NODE).m01).isCloseTo(0.0, within(1e-9));
        assertThat(wave.sampleAt(0.6).get(NODE).m01)
                .isCloseTo(-Math.sin(0.35), within(1e-9)); // held mid-gesture
        assertThat(wave.sampleAt(1.2).get(NODE).m01).isCloseTo(0.0, within(1e-9));
    }
}
