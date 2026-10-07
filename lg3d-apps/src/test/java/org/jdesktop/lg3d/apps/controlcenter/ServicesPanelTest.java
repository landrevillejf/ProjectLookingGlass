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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JComponent;
import org.jdesktop.lg3d.apps.controlcenter.ControlPanelRegistry.PanelDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless construction test for the control center's Services panel (Phase 1 of
 * the LFS system-management contract).
 *
 * <p>Building {@link ServicesPanel} detects the init system and lists its units
 * through {@link org.jdesktop.lg3d.utils.system.InitSystemService}, which shells
 * out via {@code ProcessRunner}. On a host without the detected supervisor (or
 * where {@code pkexec} is absent) that degrades to an empty list and a read-only
 * note rather than throwing, so constructing the panel under
 * {@code java.awt.headless=true} is CI-safe. The panel creates only lightweight
 * Swing components (a {@code JList}, never a combo box, so it survives
 * offscreen {@code SwingNode} hosting).</p>
 */
class ServicesPanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        ServicesPanel panel = assertDoesNotThrow(ServicesPanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        ServicesPanel panel = new ServicesPanel();
        assertEquals("Services", panel.displayName());
        assertNull(panel.icon(), "the Services panel ships no navigation icon");
    }

    @Test
    @DisplayName("the panel exposes a stable component and survives onShow/onHide")
    void componentAndLifecycle() {
        ServicesPanel panel = new ServicesPanel();
        JComponent component = panel.component();
        assertNotNull(component);
        assertNotNull(component.getLayout(), "the root container is laid out");
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        assertSame(component, panel.component(), "the component is created once and reused");
    }

    @Test
    @DisplayName("the Services category is registered and builds lazily")
    void registeredAsLazyDescriptor() {
        PanelDescriptor services = ControlPanelRegistry.descriptors().stream()
                .filter(d -> d.displayName().equals("Services"))
                .findFirst()
                .orElse(null);
        assertNotNull(services, "the Services category is registered");
        ControlPanel panel = services.get();
        assertNotNull(panel, "the Services panel builds in the test JVM");
        assertTrue(panel instanceof ServicesPanel);
    }
}
