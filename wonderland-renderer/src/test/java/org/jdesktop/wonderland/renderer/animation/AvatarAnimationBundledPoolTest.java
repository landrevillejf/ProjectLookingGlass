/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M7c: the animator against the REAL bundled pool.
 *
 * Mirrors AvatarLoadResolverTest's no-stubbing rule: the shipped .dae bytes
 * are read through ClasspathWorldStore, parsed, and turned into playable
 * clips on the CI JVM. Body A's hand-authored <library_animations> must
 * produce the walking/idle clips that move its actual node id; bodies B and
 * C ship no rig and must land on the synthetic fallback. A pool-content
 * regression fires here, headless -- the same argument M7b made for PDFBox:
 * what a headless JVM can prove, CI proves; only the Jogamp write is probe.
 */
package org.jdesktop.wonderland.renderer.animation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.jdesktop.wonderland.avatars.AvatarPool;
import org.jdesktop.wonderland.renderer.avatar.AvatarLoadResolver;
import org.jdesktop.wonderland.renderer.avatar.AvatarLoadResult;
import org.junit.jupiter.api.Test;

class AvatarAnimationBundledPoolTest {

    private static AvatarLoadResult loadPoolBody(String name) {
        AvatarLoadResult res = new AvatarLoadResolver().resolve(AvatarPool.urlFor(name));
        assertThat(res.isLoaded()).as("pool body %s loads", name).isTrue();
        return res;
    }

    @Test
    void bodyAclipsParseFromTheShippedBytes() {
        var document = loadPoolBody(AvatarPool.HUMANOID_A).document();

        AnimationLibrary library = AnimationLibrary.forDocument(document, "humanoid-a-node");

        assertThat(library.isSynthetic()).isFalse();
        assertThat(library.names()).containsExactly("walking", "idle");
    }

    @Test
    void bodyAwalkingClipBobAndTiltSampleFromTheirKeys() {
        var document = loadPoolBody(AvatarPool.HUMANOID_A).document();
        AnimationClip walking =
                AnimationLibrary.forDocument(document, "x").clip("walking");

        assertThat(walking.duration()).isEqualTo(1.0);
        var strideMid = walking.sampleAt(0.5).get("humanoid-a-node");
        assertThat(strideMid).isNotNull(); // the channel targets the real node id
        assertThat(strideMid.m13).isCloseTo(0.06, within(1e-9));  // the bob peak
        assertThat(strideMid.m01).isCloseTo(-Math.sin(-0.05), within(1e-9)); // the tilt

        var idle = AnimationLibrary.forDocument(document, "x").clip("idle");
        assertThat(idle.sampleAt(2.0).get("humanoid-a-node").m13)
                .isCloseTo(0.02, within(1e-9));
    }

    @Test
    void unriggedBodiesFallBackToTheSyntheticTrioOnTheirOwnNode() {
        for (String name : new String[] {AvatarPool.HUMANOID_B, AvatarPool.HUMANOID_C}) {
            var document = loadPoolBody(name).document();
            String rootNode = document.worldMatrices().get(0).node().id();

            AnimationLibrary library = AnimationLibrary.forDocument(document, rootNode);

            assertThat(library.isSynthetic()).as("body %s stays synthetic", name).isTrue();
            assertThat(library.clip("idle").animatedNodes()).containsExactly(rootNode);
        }
    }

    @Test
    void aDirectorOnTheRealBodyAClipsWalksTheirLoopHappily() {
        var document = loadPoolBody(AvatarPool.HUMANOID_A).document();
        AvatarAnimationDirector director =
                new AvatarAnimationDirector(AnimationLibrary.forDocument(document, "x"));

        assertThat(director.activeName()).isEqualTo("idle");
        director.requestAnimation("walking");
        director.advance(0.8);
        director.advance(0.4); // wraps the stride
        assertThat(director.time()).isBetween(0.0, 1.0);
        // every sampled pose of the real clip stays finite and complete
        var pose = director.activeClip().sampleAt(director.time()).get("humanoid-a-node");
        assertThat(pose).isNotNull();
        assertThat(Double.isFinite(pose.m13) && Double.isFinite(pose.m01)).isTrue();
    }
}
