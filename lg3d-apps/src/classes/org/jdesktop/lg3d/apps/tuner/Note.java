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

/**
 * An immutable value object mapping a detected pitch frequency to the nearest
 * equal-tempered note: its letter name (with sharps), octave, MIDI number, the
 * ideal reference frequency of that note and the deviation of the detected pitch
 * from it in cents (-50..+50).
 *
 * <p>Every method here is pure {@code java.lang.Math} over a frequency - no AWT,
 * no audio device, no Java&nbsp;3D - so the whole note/cents model is
 * unit-testable headless. The tuning maths uses the standard twelve-tone
 * equal-temperament formula: MIDI note {@code n} has frequency
 * {@code 440 * 2^((n - 69) / 12)}, so A4 (MIDI 69) is the {@value #REFERENCE_A4}
 * Hz reference and one semitone is 100 cents.</p>
 */
public final class Note {

    /** The reference pitch of A4 (MIDI 69) in Hz. */
    public static final float REFERENCE_A4 = 440.0f;

    /** The MIDI number of A4, the equal-temperament reference. */
    public static final int A4_MIDI = 69;

    /** Sharp note names indexed by pitch class (0 = C .. 11 = B). */
    private static final String[] NAMES = {
        "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
    };

    private final float frequency;
    private final int midi;
    private final float cents;
    private final float referenceFrequency;

    private Note(float frequency, int midi, float cents, float referenceFrequency) {
        this.frequency = frequency;
        this.midi = midi;
        this.cents = cents;
        this.referenceFrequency = referenceFrequency;
    }

    /**
     * Resolves the nearest equal-tempered note to {@code frequency}.
     *
     * @param frequency the detected pitch in Hz (must be positive)
     * @return the nearest note, or null when the frequency is not positive
     */
    public static Note closestTo(float frequency) {
        if (frequency <= 0f || Float.isNaN(frequency) || Float.isInfinite(frequency)) {
            return null;
        }
        float midiFloat = midiOf(frequency);
        int nearest = Math.round(midiFloat);
        // Keep the nearest note in the MIDI range; the cents deviation below is
        // measured against the (unclamped) fractional position, so an extreme
        // frequency still reports the true offset from the clamped note.
        if (nearest < 0) {
            nearest = 0;
        } else if (nearest > 127) {
            nearest = 127;
        }
        float cents = (midiFloat - nearest) * 100.0f;
        return new Note(frequency, nearest, cents, frequencyOfMidi(nearest));
    }

    /**
     * Builds the note for an exact MIDI number with zero deviation.
     *
     * @param midi the MIDI note number (clamped to 0..127)
     * @return the in-tune note for that MIDI number
     */
    public static Note fromMidi(int midi) {
        int m = Math.max(0, Math.min(127, midi));
        float f = frequencyOfMidi(m);
        return new Note(f, m, 0f, f);
    }

    /**
     * The equal-tempered frequency of a MIDI note number:
     * {@code 440 * 2^((midi - 69) / 12)}.
     *
     * @param midi the MIDI note number
     * @return the frequency in Hz
     */
    public static float frequencyOfMidi(int midi) {
        return (float) (REFERENCE_A4 * Math.pow(2.0, (midi - A4_MIDI) / 12.0));
    }

    /**
     * The fractional MIDI number of a frequency: {@code 69 + 12 * log2(f / 440)}.
     *
     * @param frequency the frequency in Hz (must be positive)
     * @return the fractional MIDI position, or NaN when the frequency is not positive
     */
    public static float midiOf(float frequency) {
        if (frequency <= 0f) {
            return Float.NaN;
        }
        return (float) (A4_MIDI + 12.0 * (Math.log(frequency / REFERENCE_A4) / Math.log(2.0)));
    }

    /** The letter name with sharps (e.g. {@code "A"}, {@code "C#"}). */
    public String getName() {
        return NAMES[Math.floorMod(midi, 12)];
    }

    /** The scientific octave (MIDI 60..71 is octave 4, so A4 = MIDI 69). */
    public int getOctave() {
        return (midi / 12) - 1;
    }

    /** The MIDI note number of the nearest note. */
    public int getMidi() {
        return midi;
    }

    /**
     * The deviation of the detected pitch from the nearest note, in cents.
     * Negative is flat, positive is sharp; a value near zero is in tune.
     */
    public float getCents() {
        return cents;
    }

    /** The ideal equal-tempered frequency of the nearest note, in Hz. */
    public float getReferenceFrequency() {
        return referenceFrequency;
    }

    /** The detected frequency this note was resolved from, in Hz. */
    public float getFrequency() {
        return frequency;
    }

    /** The name with octave, e.g. {@code "A4"} or {@code "E2"}. */
    public String getLabel() {
        return getName() + getOctave();
    }

    /**
     * Whether the detected pitch is within {@code tolerance} cents of the
     * nearest note (i.e. close enough to read as in tune).
     *
     * @param toleranceCents the acceptable absolute deviation in cents
     * @return true when {@code |cents| <= toleranceCents}
     */
    public boolean isInTune(float toleranceCents) {
        return Math.abs(cents) <= Math.abs(toleranceCents);
    }

    @Override
    public String toString() {
        return String.format("%s %+.1f cents (%.2f Hz)", getLabel(), cents, frequency);
    }
}
