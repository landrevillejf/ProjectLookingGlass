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

import javax.swing.JComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless construction test for the control center's Power panel.
 *
 * <p>Building {@link PowerPanel} reads the battery, backlight and thermal seams,
 * all of which return empty/unavailable on a host without that hardware (as in
 * the test JVM), so the panel degrades to its explanatory notes. The refresh
 * {@code Timer} is constructed but not started until {@code onShow()}. It creates
 * only lightweight Swing components, so constructing it under
 * {@code java.awt.headless=true} is CI-safe.</p>
 */
class PowerPanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        PowerPanel panel = assertDoesNotThrow(PowerPanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        PowerPanel panel = new PowerPanel();
        assertEquals("Power", panel.displayName());
        assertNull(panel.icon(), "the Power panel ships no navigation icon");
    }

    @Test
    @DisplayName("the panel exposes a stable component and starts/stops its timer")
    void componentAndOnShow() {
        PowerPanel panel = new PowerPanel();
        JComponent component = panel.component();
        assertNotNull(component);
        assertNotNull(component.getLayout(), "the root container is laid out");
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        assertEquals(component, panel.component());
    }

    @Test
    @DisplayName("the brightness preset index snaps to the nearest clamped decile")
    void brightnessIndexSnaps() {
        assertEquals(0, PowerPanel.indexOfBrightness(0));
        assertEquals(6, PowerPanel.indexOfBrightness(60));
        assertEquals(3, PowerPanel.indexOfBrightness(34), "34% snaps to the 30% row");
        assertEquals(10, PowerPanel.indexOfBrightness(100));
        assertEquals(10, PowerPanel.indexOfBrightness(999), "clamped high");
        assertEquals(0, PowerPanel.indexOfBrightness(-5), "clamped low");
    }
}
