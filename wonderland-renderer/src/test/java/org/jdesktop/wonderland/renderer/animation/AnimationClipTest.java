/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/* wonderland-renderer M7c: local-matrix composition from sampled channels. */
package org.jdesktop.wonderland.renderer.animation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnimationClipTest {

    private static KeyframeTrack ramp(double value) {
        return new KeyframeTrack(new double[] {0}, new double[] {value},
                KeyframeTrack.Interpolation.LINEAR);
    }

    @Test
    void anEmptyTrackSetIsEmptyAndNeverEntersAClip() {
        AnimationClip.NodeTracks tracks = new AnimationClip.NodeTracks();

        assertThat(tracks.isEmpty()).isTrue();
        Map<String, AnimationClip.NodeTracks> nodes = new LinkedHashMap<>();
        nodes.put("n", tracks);
        assertThat(new AnimationClip("c", nodes).animatedNodes()).isEmpty();
    }

    @Test
    void defaultsAreANeutralPose() {
        AnimationClip.NodeTracks tracks = new AnimationClip.NodeTracks();
        tracks.translation(1, ramp(1)); // only Y set: everything else neutral

        var m = tracks.sampleMatrix(0.0);
        assertThat(m.m13).isEqualTo(1.0);
        assertThat(m.m03).isZero();
        assertThat(m.m00).isEqualTo(1.0); // rotation identity, scale 1
        assertThat(m.m22).isEqualTo(1.0);
    }

    @Test
    void axisAngleRotationAboutZBy90DegreesMatchesTheHandComputedMatrix() {
        AnimationClip.NodeTracks tracks = new AnimationClip.NodeTracks();
        tracks.rotationAxis(2, ramp(1));
        tracks.rotationAngle(new KeyframeTrack(new double[] {0},
                new double[] {Math.PI / 2}, KeyframeTrack.Interpolation.LINEAR));

        var m = tracks.sampleMatrix(0.0);
        assertThat(m.m00).isCloseTo(0.0, within(1e-9));
        assertThat(m.m01).isCloseTo(-1.0, within(1e-9));
        assertThat(m.m10).isCloseTo(1.0, within(1e-9));
        assertThat(m.m11).isCloseTo(0.0, within(1e-9));
        assertThat(m.m33).isEqualTo(1.0);
    }

    @Test
    void aNonUnitAxisIsNormalisedBeforeRotating() {
        AnimationClip.NodeTracks tracks = new AnimationClip.NodeTracks();
        tracks.rotationAxis(2, ramp(3)); // axis (0,0,3): same rotation as (0,0,1)
        tracks.rotationAngle(ramp(Math.PI));

        var m = tracks.sampleMatrix(0.0);
        assertThat(m.m00).isCloseTo(-1.0, within(1e-9));
        assertThat(m.m11).isCloseTo(-1.0, within(1e-9));
        assertThat(m.m22).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void aZeroLengthAxisRotatesNothingWhateverTheAngle() {
        AnimationClip.NodeTracks tracks = new AnimationClip.NodeTracks();
        tracks.rotationAxis(0, ramp(0));
        tracks.rotationAxis(1, ramp(0));
        tracks.rotationAxis(2, ramp(0));
        tracks.rotationAngle(ramp(1.5));

        var m = tracks.sampleMatrix(0.0);
        assertThat(m.m00).isEqualTo(1.0);
        assertThat(m.m01).isZero();
    }

    @Test
    void anAngleWithoutAxisTracksDefaultsToTheZAxis() {
        // the stride-1 angle-only channel: the authored default of the port
        AnimationClip.NodeTracks tracks = new AnimationClip.NodeTracks();
        tracks.rotationAngle(ramp(Math.PI / 2));

        var m = tracks.sampleMatrix(0.0);
        assertThat(m.m01).isCloseTo(-1.0, within(1e-9)); // a Z rotation
    }

    @Test
    void scaleMultipliesBeforeTranslationInTheTorsOrder() {
        AnimationClip.NodeTracks tracks = new AnimationClip.NodeTracks();
        tracks.scale(0, ramp(2));
        tracks.translation(0, ramp(5));

        var m = tracks.sampleMatrix(0.0);
        assertThat(m.m00).isEqualTo(2.0);
        assertThat(m.m03).isEqualTo(5.0); // T*R*S: the translation is not scaled
    }

    @Test
    void durationIsTheLastKeyAnywhereInTheClip() {
        Map<String, AnimationClip.NodeTracks> nodes = new LinkedHashMap<>();
        AnimationClip.NodeTracks a = new AnimationClip.NodeTracks();
        a.translation(0, new KeyframeTrack(new double[] {0, 4}, new double[] {0, 1},
                KeyframeTrack.Interpolation.LINEAR));
        nodes.put("a", a);
        AnimationClip.NodeTracks b = new AnimationClip.NodeTracks();
        b.translation(1, new KeyframeTrack(new double[] {0, 7, 9}, new double[] {0, 1, 2},
                KeyframeTrack.Interpolation.LINEAR));
        nodes.put("b", b);

        assertThat(new AnimationClip("two-nodes", nodes).duration()).isEqualTo(9.0);
    }

    @Test
    void sampleAtReturnsOneMatrixPerAnimatedNodeAndIsolatesTheRest() {
        Map<String, AnimationClip.NodeTracks> nodes = new LinkedHashMap<>();
        AnimationClip.NodeTracks a = new AnimationClip.NodeTracks();
        a.translation(1, new KeyframeTrack(new double[] {0, 1}, new double[] {0, 10},
                KeyframeTrack.Interpolation.LINEAR));
        nodes.put("a", a);
        AnimationClip.NodeTracks c = new AnimationClip.NodeTracks();
        c.scale(0, ramp(3));
        nodes.put("c", c); // scale-only node: still an override, must appear
        AnimationClip clip = new AnimationClip("mixed", nodes);

        Map<String, org.jogamp.vecmath.Matrix4d> pose = clip.sampleAt(0.5);
        assertThat(pose).containsOnlyKeys("a", "c");
        assertThat(pose.get("a").m13).isCloseTo(5.0, within(1e-9));
        assertThat(pose.get("a").m00).isEqualTo(1.0);
        assertThat(pose.get("c").m00).isEqualTo(3.0);
        assertThat(pose.get("c").m13).isZero();
    }

    @Test
    void clipNameAndNullValueFilteringFollowTheConstructorContract() {
        Map<String, AnimationClip.NodeTracks> nodes = new LinkedHashMap<>();
        AnimationClip.NodeTracks good = new AnimationClip.NodeTracks();
        good.translation(0, ramp(1));
        nodes.put("good", good);
        nodes.put("null", null);
        AnimationClip.NodeTracks empty = new AnimationClip.NodeTracks();
        nodes.put("empty", empty);

        AnimationClip clip = new AnimationClip("named", nodes);
        assertThat(clip.name()).isEqualTo("named");
        assertThat(clip.animatedNodes()).containsExactly("good");
    }

    @Test
    void nodeTracksExposeTheEndTimeOfTheirLatestKey() {
        AnimationClip.NodeTracks tracks = new AnimationClip.NodeTracks();
        assertThat(tracks.endTime()).isZero();
        tracks.translation(2, new KeyframeTrack(new double[] {1, 6}, new double[] {0, 1},
                KeyframeTrack.Interpolation.LINEAR));
        assertThat(tracks.endTime()).isEqualTo(6.0);
    }
}
