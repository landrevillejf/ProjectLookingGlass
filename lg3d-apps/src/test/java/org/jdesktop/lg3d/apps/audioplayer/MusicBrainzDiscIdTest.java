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
package org.jdesktop.lg3d.apps.audioplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link MusicBrainzDiscId}: the canonical hashed ID string (first/last
 * track + the 100 lead-in-adjusted frame-offset slots) and the 28-character
 * MusicBrainz base64 of its SHA-1, asserted against the worked six-track disc so
 * the output matches libdiscid byte for byte. Pure hashing, so it runs headless.
 */
class MusicBrainzDiscIdTest {

    /** The six-track disc used by the worked example (lead-out at 95312). */
    private static Toc exampleToc() {
        return new Toc(1, List.of(
                new Toc.Track(1, 0L, 15213L),
                new Toc.Track(2, 15213L, 16951L),
                new Toc.Track(3, 32164L, 14278L),
                new Toc.Track(4, 46442L, 16822L),
                new Toc.Track(5, 63264L, 17075L),
                new Toc.Track(6, 80339L, 14973L)));
    }

    @Test
    @DisplayName("the hashed ID string carries first/last track then the offset slots")
    void idString() {
        String id = MusicBrainzDiscId.idString(exampleToc());
        // 2 + 2 hex chars for first/last track, then 100 slots of 8 hex chars.
        assertEquals(2 + 2 + 100 * 8, id.length());
        assertTrue(id.startsWith("0106"), "first track 01, last track 06");
        // Slot 0 is the lead-out (95312) + 150 lead-in = 95462 = 0x000174E6.
        assertEquals("000174E6", id.substring(4, 12));
        // Slot 1 is track 1's offset 0 + 150 = 0x00000096.
        assertEquals("00000096", id.substring(12, 20));
        // Slot 6 is track 6's offset 80339 + 150 = 80489 = 0x00013A69.
        assertEquals("00013A69", id.substring(4 + 6 * 8, 4 + 7 * 8));
        // Unused high slots stay zero.
        assertEquals("00000000", id.substring(id.length() - 8));
    }

    @Test
    @DisplayName("compute matches the worked-example disc ID")
    void computeKnownVector() {
        assertEquals("49HHV7Eb8UKF3aQiNmu1GR8vKTY-",
                MusicBrainzDiscId.compute(exampleToc()));
    }

    @Test
    @DisplayName("the ID is 28 chars and uses the MusicBrainz URL-safe alphabet")
    void alphabetAndLength() {
        String id = MusicBrainzDiscId.compute(exampleToc());
        assertEquals(28, id.length());
        assertTrue(id.matches("[.0-9A-Za-z_-]+"),
                "only the MusicBrainz base64 alphabet (. _ - plus alphanumerics)");
    }

    @Test
    @DisplayName("a null or empty TOC yields an empty ID, never a throw")
    void emptyIsSafe() {
        assertEquals("", MusicBrainzDiscId.compute(null));
        assertEquals("", MusicBrainzDiscId.compute(new Toc(1, List.of())));
    }

    @Test
    @DisplayName("base64 maps + / = onto . _ - as MusicBrainz requires")
    void base64Alphabet() {
        // A digest whose standard base64 would use '+', '/' and '=' padding.
        byte[] digest = {(byte) 0xFB, (byte) 0xFF, (byte) 0xBF, 0x00, 0x00, 0x00};
        String mb = MusicBrainzDiscId.base64(digest);
        assertTrue(mb.indexOf('+') < 0 && mb.indexOf('/') < 0 && mb.indexOf('=') < 0,
                "the standard base64 punctuation is replaced: " + mb);
    }
}
