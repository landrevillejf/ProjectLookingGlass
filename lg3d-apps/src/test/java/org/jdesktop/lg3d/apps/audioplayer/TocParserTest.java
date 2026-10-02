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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link TocParser} against canned {@code cdparanoia -Q} and
 * {@code cd-info} output, and asserts its tolerance of blank / malformed text
 * (no track rows yields an empty {@link Toc} rather than a throw). Pure string
 * parsing, so it runs headless.
 */
class TocParserTest {

    /** A six-track disc, matching the MusicBrainz worked-example offsets. */
    private static final String CDPARANOIA = String.join("\n",
            "cdparanoia III release 10.2",
            "track  length               begin",
            "  1.     15213 (03:22.63)        0 (00:00.00)",
            "  2.     16951 (03:46.01)    15213 (03:22.63)",
            "  3.     14278 (03:10.28)    32164 (07:08.64)",
            "  4.     16822 (03:44.22)    46442 (10:19.17)",
            "  5.     17075 (03:47.50)    63264 (14:03.39)",
            "  6.     14973 (03:19.48)    80339 (17:51.14)");

    private static final String CDINFO = String.join("\n",
            "cd-info version 2.1.0",
            "CD Track Layout",
            "track  LSN    start        length",
            "  1        0  00:00:00.00  00:03:22.63",
            "  2    15213  00:03:24.63  00:03:46.01",
            "  3    32164  00:07:10.64  00:03:10.28",
            "  4    46442  00:10:21.17  00:03:44.22",
            "  5    63264  00:14:05.39  00:03:47.50",
            "  6    80339  00:17:53.14  00:03:19.48",
            "Lead-out   95312  00:21:11.12");

    @Test
    @DisplayName("cdparanoia -Q output parses to track offsets, lengths and lead-out")
    void parseCdparanoia() {
        Toc toc = TocParser.parse(CDPARANOIA);
        assertEquals(1, toc.getFirstTrack());
        assertEquals(6, toc.trackCount());
        assertEquals(6, toc.lastTrack());
        Toc.Track first = toc.getTracks().get(0);
        assertEquals(1, first.number);
        assertEquals(0L, first.startLba);
        assertEquals(15213L, first.lengthLba);
        Toc.Track last = toc.getTracks().get(5);
        assertEquals(6, last.number);
        assertEquals(80339L, last.startLba);
        assertEquals(14973L, last.lengthLba);
        assertEquals(95312L, toc.leadOutLba());
    }

    @Test
    @DisplayName("cd-info output derives lengths from the gaps and the lead-out")
    void parseCdInfo() {
        Toc toc = TocParser.parse(CDINFO);
        assertEquals(6, toc.trackCount());
        assertEquals(0L, toc.getTracks().get(0).startLba);
        assertEquals(15213L, toc.getTracks().get(0).lengthLba,
                "length = next start - this start");
        assertEquals(80339L, toc.getTracks().get(5).startLba);
        assertEquals(95312L, toc.leadOutLba());
        assertEquals(14973L, toc.getTracks().get(5).lengthLba,
                "final length = lead-out - last start");
    }

    @Test
    @DisplayName("a cd-info disc with no lead-out row still parses, last length 0")
    void parseCdInfoNoLeadOut() {
        String out = String.join("\n",
                "  1        0  00:00:00.00  00:03:22.63",
                "  2    15213  00:03:24.63  00:03:46.01");
        Toc toc = TocParser.parse(out);
        assertEquals(2, toc.trackCount());
        assertEquals(15213L, toc.getTracks().get(0).lengthLba);
        assertEquals(0L, toc.getTracks().get(1).lengthLba);
    }

    @Test
    @DisplayName("blank, null and unrecognised output yield an empty TOC")
    void tolerantOfJunk() {
        assertTrue(TocParser.parse(null).isEmpty());
        assertTrue(TocParser.parse("   ").isEmpty());
        assertTrue(TocParser.parse("no track rows here\njust a banner").isEmpty());
        assertEquals(1, TocParser.parse("").getFirstTrack());
    }
}
