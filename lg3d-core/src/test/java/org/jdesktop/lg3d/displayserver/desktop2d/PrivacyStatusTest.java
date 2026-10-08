/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import org.jdesktop.lg3d.utils.system.TorPrivateMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure {@link PrivacyStatus} formatting seam: glyph, tooltip,
 * visibility and the CUT alarm colour, including null tolerance.
 */
class PrivacyStatusTest {

    @Test
    @DisplayName("each state carries its own compact glyph")
    void glyphs() {
        assertEquals("Tor ..", PrivacyStatus.glyph(TorPrivateMode.State.ENABLING));
        assertEquals("Tor >>", PrivacyStatus.glyph(TorPrivateMode.State.ON));
        assertEquals("Tor !!", PrivacyStatus.glyph(TorPrivateMode.State.CUT));
        assertEquals("", PrivacyStatus.glyph(TorPrivateMode.State.OFF));
        assertEquals("", PrivacyStatus.glyph(null), "a null state shows nothing");
    }

    @Test
    @DisplayName("every state has a non-blank tooltip, null included")
    void labels() {
        for (TorPrivateMode.State state : TorPrivateMode.State.values()) {
            String label = PrivacyStatus.label(state);
            assertNotNull(label);
            assertFalse(label.isBlank(), state + " needs a tooltip");
            assertTrue(label.startsWith("Private (Tor) mode:"),
                    state + " tooltip names the mode: " + label);
        }
        assertEquals("Private (Tor) mode: off", PrivacyStatus.label(null));
        assertTrue(PrivacyStatus.label(TorPrivateMode.State.CUT).contains("CUT"),
                "the cut tooltip says so loudly");
    }

    @Test
    @DisplayName("the shield shows only while a guarantee is in force")
    void visibility() {
        assertFalse(PrivacyStatus.visible(null));
        assertFalse(PrivacyStatus.visible(TorPrivateMode.State.OFF));
        assertTrue(PrivacyStatus.visible(TorPrivateMode.State.ENABLING));
        assertTrue(PrivacyStatus.visible(TorPrivateMode.State.ON));
        assertTrue(PrivacyStatus.visible(TorPrivateMode.State.CUT));
    }

    @Test
    @DisplayName("only a cut paints the glyph red")
    void cutColor() {
        assertNull(PrivacyStatus.color(null));
        assertNull(PrivacyStatus.color(TorPrivateMode.State.OFF));
        assertNull(PrivacyStatus.color(TorPrivateMode.State.ENABLING));
        assertNull(PrivacyStatus.color(TorPrivateMode.State.ON));
        Color cut = PrivacyStatus.color(TorPrivateMode.State.CUT);
        assertNotNull(cut, "a cut must be alarming");
        assertTrue(cut.getRed() > cut.getGreen(),
                "the alarm colour is red-dominant: " + cut);
        assertEquals(cut, PrivacyStatus.CUT_COLOR);
    }

    @Test
    @DisplayName("a blank grade leaves the tooltip unchanged; a real grade appends it")
    void labelWithGrade() {
        for (TorPrivateMode.State state : TorPrivateMode.State.values()) {
            assertEquals(PrivacyStatus.label(state), PrivacyStatus.label(state, null),
                    state + ": a null grade adds nothing");
            assertEquals(PrivacyStatus.label(state), PrivacyStatus.label(state, "  "),
                    state + ": a blank grade adds nothing");
        }
        String augmented = PrivacyStatus.label(TorPrivateMode.State.ON, "B");
        assertTrue(augmented.startsWith(PrivacyStatus.label(TorPrivateMode.State.ON)), augmented);
        assertTrue(augmented.contains("security grade B"), augmented);
        assertTrue(PrivacyStatus.label(TorPrivateMode.State.OFF, " A ").contains("security grade A"),
                "the grade letter is trimmed");
    }

    @Test
    @DisplayName("a cut stays red; otherwise only a poor grade paints the caution colour")
    void colorWithGrade() {
        // A cut wins regardless of the grade.
        assertEquals(PrivacyStatus.CUT_COLOR, PrivacyStatus.color(TorPrivateMode.State.CUT, "A"));
        assertEquals(PrivacyStatus.CUT_COLOR, PrivacyStatus.color(TorPrivateMode.State.CUT, "F"));

        // A poor grade (D/F) paints the caution colour on a non-cut state.
        assertEquals(PrivacyStatus.ATTENTION_COLOR,
                PrivacyStatus.color(TorPrivateMode.State.ON, "D"));
        assertEquals(PrivacyStatus.ATTENTION_COLOR,
                PrivacyStatus.color(TorPrivateMode.State.ON, "f"));
        assertEquals(PrivacyStatus.ATTENTION_COLOR,
                PrivacyStatus.color(TorPrivateMode.State.ENABLING, "F"));

        // A good or absent grade inherits the taskbar colour (null).
        assertNull(PrivacyStatus.color(TorPrivateMode.State.ON, "A"));
        assertNull(PrivacyStatus.color(TorPrivateMode.State.ON, "C"));
        assertNull(PrivacyStatus.color(TorPrivateMode.State.ON, ""));
        assertEquals(PrivacyStatus.ATTENTION_COLOR,
                PrivacyStatus.color(TorPrivateMode.State.OFF, "D"),
                "colour is grade-driven; visible(OFF) hides the shield anyway");

        // The one-arg form still ignores any grade.
        assertNull(PrivacyStatus.color(TorPrivateMode.State.ON));
    }

    @Test
    @DisplayName("only a D or F counts as a poor grade")
    void poorGrade() {
        assertTrue(PrivacyStatus.isPoorGrade("D"));
        assertTrue(PrivacyStatus.isPoorGrade("f"));
        assertTrue(PrivacyStatus.isPoorGrade("  d  "));
        assertFalse(PrivacyStatus.isPoorGrade("A"));
        assertFalse(PrivacyStatus.isPoorGrade("B"));
        assertFalse(PrivacyStatus.isPoorGrade("C"));
        assertFalse(PrivacyStatus.isPoorGrade(""));
        assertFalse(PrivacyStatus.isPoorGrade("   "));
        assertFalse(PrivacyStatus.isPoorGrade(null));
    }
}
