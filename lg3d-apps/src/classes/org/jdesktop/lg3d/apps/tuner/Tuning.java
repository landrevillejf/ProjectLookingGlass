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

import java.util.List;

/**
 * A named instrument tuning: an ordered set of target notes (low string to high)
 * with the equal-tempered reference frequency of each, plus the search that maps
 * a detected pitch to the nearest string and its deviation in cents.
 *
 * <p>The built-in tunings cover the common guitar and bass sets, but a tuning is
 * just a name and an ordered list of string MIDI numbers, so any custom set can
 * be built with {@link #of(String, int...)} (e.g. Drop C = C2 G2 C3 F3 A3 D4 =
 * {@code Tuning.of("Guitar (Drop C)", 36, 43, 48, 53, 57, 62)}). User-defined
 * tunings are persisted by {@code TuningStore} and offered alongside the
 * built-ins. A {@link #CHROMATIC} tuning has no target strings and simply reports
 * the nearest note, which is the right mode for tuning by ear or for other
 * instruments.</p>
 *
 * <p>Like {@link Note}, everything here is pure {@code java.lang.Math} - no AWT,
 * no audio device - so the whole tuning table and its nearest-string search are
 * unit-testable headless. String MIDI numbers use the same reference as
 * {@link Note} (A4 = MIDI 69 = {@value Note#REFERENCE_A4} Hz).</p>
 */
public final class Tuning {

    /**
     * Free chromatic mode: no fixed strings, the tuner just reports the nearest
     * note. {@link #getStringCount()} is 0 and {@link #closestString(float)}
     * always returns -1.
     */
    public static final Tuning CHROMATIC =
            new Tuning("Chromatic", new int[0]);

    /** Standard six-string guitar, low to high: E2 A2 D3 G3 B3 E4. */
    public static final Tuning GUITAR_STANDARD =
            new Tuning("Guitar (E standard)", 40, 45, 50, 55, 59, 64);

    /** Drop-D guitar, low to high: D2 A2 D3 G3 B3 E4. */
    public static final Tuning GUITAR_DROP_D =
            new Tuning("Guitar (Drop D)", 38, 45, 50, 55, 59, 64);

    /** Open-G guitar, low to high: D2 G2 D3 G3 B3 D4. */
    public static final Tuning GUITAR_OPEN_G =
            new Tuning("Guitar (Open G)", 38, 43, 50, 55, 59, 62);

    /** Standard four-string bass, low to high: E1 A1 D2 G2. */
    public static final Tuning BASS_STANDARD =
            new Tuning("Bass (E standard)", 28, 33, 38, 43);

    /** Five-string bass, low to high: B0 E1 A1 D2 G2. */
    public static final Tuning BASS_FIVE_STRING =
            new Tuning("Bass (5-string)", 23, 28, 33, 38, 43);

    /** Standard ukulele / violin (high-to-low tuning), low to high: G4 C4 E4 A4. */
    public static final Tuning UKULELE =
            new Tuning("Ukulele (C)", 67, 60, 64, 69);

    /** Every built-in tuning, in menu order (chromatic first). */
    public static final List<Tuning> ALL = List.of(
            CHROMATIC, GUITAR_STANDARD, GUITAR_DROP_D, GUITAR_OPEN_G,
            BASS_STANDARD, BASS_FIVE_STRING, UKULELE);

    private final String name;
    private final int[] midi;

    /**
     * Builds a tuning from the MIDI numbers of its strings, low to high.
     *
     * @param name the human-readable tuning name
     * @param midi the string MIDI numbers (empty for a chromatic tuning)
     */
    public Tuning(String name, int... midi) {
        this.name = (name == null) ? "" : name;
        this.midi = (midi == null) ? new int[0] : midi.clone();
    }

    /**
     * Builds a custom tuning from the MIDI numbers of its strings, low to high.
     * This is the public factory the tuner's "add tuning" UI and the persisted
     * store use; it is exactly equivalent to the constructor and exists so a
     * caller can name the intent (e.g.
     * {@code Tuning.of("Guitar (Drop C)", 36, 43, 48, 53, 57, 62)}).
     *
     * @param name the human-readable tuning name
     * @param midi the string MIDI numbers, low to high (empty for chromatic)
     * @return the new tuning
     */
    public static Tuning of(String name, int... midi) {
        return new Tuning(name, midi);
    }

    /** The human-readable tuning name. */
    public String getName() {
        return name;
    }

    /**
     * A copy of the string MIDI numbers, low to high (empty for a chromatic
     * tuning). This is what the store persists and the "add tuning" UI reads
     * back; mutating the returned array never affects this tuning.
     *
     * @return the string MIDI numbers
     */
    public int[] getStrings() {
        return midi.clone();
    }

    /** The number of target strings (0 for {@link #CHROMATIC}). */
    public int getStringCount() {
        return midi.length;
    }

    /**
     * The MIDI number of a string.
     *
     * @param index the string index (0 = lowest)
     * @return the MIDI number
     * @throws IndexOutOfBoundsException if the index is out of range
     */
    public int getStringMidi(int index) {
        return midi[index];
    }

    /**
     * The target note of a string (in tune, zero cents).
     *
     * @param index the string index (0 = lowest)
     * @return the note for that string
     */
    public Note getStringNote(int index) {
        return Note.fromMidi(midi[index]);
    }

    /** The equal-tempered target frequency of a string, in Hz. */
    public float getStringFrequency(int index) {
        return Note.frequencyOfMidi(midi[index]);
    }

    /**
     * The index of the string whose target note is nearest (in log-frequency /
     * MIDI space) to {@code frequency}.
     *
     * @param frequency the detected pitch in Hz
     * @return the nearest string index, or -1 for a chromatic tuning, a
     *         non-positive frequency, or a tuning with no strings
     */
    public int closestString(float frequency) {
        if (midi.length == 0 || frequency <= 0f
                || Float.isNaN(frequency) || Float.isInfinite(frequency)) {
            return -1;
        }
        float midiFloat = Note.midiOf(frequency);
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < midi.length; i++) {
            float distance = Math.abs(midi[i] - midiFloat);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /**
     * The deviation, in cents, of {@code frequency} from the nearest string in
     * this tuning. Negative is flat, positive is sharp.
     *
     * @param frequency the detected pitch in Hz
     * @return the cents deviation from the nearest string, or 0 when there is no
     *         nearest string (chromatic tuning or non-positive frequency)
     */
    public float centsOff(float frequency) {
        int index = closestString(frequency);
        if (index < 0) {
            return 0f;
        }
        return (Note.midiOf(frequency) - midi[index]) * 100.0f;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Tuning)) {
            return false;
        }
        Tuning that = (Tuning) other;
        return name.equals(that.name) && java.util.Arrays.equals(midi, that.midi);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + java.util.Arrays.hashCode(midi);
    }

    @Override
    public String toString() {
        return name;
    }
}
