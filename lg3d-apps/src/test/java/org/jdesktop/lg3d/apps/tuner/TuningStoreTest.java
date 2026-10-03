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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link TuningStore}'s defensive JSON round-trip of user-defined
 * tunings: a saved set (e.g. Drop C) loads back with its name and string MIDI
 * numbers intact, and a missing / corrupt / null store yields an empty list and
 * never throws, so a damaged config can never stop the tuner from opening.
 */
class TuningStoreTest {

    /** Drop C guitar: C2 G2 C3 F3 A3 D4. */
    private static final int[] DROP_C = {36, 43, 48, 53, 57, 62};

    @Test
    @DisplayName("a custom tuning survives a save / load round-trip")
    void roundTrip(@TempDir Path dir) {
        TuningStore store = new TuningStore(dir);
        Tuning dropC = Tuning.of("Guitar (Drop C)", DROP_C);
        Tuning dadgad = Tuning.of("Guitar (DADGAD)", 38, 45, 50, 57, 62, 74);
        store.saveCustomTunings(List.of(dropC, dadgad));

        List<Tuning> back = new TuningStore(dir).loadCustomTunings();
        assertEquals(2, back.size());
        assertEquals("Guitar (Drop C)", back.get(0).getName());
        assertArrayEquals(DROP_C, back.get(0).getStrings());
        assertEquals(6, back.get(0).getStringCount());
        assertEquals("Guitar (DADGAD)", back.get(1).getName());
        // The reloaded tuning is equal to (and behaves like) the original.
        assertEquals(dropC, back.get(0));
        assertEquals(65.41f, back.get(0).getStringFrequency(0), 0.5f); // C2
    }

    @Test
    @DisplayName("a missing file yields an empty list, never throws")
    void missingIsSafe(@TempDir Path dir) {
        assertTrue(new TuningStore(dir).loadCustomTunings().isEmpty());
    }

    @Test
    @DisplayName("a corrupt file yields an empty list, never throws")
    void corruptIsSafe(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(TuningStore.TUNINGS_FILE), "{not a list]");
        assertTrue(new TuningStore(dir).loadCustomTunings().isEmpty());
    }

    @Test
    @DisplayName("saving null or an empty list clears the stored tunings")
    void saveNullIsEmpty(@TempDir Path dir) {
        TuningStore store = new TuningStore(dir);
        store.saveCustomTunings(List.of(Tuning.of("Temp", DROP_C)));
        assertEquals(1, new TuningStore(dir).loadCustomTunings().size());

        store.saveCustomTunings(null);
        assertTrue(new TuningStore(dir).loadCustomTunings().isEmpty());

        store.saveCustomTunings(new ArrayList<>());
        assertTrue(new TuningStore(dir).loadCustomTunings().isEmpty());
    }

    @Test
    @DisplayName("a chromatic (no-string) custom tuning round-trips")
    void chromaticRoundTrip(@TempDir Path dir) {
        new TuningStore(dir).saveCustomTunings(List.of(Tuning.of("No strings")));
        List<Tuning> back = new TuningStore(dir).loadCustomTunings();
        assertEquals(1, back.size());
        assertEquals(0, back.get(0).getStringCount());
        assertEquals(-1, back.get(0).closestString(440f));
    }

    @Test
    @DisplayName("the DIR_PROPERTY override resolves the default directory")
    void honoursOverride() {
        String previous = System.getProperty(TuningStore.DIR_PROPERTY);
        try {
            System.setProperty(TuningStore.DIR_PROPERTY, "/tmp/tuner-override");
            assertEquals(Path.of("/tmp/tuner-override"), TuningStore.defaultConfigDir());
            assertEquals(Path.of("/tmp/tuner-override"), new TuningStore().getConfigDir());
        } finally {
            if (previous == null) {
                System.clearProperty(TuningStore.DIR_PROPERTY);
            } else {
                System.setProperty(TuningStore.DIR_PROPERTY, previous);
            }
        }
    }
}
