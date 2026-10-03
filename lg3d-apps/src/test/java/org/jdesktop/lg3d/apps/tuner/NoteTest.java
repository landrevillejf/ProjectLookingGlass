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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure equal-temperament maths of {@link Note}: the frequency/MIDI
 * conversions, the nearest-note resolution, the cents deviation and the
 * in-tune test. No AWT or audio device is touched.
 */
class NoteTest {

    private static final float EPS = 0.5f;

    @Test
    @DisplayName("A4 (440 Hz) resolves to note A, octave 4, zero cents")
    void concertA() {
        Note n = Note.closestTo(440.0f);
        assertNotNull(n);
        assertEquals("A", n.getName());
        assertEquals(4, n.getOctave());
        assertEquals("A4", n.getLabel());
        assertEquals(Note.A4_MIDI, n.getMidi());
        assertEquals(0f, n.getCents(), 0.5f);
        assertEquals(440.0f, n.getReferenceFrequency(), EPS);
        assertTrue(n.isInTune(5f));
    }

    @Test
    @DisplayName("the guitar and bass open strings resolve to their letter names")
    void openStrings() {
        assertEquals("E", Note.closestTo(82.41f).getName());   // guitar low E2
        assertEquals(2, Note.closestTo(82.41f).getOctave());
        assertEquals("A", Note.closestTo(110.00f).getName());  // A2
        assertEquals("D", Note.closestTo(146.83f).getName());  // D3
        assertEquals("G", Note.closestTo(196.00f).getName());  // G3
        assertEquals("B", Note.closestTo(246.94f).getName());  // B3
        assertEquals("E", Note.closestTo(329.63f).getName());  // high E4
        assertEquals(4, Note.closestTo(329.63f).getOctave());
        assertEquals("E", Note.closestTo(41.20f).getName());   // bass low E1
        assertEquals(1, Note.closestTo(41.20f).getOctave());
    }

    @Test
    @DisplayName("a sharp frequency resolves to the sharp note name")
    void sharps() {
        // C#4 / Db4 is ~277.18 Hz.
        Note n = Note.closestTo(277.18f);
        assertNotNull(n);
        assertEquals("C#", n.getName());
        assertEquals(4, n.getOctave());
    }

    @Test
    @DisplayName("the frequency of a MIDI number round-trips through midiOf")
    void frequencyAndMidiRoundTrip() {
        for (int midi = 20; midi <= 100; midi++) {
            float f = Note.frequencyOfMidi(midi);
            assertEquals(midi, Math.round(Note.midiOf(f)),
                    "MIDI " + midi + " at " + f + " Hz");
        }
        assertEquals(440.0f, Note.frequencyOfMidi(Note.A4_MIDI), EPS);
        assertEquals(261.63f, Note.frequencyOfMidi(60), EPS);  // middle C
    }

    @Test
    @DisplayName("a frequency a quarter tone sharp reports ~+50 cents")
    void centsDeviation() {
        // A4 sharp by half a semitone (~+50 cents): 440 * 2^(0.5/12) = 452.9 Hz.
        float sharp = (float) (440.0 * Math.pow(2.0, 0.5 / 12.0));
        Note n = Note.closestTo(sharp);
        assertNotNull(n);
        // It rounds up to A#4 (50 cents above A4), so it sits near -50 of A#4
        // or +50 of A4; the absolute deviation from the nearest note is ~50.
        assertEquals(50f, Math.abs(n.getCents()), 3f);
        assertFalse(n.isInTune(5f));
    }

    @Test
    @DisplayName("a slightly flat pitch reports negative cents")
    void flatIsNegative() {
        // 2% below 440 Hz is flat.
        Note n = Note.closestTo(440.0f * 0.98f);
        assertNotNull(n);
        assertTrue(n.getCents() < 0f, "flat pitch should be negative cents: " + n);
    }

    @Test
    @DisplayName("fromMidi builds an in-tune note with zero cents")
    void fromMidi() {
        Note n = Note.fromMidi(Note.A4_MIDI);
        assertEquals("A4", n.getLabel());
        assertEquals(0f, n.getCents());
        assertEquals(440.0f, n.getReferenceFrequency(), EPS);
        assertEquals(440.0f, n.getFrequency(), EPS);
        assertTrue(n.isInTune(1f));
    }

    @Test
    @DisplayName("fromMidi clamps out-of-range MIDI numbers")
    void fromMidiClamps() {
        assertEquals(0, Note.fromMidi(-5).getMidi());
        assertEquals(127, Note.fromMidi(999).getMidi());
    }

    @Test
    @DisplayName("non-positive and non-finite frequencies yield no note")
    void degenerateFrequencies() {
        assertNull(Note.closestTo(0f));
        assertNull(Note.closestTo(-440f));
        assertNull(Note.closestTo(Float.NaN));
        assertNull(Note.closestTo(Float.POSITIVE_INFINITY));
        assertTrue(Float.isNaN(Note.midiOf(0f)));
        assertTrue(Float.isNaN(Note.midiOf(-1f)));
    }

    @Test
    @DisplayName("the detected frequency is preserved on the resolved note")
    void keepsDetectedFrequency() {
        Note n = Note.closestTo(442.0f);
        assertNotNull(n);
        assertEquals(442.0f, n.getFrequency(), 0.001f);
        assertNotNull(n.toString());
        assertTrue(n.toString().contains("Hz"));
    }

    @Test
    @DisplayName("the in-tune tolerance is symmetric about zero cents")
    void inTuneToleranceIsAbsolute() {
        Note sharp = Note.closestTo(Note.frequencyOfMidi(69) * 1.01f);
        assertNotNull(sharp);
        assertTrue(sharp.isInTune(50f), "a wide tolerance accepts a sharp pitch");
        assertTrue(sharp.isInTune(-50f), "the tolerance magnitude is what matters");
    }
}
