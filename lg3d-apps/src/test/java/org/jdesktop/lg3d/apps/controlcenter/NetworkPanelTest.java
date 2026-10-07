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
 * Headless construction test for the control center's Network panel (Phase 3 of
 * the LFS system-management contract, §4.4).
 *
 * <p>Building {@link NetworkPanel} detects the network backend (probing only
 * {@code PATH}) and, when one is present, lists its connections/links read-only;
 * on a host with no {@code nmcli}/{@code dhcpcd}/{@code networkctl}/{@code ip}
 * the detection returns {@link org.jdesktop.lg3d.utils.system.NetworkService.Backend#NONE},
 * so the panel degrades to its "unavailable" note and spawns no process. It
 * creates only lightweight Swing components, so constructing it under
 * {@code java.awt.headless=true} is CI-safe. The connect/disconnect actions run
 * off the EDT via {@code SwingWorker}, are gated behind a confirmation dialog
 * and (on {@code dhcpcd}/{@code networkd}) polkit, and are exercised on target,
 * not here.</p>
 */
class NetworkPanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        NetworkPanel panel = assertDoesNotThrow(NetworkPanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        NetworkPanel panel = new NetworkPanel();
        assertEquals("Network", panel.displayName());
        assertNull(panel.icon(), "the Network panel ships no navigation icon");
    }

    @Test
    @DisplayName("the panel exposes a stable component and survives onShow/onHide")
    void componentAndLifecycle() {
        NetworkPanel panel = new NetworkPanel();
        JComponent component = panel.component();
        assertNotNull(component);
        assertNotNull(component.getLayout(), "the root container is laid out");
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        assertSame(component, panel.component(), "the component is created once and reused");
    }

    @Test
    @DisplayName("the Network category is registered and builds lazily")
    void registeredAsLazyDescriptor() {
        PanelDescriptor network = ControlPanelRegistry.descriptors().stream()
                .filter(d -> d.displayName().equals("Network"))
                .findFirst()
                .orElse(null);
        assertNotNull(network, "the Network category is registered");
        ControlPanel panel = network.get();
        assertNotNull(panel, "the Network panel builds in the test JVM");
        assertTrue(panel instanceof NetworkPanel);
    }
}
