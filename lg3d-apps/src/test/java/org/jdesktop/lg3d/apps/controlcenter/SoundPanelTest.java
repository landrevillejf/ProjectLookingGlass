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
 * Headless construction test for the control center's Sound panel.
 *
 * <p>Building {@link SoundPanel} reads the master volume through the
 * {@code VolumeStatus} seam, which returns empty on a host with no audio device
 * (as in the test JVM), so the panel degrades to its "no audio device" state. It
 * creates only lightweight Swing components - no top-level window - so
 * constructing it under {@code java.awt.headless=true} is CI-safe.</p>
 */
class SoundPanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        SoundPanel panel = assertDoesNotThrow(SoundPanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        SoundPanel panel = new SoundPanel();
        assertEquals("Sound", panel.displayName());
        assertNull(panel.icon(), "the Sound panel ships no navigation icon");
    }

    @Test
    @DisplayName("the panel exposes a stable Swing component and re-loads on show")
    void componentAndOnShow() {
        SoundPanel panel = new SoundPanel();
        JComponent component = panel.component();
        assertNotNull(component);
        assertNotNull(component.getLayout(), "the root container is laid out");
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        assertEquals(component, panel.component());
    }

    @Test
    @DisplayName("the volume preset index snaps to the nearest clamped decile")
    void volumeIndexSnaps() {
        assertEquals(0, SoundPanel.indexOfVolume(0));
        assertEquals(5, SoundPanel.indexOfVolume(47), "47% snaps to the 50% row");
        assertEquals(5, SoundPanel.indexOfVolume(50));
        assertEquals(10, SoundPanel.indexOfVolume(100));
        assertEquals(10, SoundPanel.indexOfVolume(999), "clamped high");
        assertEquals(0, SoundPanel.indexOfVolume(-5), "clamped low");
    }
}
