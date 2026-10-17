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
package org.jdesktop.lg3d.apps.screencapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The capture-area vocabulary is pure, so it is asserted directly: exactly the
 * three areas the Screen Snapshot dialog offers, each with a distinct,
 * non-blank button label.
 */
class CaptureAreaTest {

    @Test
    @DisplayName("there are exactly three capture areas")
    void threeAreas() {
        assertEquals(3, CaptureArea.values().length);
        assertNotNull(CaptureArea.FULL_SCREEN);
        assertNotNull(CaptureArea.ALL_WINDOWS);
        assertNotNull(CaptureArea.SELECTION);
    }

    @Test
    @DisplayName("each area carries a non-blank, distinct label")
    void labelsAreDistinctAndNonBlank() {
        Set<String> labels = new HashSet<>();
        for (CaptureArea area : CaptureArea.values()) {
            String label = area.label();
            assertNotNull(label);
            assertFalse(label.isBlank(), "blank label for " + area);
            assertTrue(labels.add(label), "duplicate label " + label);
        }
        assertEquals(3, labels.size());
    }

    @Test
    @DisplayName("the labels are the ones shown on the radio buttons")
    void labelWording() {
        assertEquals("Whole screen", CaptureArea.FULL_SCREEN.label());
        assertEquals("All windows", CaptureArea.ALL_WINDOWS.label());
        assertEquals("A region I select", CaptureArea.SELECTION.label());
    }
}
