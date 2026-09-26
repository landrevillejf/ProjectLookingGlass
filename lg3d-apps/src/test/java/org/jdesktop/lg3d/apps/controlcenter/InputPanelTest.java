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
package org.jdesktop.lg3d.apps.controlcenter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless construction test for the control center's Mouse &amp; Keyboard panel.
 *
 * <p>Building {@link InputPanel} reads the pointer acceleration and key-repeat
 * state through the {@code InputSettings} seam, which degrades to a read-only note
 * when {@code xset} or an X server is absent (as in the headless test JVM). It
 * creates only lightweight Swing components, so constructing it under
 * {@code java.awt.headless=true} is CI-safe. The two pure selection helpers are
 * asserted directly.</p>
 */
class InputPanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        InputPanel panel = assertDoesNotThrow(InputPanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        InputPanel panel = new InputPanel();
        assertEquals("Mouse & Keyboard", panel.displayName());
        assertNull(panel.icon(), "the Mouse & Keyboard panel ships no navigation icon");
    }

    @Test
    @DisplayName("the panel exposes a stable Swing component and re-loads on show")
    void componentAndOnShow() {
        InputPanel panel = new InputPanel();
        JComponent component = panel.component();
        assertNotNull(component);
        assertNotNull(component.getLayout(), "the root container is laid out");
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        assertEquals(component, panel.component());
    }

    @Test
    @DisplayName("the closest preset index resolves exactly, by nearest, and ties to the lower row")
    void closestIndexResolves() {
        assertEquals(1, InputPanel.closestIndex(InputPanel.ACCELERATIONS, 2), "an exact match");
        assertEquals(0, InputPanel.closestIndex(InputPanel.ACCELERATIONS, 0),
                "a target below the table clamps to the first row");
        assertEquals(InputPanel.ACCELERATIONS.length - 1,
                InputPanel.closestIndex(InputPanel.ACCELERATIONS, 100),
                "a target above the table clamps to the last row");
        assertEquals(5, InputPanel.closestIndex(InputPanel.THRESHOLDS, 7),
                "a tie between 6 and 8 resolves to the lower index");
        assertEquals(-1, InputPanel.closestIndex(new int[0], 5), "an empty table has no rows");
        assertEquals(-1, InputPanel.closestIndex(null, 5), "a null table has no rows");
    }

    @Test
    @DisplayName("the acceleration read-out formats whole, fractional and unknown values")
    void formatAccelFormats() {
        assertEquals("2x", InputPanel.formatAccel(2.0), "a whole multiplier drops the decimals");
        assertEquals("(unknown)", InputPanel.formatAccel(Double.NaN), "an unreadable acceleration");
        String fractional = InputPanel.formatAccel(1.5);
        assertTrue(fractional.startsWith("1") && fractional.endsWith("x"),
                "a fractional multiplier keeps the integer part and the x suffix: " + fractional);
    }
}
