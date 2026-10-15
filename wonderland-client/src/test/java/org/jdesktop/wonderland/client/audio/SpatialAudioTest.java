/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client P4.1: the spatial maths, headless.
 *
 * Each assert is a physical statement about sound in a room: in front is
 * centred, at the right ear is full right, behind is centred again, twice as
 * far is half as loud, and past the maximum distance the world goes quiet.
 * What no test here claims: that any of this reaches a speaker -- the mixer
 * half is JavaSoundBackend, and its honest clause says a device is proven on
 * a machine with speakers, not in CI.
 */
package org.jdesktop.wonderland.client.audio;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SpatialAudioTest {

    private static final SpatialAudio.Listener AHEAD =
            new SpatialAudio.Listener(0, 0, 0, 0); // looks down +Z, right ear +X

    /** A source at (x, y, z) carrying 10 m with a 1 m reference. */
    private static SpatialAudio.Source source(float x, float y, float z) {
        return new SpatialAudio.Source(x, y, z, 1f, 10f, 1f);
    }

    @Test
    void aSoundInsideTheReferenceDistanceIsDeliveredWhole() {
        SpatialAudio.Mix mix = SpatialAudio.mix(AHEAD, source(0, 0, 0.5f));

        assertThat(mix.gain()).isCloseTo(1f, within(1e-6f));
        assertThat(mix.audible()).isTrue();
    }

    @Test
    void aSoundTwiceAsFarIsHalfAsLoudAndQuietDiesAtTheMaxDistance() {
        // 2 m: ref/d = 1/2; 5 m: 1/5; 10 m and past: the clip OpenAL applied.
        assertThat(SpatialAudio.mix(AHEAD, source(0, 0, 2)).gain())
                .isCloseTo(0.5f, within(1e-6f));
        assertThat(SpatialAudio.mix(AHEAD, source(0, 0, 5)).gain())
                .isCloseTo(0.2f, within(1e-6f));
        assertThat(SpatialAudio.mix(AHEAD, source(0, 0, 10)).gain())
                .as("at the max distance, exactly")
                .isZero();
        assertThat(SpatialAudio.mix(AHEAD, source(0, 0, 50)).audible()).isFalse();
    }

    @Test
    void straightAheadAndDeadBehindAreCentredNotLopsided() {
        assertThat(SpatialAudio.mix(AHEAD, source(0, 0, 2)).pan())
                .isCloseTo(0f, within(1e-6f));
        assertThat(SpatialAudio.mix(AHEAD, source(0, 0, -2)).pan())
                .as("behind is still behind the nose, not suddenly left")
                .isCloseTo(0f, within(1e-6f));
    }

    @Test
    void theRightEarIsFullRightAndTheLeftEarFullLeft() {
        assertThat(SpatialAudio.mix(AHEAD, source(0.5f, 0, 0)).pan())
                .isCloseTo(1f, within(1e-6f));
        assertThat(SpatialAudio.mix(AHEAD, source(-0.5f, 0, 0)).pan())
                .isCloseTo(-1f, within(1e-6f));
    }

    @Test
    void turningTheHeadMovesTheSoundAcrossTheEars() {
        // The same +X sound sits ahead once the listener turns toward it.
        SpatialAudio.Source east = source(2, 0, 0);
        SpatialAudio.Listener facingEast =
                new SpatialAudio.Listener(0, 0, 0, (float) (Math.PI / 2));

        assertThat(SpatialAudio.mix(AHEAD, east).pan()).isCloseTo(1f, within(1e-6f));
        assertThat(SpatialAudio.mix(facingEast, east).pan())
                .as("now dead ahead")
                .isCloseTo(0f, within(1e-6f));
    }

    @Test
    void aSoundOnTopOfTheListenerPicksNeitherEar() {
        assertThat(SpatialAudio.panOf(AHEAD, source(0, 1.5f, 0)))
                .as("directly above: no side to invent")
                .isZero();
        assertThat(SpatialAudio.panOf(AHEAD, source(0, 0, 0)))
                .isZero();
    }

    @Test
    void aSourceThatCannotCarryAtAllIsSilentRatherThanSurprising() {
        SpatialAudio.Source broken = new SpatialAudio.Source(0, 0, 1, 0f, 10f, 1f);
        SpatialAudio.Source muted = new SpatialAudio.Source(0, 0, 1, 1f, 10f, 0f);
        SpatialAudio.Source noCutOff = new SpatialAudio.Source(0, 0, 3, 1f, 0f, 1f);

        assertThat(SpatialAudio.mix(AHEAD, broken).gain()).isZero();
        assertThat(SpatialAudio.mix(AHEAD, muted).gain()).isZero();
        assertThat(SpatialAudio.mix(AHEAD, noCutOff).gain())
                .as("a zero max distance means no cut-off, not silence")
                .isCloseTo(1f / 3f, within(1e-6f));
    }

    @Test
    void heightCountsInTheDistanceLikeItDoesInARoom() {
        // 3 m up and 4 m along is 5 m away: gain 1/5, not 1/4.
        SpatialAudio.Mix mix = SpatialAudio.mix(AHEAD, source(0, 3, 4));

        assertThat(mix.gain()).isCloseTo(0.2f, within(1e-6f));
    }
}
