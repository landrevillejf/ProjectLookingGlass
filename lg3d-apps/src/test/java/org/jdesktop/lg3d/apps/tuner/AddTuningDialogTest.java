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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure logic of {@link AddTuningDialog}: the note-label table offered
 * by each string picker, the label &lt;-&gt; MIDI round-trip, and the
 * {@link AddTuningDialog#build(String, int[])} step that turns the editor's
 * inputs into a {@link Tuning} (including the derived name for a blank one). No
 * window is ever shown, so this runs headless.
 */
class AddTuningDialogTest {

    @Test
    @DisplayName("the note table spans the declared MIDI range, low to high")
    void noteLabelsSpanRange() {
        String[] labels = AddTuningDialog.noteLabels();
        assertEquals(AddTuningDialog.MAX_MIDI - AddTuningDialog.MIN_MIDI + 1, labels.length);
        assertEquals(AddTuningDialog.labelOf(AddTuningDialog.MIN_MIDI), labels[0]);
        assertEquals(AddTuningDialog.labelOf(AddTuningDialog.MAX_MIDI), labels[labels.length - 1]);
        // A returned copy, so a caller cannot mutate the shared table.
        labels[0] = "MUTATED";
        assertEquals(AddTuningDialog.labelOf(AddTuningDialog.MIN_MIDI),
                AddTuningDialog.noteLabels()[0]);
    }

    @Test
    @DisplayName("labelOf and midiOf round-trip across the range")
    void labelMidiRoundTrip() {
        for (int midi = AddTuningDialog.MIN_MIDI; midi <= AddTuningDialog.MAX_MIDI; midi++) {
            String label = AddTuningDialog.labelOf(midi);
            assertEquals(midi, AddTuningDialog.midiOf(label),
                    "round-trip failed for MIDI " + midi + " (" + label + ")");
        }
        // Known reference points.
        assertEquals("A4", AddTuningDialog.labelOf(69));
        assertEquals(69, AddTuningDialog.midiOf("A4"));
        assertEquals("C2", AddTuningDialog.labelOf(36));
        assertEquals(36, AddTuningDialog.midiOf("C2"));
        // An unknown label falls back to the lowest MIDI number.
        assertEquals(AddTuningDialog.MIN_MIDI, AddTuningDialog.midiOf("not-a-note"));
        assertEquals(AddTuningDialog.MIN_MIDI, AddTuningDialog.midiOf(null));
    }

    @Test
    @DisplayName("build keeps a given name and the exact string MIDI numbers")
    void buildWithName() {
        int[] dropC = {36, 43, 48, 53, 57, 62};
        Tuning t = AddTuningDialog.build("Guitar (Drop C)", dropC);
        assertEquals("Guitar (Drop C)", t.getName());
        assertArrayEquals(dropC, t.getStrings());
        assertEquals(6, t.getStringCount());
        assertEquals("C2", t.getStringNote(0).getLabel());
        assertEquals("D4", t.getStringNote(5).getLabel());
    }

    @Test
    @DisplayName("build derives a readable name when the field is blank")
    void buildWithBlankName() {
        int[] strings = {40, 45, 50, 55, 59, 64};
        Tuning named = AddTuningDialog.build("   ", strings);
        assertEquals("Custom (E2 A2 D3 G3 B3 E4)", named.getName());
        assertEquals("Custom (E2 A2 D3 G3 B3 E4)", AddTuningDialog.build(null, strings).getName());
        // An empty string set is a chromatic-style custom tuning.
        assertEquals("Custom (chromatic)", AddTuningDialog.build("", new int[0]).getName());
        assertEquals("Custom (chromatic)", AddTuningDialog.defaultName(null));
    }

    @Test
    @DisplayName("build copies the array, so later edits do not leak in")
    void buildCopiesArray() {
        int[] strings = {36, 43, 48};
        Tuning t = AddTuningDialog.build("Copy check", strings);
        strings[0] = 99;
        assertEquals(36, t.getStringMidi(0), "the tuning must snapshot its input");
    }

    @Test
    @DisplayName("the editor widget tree builds headless without showing a window")
    void editorBuildsHeadless() {
        // Constructing the dialog only builds Swing widgets (no peer); show() is
        // the one call that needs a window and is deliberately not exercised here.
        assertDoesNotThrow(AddTuningDialog::new);
        assertTrue(AddTuningDialog.DEFAULT_STRINGS >= AddTuningDialog.MIN_STRINGS);
        assertTrue(AddTuningDialog.DEFAULT_STRINGS <= AddTuningDialog.MAX_STRINGS);
    }
}
