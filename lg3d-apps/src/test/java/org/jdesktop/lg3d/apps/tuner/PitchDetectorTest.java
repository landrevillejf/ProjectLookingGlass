/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.tuner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the YIN {@link PitchDetector} entirely headless by feeding it synthetic
 * signals: a pure sine wave of a known fundamental must be recovered to within a
 * fraction of a semitone across the whole guitar/bass range, and silence, noise
 * and degenerate buffers must come back unvoiced. No audio device is opened.
 */
class PitchDetectorTest {

    private static final int RATE = 44100;
    private static final int WINDOW = 8192;

    /** A pure sine of {@code freq} Hz at the test sample rate. */
    private static float[] sine(float freq, int n) {
        float[] buf = new float[n];
        for (int i = 0; i < n; i++) {
            buf[i] = (float) Math.sin(2.0 * Math.PI * freq * i / RATE);
        }
        return buf;
    }

    /** The detected frequency of a synthetic sine, asserted voiced. */
    private static float detectFreq(PitchDetector d, float freq) {
        PitchDetector.Pitch pitch = d.detect(sine(freq, WINDOW));
        assertTrue(pitch.isVoiced(), "expected a voiced pitch at " + freq + " Hz");
        return pitch.getFrequency();
    }

    @Test
    @DisplayName("a 440 Hz sine is detected as A4 within a few Hz")
    void detectsConcertA() {
        PitchDetector d = new PitchDetector(RATE);
        float f = detectFreq(d, 440.0f);
        assertEquals(440.0f, f, 2.0f, "440 Hz sine");
    }

    @Test
    @DisplayName("every standard guitar open string is recovered accurately")
    void detectsGuitarStrings() {
        PitchDetector d = new PitchDetector(RATE);
        // E2 A2 D3 G3 B3 E4
        float[] strings = {82.41f, 110.00f, 146.83f, 196.00f, 246.94f, 329.63f};
        for (float f : strings) {
            float detected = detectFreq(d, f);
            // Within a quarter semitone (~1.5%) is far tighter than a tuner needs.
            assertTrue(Math.abs(detected - f) / f < 0.015f,
                    "guitar string " + f + " Hz detected as " + detected);
        }
    }

    @Test
    @DisplayName("every standard bass open string is recovered accurately")
    void detectsBassStrings() {
        PitchDetector d = new PitchDetector(RATE);
        // E1 A1 D2 G2
        float[] strings = {41.20f, 55.00f, 73.42f, 98.00f};
        for (float f : strings) {
            float detected = detectFreq(d, f);
            assertTrue(Math.abs(detected - f) / f < 0.02f,
                    "bass string " + f + " Hz detected as " + detected);
        }
    }

    @Test
    @DisplayName("the detected note maps to the expected letter name")
    void detectedPitchResolvesToNote() {
        PitchDetector d = new PitchDetector(RATE);
        Note e2 = Note.closestTo(detectFreq(d, 82.41f));
        assertNotNull(e2);
        assertEquals("E", e2.getName());
        assertEquals(2, e2.getOctave());
        assertTrue(e2.isInTune(10f), "a clean sine is in tune: " + e2);
    }

    @Test
    @DisplayName("silence and a null/short buffer come back unvoiced")
    void silenceIsUnvoiced() {
        PitchDetector d = new PitchDetector(RATE);
        assertFalse(d.detect(new float[WINDOW]).isVoiced(), "all-zero silence");
        assertFalse(d.detect(null).isVoiced(), "null buffer");
        assertFalse(d.detect(new float[8]).isVoiced(), "buffer too short to analyse");
        assertFalse(d.detect(new float[]{0.5f}).isVoiced(), "single sample");
    }

    @Test
    @DisplayName("white noise does not resolve to a confident pitch")
    void noiseIsNotConfident() {
        PitchDetector d = new PitchDetector(RATE);
        Random rng = new Random(42);
        float[] noise = new float[WINDOW];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (rng.nextFloat() * 2f) - 1f;
        }
        PitchDetector.Pitch pitch = d.detect(noise);
        // Noise either comes back unvoiced or with a very low periodicity.
        assertTrue(!pitch.isVoiced() || pitch.getClarity() < 0.5f,
                "noise should not look like a clear tone: " + pitch);
    }

    @Test
    @DisplayName("a clean sine reports high periodicity clarity")
    void clarityIsHighForATone() {
        PitchDetector d = new PitchDetector(RATE);
        PitchDetector.Pitch pitch = d.detect(sine(220.0f, WINDOW));
        assertTrue(pitch.isVoiced());
        assertTrue(pitch.getClarity() > 0.8f, "clarity should be high: " + pitch);
    }

    @Test
    @DisplayName("an amplitude-scaled sine yields the same frequency")
    void amplitudeDoesNotShiftPitch() {
        PitchDetector d = new PitchDetector(RATE);
        float[] loud = sine(196.0f, WINDOW);
        for (int i = 0; i < loud.length; i++) {
            loud[i] *= 0.1f;   // quiet but still above the noise floor
        }
        PitchDetector.Pitch pitch = d.detect(loud);
        assertTrue(pitch.isVoiced());
        assertEquals(196.0f, pitch.getFrequency(), 3.0f);
    }

    @Test
    @DisplayName("the lag bounds follow the sample rate and frequency range")
    void lagBoundsFollowSampleRate() {
        PitchDetector d = new PitchDetector(RATE);
        assertEquals(RATE, d.getSampleRate());
        // minTau ~ rate / MAX_FREQUENCY, maxTau ~ rate / MIN_FREQUENCY.
        assertEquals((int) Math.floor(RATE / PitchDetector.MAX_FREQUENCY), d.getMinTau());
        assertEquals((int) Math.ceil(RATE / PitchDetector.MIN_FREQUENCY), d.getMaxTau());
        assertTrue(d.getMinTau() < d.getMaxTau());
    }

    @Test
    @DisplayName("a 48 kHz detector still recovers a 440 Hz sine")
    void worksAtOtherSampleRates() {
        int rate = 48000;
        float[] buf = new float[WINDOW];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / rate);
        }
        PitchDetector.Pitch pitch = new PitchDetector(rate).detect(buf);
        assertTrue(pitch.isVoiced());
        assertEquals(440.0f, pitch.getFrequency(), 2.0f);
    }

    @Test
    @DisplayName("a non-positive sample rate is rejected")
    void rejectsBadSampleRate() {
        assertThrows(IllegalArgumentException.class, () -> new PitchDetector(0));
        assertThrows(IllegalArgumentException.class, () -> new PitchDetector(-1));
    }

    @Test
    @DisplayName("the threshold and noise floor are clamped into range")
    void clampsConstructorArguments() {
        PitchDetector d = new PitchDetector(RATE, 5f, -3f);
        assertEquals(RATE, d.getSampleRate());
        // A huge threshold clamps to 0.5, a negative floor to 0; detection still runs.
        assertTrue(d.detect(sine(110f, WINDOW)).isVoiced());
    }

    @Test
    @DisplayName("an unvoiced result carries a non-positive frequency")
    void unvoicedResultShape() {
        PitchDetector.Pitch unvoiced = PitchDetector.Pitch.unvoiced();
        assertFalse(unvoiced.isVoiced());
        assertTrue(unvoiced.getFrequency() <= 0f);
        assertEquals(0f, unvoiced.getClarity());
        assertNotNull(unvoiced.toString());
    }

    @Test
    @DisplayName("a voiced result clamps its clarity into [0, 1]")
    void clarityIsClamped() {
        PitchDetector.Pitch p = new PitchDetector.Pitch(440f, 5f);
        assertEquals(1f, p.getClarity());
        PitchDetector.Pitch q = new PitchDetector.Pitch(440f, -2f);
        assertEquals(0f, q.getClarity());
        assertNotNull(p.toString());
    }
}
