/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M7c: the playback policy, asserted on an explicit
 * clock -- no wall time, no threads, no Jogamp.
 */
package org.jdesktop.wonderland.renderer.animation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AvatarAnimationDirectorTest {

    private AnimationLibrary library;

    private static AnimationClip clip(String name, double duration) {
        Map<String, AnimationClip.NodeTracks> nodes = new LinkedHashMap<>();
        AnimationClip.NodeTracks tracks = new AnimationClip.NodeTracks();
        tracks.translation(1, new KeyframeTrack(
                new double[] {0, duration}, new double[] {0, 1},
                KeyframeTrack.Interpolation.LINEAR));
        nodes.put("body", tracks);
        return new AnimationClip(name, nodes);
    }

    @BeforeEach
    void buildLibrary() {
        Map<String, AnimationClip> clips = new LinkedHashMap<>();
        clips.put("idle", clip("idle", 4.0));
        clips.put("walking", clip("walking", 1.0));
        clips.put("waving", clip("waving", 1.2));
        library = AnimationLibrary.of(clips);
    }

    @Test
    void aFreshDirectorAlreadyPlaysIdleBecauseThe2006AvatarsNeverFroze() {
        AvatarAnimationDirector d = new AvatarAnimationDirector(library);

        assertThat(d.isPlaying()).isTrue();
        assertThat(d.activeName()).isEqualTo("idle");
    }

    @Test
    void nullAndBlankRequestsResolveToIdle() {
        AvatarAnimationDirector d = new AvatarAnimationDirector(library);
        d.requestAnimation("walking");

        d.requestAnimation(null);
        assertThat(d.activeName()).isEqualTo("idle");
        d.requestAnimation("walking");
        d.requestAnimation("   ");
        assertThat(d.activeName()).isEqualTo("idle");
    }

    @Test
    void anUnknownNameFallsBackToIdleLikeThe2006MissingJmeFile() {
        AvatarAnimationDirector d = new AvatarAnimationDirector(library);

        d.requestAnimation("breakdancing-on-the-ceiling");
        assertThat(d.activeName()).isEqualTo("idle");
    }

    @Test
    void theWalkAliasesResolveToTheWalkingClip() {
        AvatarAnimationDirector d = new AvatarAnimationDirector(library);

        for (String alias : new String[] {"walk", "walking", "run", "RUNNING", "Run"}) {
            d.requestAnimation(alias);
            assertThat(d.activeName()).as("alias %s", alias).isEqualTo("walking");
        }
        d.requestAnimation("sitting");
        assertThat(d.activeName()).isEqualTo("idle");
        d.requestAnimation("onPhone");
        assertThat(d.activeName()).isEqualTo("idle");
        d.requestAnimation("wave");
        assertThat(d.activeName()).isEqualTo("waving");
    }

    @Test
    void anExactLibraryHitWinsBeforeTheAliasTable() {
        Map<String, AnimationClip> withRunning = new LinkedHashMap<>();
        withRunning.put("idle", clip("idle", 4));
        withRunning.put("running", clip("running", 0.6));
        AvatarAnimationDirector d =
                new AvatarAnimationDirector(AnimationLibrary.of(withRunning));

        d.requestAnimation("running");
        assertThat(d.activeName()).isEqualTo("running"); // not aliased to walking
    }

    @Test
    void loopingClipsWrapAtTheirBoundary() {
        AvatarAnimationDirector d = new AvatarAnimationDirector(library);
        d.requestAnimation("walking"); // duration 1.0

        d.advance(0.8);
        assertThat(d.time()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-9));
        d.advance(0.4); // crosses the boundary: 1.2 % 1.0
        assertThat(d.time()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(d.activeName()).isEqualTo("walking"); // still playing the loop
    }

    @Test
    void gestureClipsPlayOnceThenRevertToIdle() {
        AvatarAnimationDirector d = new AvatarAnimationDirector(library);
        d.requestAnimation("waving"); // duration 1.2, not in LOOPING

        d.advance(1.0);
        assertThat(d.activeName()).isEqualTo("waving");
        d.advance(0.3); // past the end
        assertThat(d.activeName()).isEqualTo("idle");
        assertThat(d.time()).isZero();
    }

    @Test
    void reRequestingAGestureRestartsItButReRequestingALoopKeepsItsPhase() {
        AvatarAnimationDirector d = new AvatarAnimationDirector(library);

        d.requestAnimation("waving");
        d.advance(0.5);
        d.requestAnimation("waving"); // a second click mid-wave
        assertThat(d.time()).isZero();

        d.requestAnimation("walking");
        d.advance(0.7);
        d.requestAnimation("walking"); // a fresh move message while walking
        assertThat(d.time()).isCloseTo(0.7, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void sloppyClockInputIsIgnoredNotAccumulated() {
        AvatarAnimationDirector d = new AvatarAnimationDirector(library);
        double before = d.time();

        d.advance(-1.0);
        d.advance(Double.NaN);
        d.advance(Double.POSITIVE_INFINITY);
        d.advance(0.0);
        assertThat(d.time()).isEqualTo(before);
    }

    @Test
    void anEmptyLibraryPlaysNothingAndSurvivesEveryRequest() {
        AvatarAnimationDirector d = new AvatarAnimationDirector(AnimationLibrary.of(Map.of()));

        assertThat(d.isPlaying()).isFalse();
        d.requestAnimation("walking");
        d.advance(1.0);
        assertThat(d.isPlaying()).isFalse();
        assertThat(d.activeName()).isNull();
        // even a null library is not a crash
        assertThat(new AvatarAnimationDirector(null).isPlaying()).isFalse();
    }

    @Test
    void aZeroDurationLoopDoesNotDivideByZero() {
        Map<String, AnimationClip> clips = new LinkedHashMap<>();
        clips.put("flat", clip("flat", 0.0));
        AvatarAnimationDirector d = new AvatarAnimationDirector(AnimationLibrary.of(clips));
        d.requestAnimation("flat"); // flat = duration 0; LOOPING? "flat" is not in the table

        d.advance(1.0); // one-shot path: next >= 0 -> revert to idle; idle missing -> stop
        assertThat(d.isPlaying()).isFalse();
    }

    @Test
    void resolveClipNameIsPureAndVisibleForTheProbe() {
        AvatarAnimationDirector d = new AvatarAnimationDirector(library);

        assertThat(d.resolveClipName("WALKING")).isEqualTo("walking");
        assertThat(d.resolveClipName("talking")).isEqualTo("idle"); // unknown -> idle
        assertThat(d.resolveClipName(null)).isEqualTo("idle");
    }

    @Test
    void switchingClipsResetsThePlayHead() {
        AvatarAnimationDirector d = new AvatarAnimationDirector(library);
        d.requestAnimation("idle");
        d.advance(3.5);

        d.requestAnimation("walking");
        assertThat(d.time()).isZero();
        assertThat(d.activeClip().duration()).isEqualTo(1.0);
    }
}
