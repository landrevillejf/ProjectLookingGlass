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

import java.awt.Component;
import java.awt.Container;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JSlider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless construction test for the control center's Sound panel.
 *
 * <p>Building {@link SoundPanel} reads the master volume and the device list
 * through the {@code VolumeStatus} seam, which returns empty on a host with no
 * audio device (as in the test JVM), so the panel degrades to its "no audio
 * device" state with an empty device list. It creates only lightweight Swing
 * components - no top-level window - so constructing it under
 * {@code java.awt.headless=true} is CI-safe.</p>
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
    @DisplayName("the panel hosts a master volume slider and an output-device list")
    void sliderAndDeviceListPresent() {
        SoundPanel panel = new SoundPanel();
        JSlider slider = find(panel.component(), JSlider.class);
        assertNotNull(slider, "the master volume is a JSlider (SwingNode-safe)");
        assertEquals(0, slider.getMinimum());
        assertEquals(100, slider.getMaximum());
        JList<?> list = find(panel.component(), JList.class);
        assertNotNull(list, "the output devices are shown in a JList (never a combo box)");
        assertTrue(list.getModel().getSize() >= 0, "the device list model is queryable");
    }

    /** Depth-first search for the first component of {@code type} in the tree. */
    @SuppressWarnings("unchecked")
    private static <T extends Component> T find(Container root, Class<T> type) {
        if (type.isInstance(root)) {
            return (T) root;
        }
        for (Component child : root.getComponents()) {
            if (child instanceof Container container) {
                T found = find(container, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
