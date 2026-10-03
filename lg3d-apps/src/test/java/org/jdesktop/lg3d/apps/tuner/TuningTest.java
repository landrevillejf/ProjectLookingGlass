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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure {@link Tuning} table and its nearest-string search: the string
 * counts and target frequencies of the built-in guitar/bass tunings, the
 * chromatic mode, and the cents-off-a-string computation. No AWT or audio device
 * is touched.
 */
class TuningTest {

    private static final float EPS = 0.5f;

    @Test
    @DisplayName("standard guitar has six strings, E2 low to E4 high")
    void guitarStandard() {
        Tuning t = Tuning.GUITAR_STANDARD;
        assertEquals(6, t.getStringCount());
        assertEquals(40, t.getStringMidi(0));
        assertEquals(64, t.getStringMidi(5));
        assertEquals(82.41f, t.getStringFrequency(0), EPS);   // low E2
        assertEquals(329.63f, t.getStringFrequency(5), EPS);  // high E4
        assertEquals("E2", t.getStringNote(0).getLabel());
        assertEquals("E4", t.getStringNote(5).getLabel());
        assertEquals("Guitar (E standard)", t.getName());
        assertEquals(t.getName(), t.toString());
    }

    @Test
    @DisplayName("standard bass has four strings, E1 low to G2 high")
    void bassStandard() {
        Tuning t = Tuning.BASS_STANDARD;
        assertEquals(4, t.getStringCount());
        assertEquals(41.20f, t.getStringFrequency(0), EPS);   // low E1
        assertEquals(98.00f, t.getStringFrequency(3), EPS);   // G2
        assertEquals("E1", t.getStringNote(0).getLabel());
        assertEquals("G2", t.getStringNote(3).getLabel());
    }

    @Test
    @DisplayName("drop D lowers only the sixth string to D2")
    void dropD() {
        Tuning t = Tuning.GUITAR_DROP_D;
        assertEquals(6, t.getStringCount());
        assertEquals("D2", t.getStringNote(0).getLabel());
        // The rest match standard tuning.
        assertEquals(Tuning.GUITAR_STANDARD.getStringMidi(1), t.getStringMidi(1));
        assertEquals(Tuning.GUITAR_STANDARD.getStringMidi(5), t.getStringMidi(5));
    }

    @Test
    @DisplayName("chromatic has no strings and never picks one")
    void chromatic() {
        Tuning t = Tuning.CHROMATIC;
        assertEquals(0, t.getStringCount());
        assertEquals(-1, t.closestString(440f));
        assertEquals(-1, t.closestString(82.41f));
        assertEquals(0f, t.centsOff(440f), 0.001f);
    }

    @Test
    @DisplayName("every detected string frequency maps to its own string index")
    void closestStringFindsEachString() {
        Tuning guitar = Tuning.GUITAR_STANDARD;
        for (int i = 0; i < guitar.getStringCount(); i++) {
            float f = guitar.getStringFrequency(i);
            assertEquals(i, guitar.closestString(f),
                    "frequency " + f + " should map to string " + i);
            assertEquals(0f, guitar.centsOff(f), 1f,
                    "an exact string frequency is 0 cents off");
        }
    }

    @Test
    @DisplayName("a pitch between two strings maps to the nearer one")
    void closestStringPicksNearest() {
        Tuning guitar = Tuning.GUITAR_STANDARD;
        // Just above the low E2 (string 0) is still nearer string 0 than A2.
        assertEquals(0, guitar.closestString(85.0f));
        // Just below A2 (string 1) is nearer string 1.
        assertEquals(1, guitar.closestString(107.0f));
    }

    @Test
    @DisplayName("centsOff is positive when sharp of the string and negative when flat")
    void centsOffSign() {
        Tuning guitar = Tuning.GUITAR_STANDARD;
        float a2 = guitar.getStringFrequency(1);            // 110 Hz
        float sharp = (float) (a2 * Math.pow(2.0, 20.0 / 1200.0));  // +20 cents
        float flat = (float) (a2 * Math.pow(2.0, -20.0 / 1200.0));  // -20 cents
        assertEquals(20f, guitar.centsOff(sharp), 2f);
        assertEquals(-20f, guitar.centsOff(flat), 2f);
        assertEquals(1, guitar.closestString(sharp));
        assertEquals(1, guitar.closestString(flat));
    }

    @Test
    @DisplayName("a non-positive or non-finite frequency has no nearest string")
    void degenerateFrequencies() {
        Tuning guitar = Tuning.GUITAR_STANDARD;
        assertEquals(-1, guitar.closestString(0f));
        assertEquals(-1, guitar.closestString(-100f));
        assertEquals(-1, guitar.closestString(Float.NaN));
        assertEquals(-1, guitar.closestString(Float.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("all built-in tunings are listed with distinct names")
    void allTuningsListed() {
        assertNotNull(Tuning.ALL);
        assertTrue(Tuning.ALL.contains(Tuning.CHROMATIC));
        assertTrue(Tuning.ALL.contains(Tuning.GUITAR_STANDARD));
        assertTrue(Tuning.ALL.contains(Tuning.BASS_STANDARD));
        long distinct = Tuning.ALL.stream().map(Tuning::getName).distinct().count();
        assertEquals(Tuning.ALL.size(), distinct, "tuning names must be unique");
    }

    @Test
    @DisplayName("a five-string bass reaches the low B0 string")
    void fiveStringBass() {
        Tuning t = Tuning.BASS_FIVE_STRING;
        assertEquals(5, t.getStringCount());
        assertEquals("B0", t.getStringNote(0).getLabel());
        assertEquals(30.87f, t.getStringFrequency(0), EPS);
    }

    @Test
    @DisplayName("a custom Drop C tuning can be built and resolves its strings")
    void customDropC() {
        // Drop C: C2 G2 C3 F3 A3 D4 - a set no built-in covers, proving the
        // model is flexible enough for any user tuning.
        Tuning dropC = Tuning.of("Guitar (Drop C)", 36, 43, 48, 53, 57, 62);
        assertEquals("Guitar (Drop C)", dropC.getName());
        assertEquals(6, dropC.getStringCount());
        assertEquals("C2", dropC.getStringNote(0).getLabel());
        assertEquals("G2", dropC.getStringNote(1).getLabel());
        assertEquals("D4", dropC.getStringNote(5).getLabel());
        assertEquals(65.41f, dropC.getStringFrequency(0), EPS);   // low C2
        // Each string frequency maps back to its own index, 0 cents off.
        for (int i = 0; i < dropC.getStringCount(); i++) {
            float f = dropC.getStringFrequency(i);
            assertEquals(i, dropC.closestString(f));
            assertEquals(0f, dropC.centsOff(f), 1f);
        }
    }

    @Test
    @DisplayName("of() and the constructor agree, and getStrings copies")
    void factoryAndAccessor() {
        int[] strings = {36, 43, 48};
        Tuning viaFactory = Tuning.of("X", strings);
        Tuning viaCtor = new Tuning("X", strings);
        assertEquals(viaCtor, viaFactory);
        assertArrayEquals(strings, viaFactory.getStrings());
        // The accessor returns a copy, so mutating it cannot corrupt the tuning.
        viaFactory.getStrings()[0] = 99;
        assertEquals(36, viaFactory.getStringMidi(0));
    }

    @Test
    @DisplayName("equality is by name and strings, so duplicates collapse")
    void valueEquality() {
        Tuning a = Tuning.of("Drop C", 36, 43, 48, 53, 57, 62);
        Tuning b = Tuning.of("Drop C", 36, 43, 48, 53, 57, 62);
        Tuning differentName = Tuning.of("Other", 36, 43, 48, 53, 57, 62);
        Tuning differentStrings = Tuning.of("Drop C", 36, 43, 48);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertTrue(!a.equals(differentName));
        assertTrue(!a.equals(differentStrings));
        assertTrue(!a.equals(null));
        assertTrue(!a.equals("not a tuning"));
        assertEquals(a, a);
    }
}
