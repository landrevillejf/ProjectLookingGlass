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
}
