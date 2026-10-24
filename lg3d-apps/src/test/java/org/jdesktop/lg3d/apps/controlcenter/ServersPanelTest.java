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
 * Headless construction test for the control center's Servers panel (Phase B of
 * the LFS system-management contract, §4.9).
 *
 * <p>Building {@link ServersPanel} only probes {@code PATH} and the filesystem
 * for the curated catalog of servers ({@link org.jdesktop.lg3d.utils.system.ServerService#probe()});
 * it never spawns a server process, so on a dev host where none of httpd / BIND /
 * Postfix / Samba / MariaDB is installed the panel degrades to an empty list and
 * a read-only note. It creates only lightweight Swing components, so constructing
 * it under {@code java.awt.headless=true} is CI-safe. The status / config reads
 * and the start/stop/restart/enable/disable mutations run off the EDT via
 * {@code SwingWorker}, are gated behind confirmation dialogs and polkit, and are
 * exercised on target, not here.</p>
 */
class ServersPanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        ServersPanel panel = assertDoesNotThrow(ServersPanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        ServersPanel panel = new ServersPanel();
        assertEquals("Servers", panel.displayName());
        assertNull(panel.icon(), "the Servers panel ships no navigation icon");
    }

    @Test
    @DisplayName("the panel exposes a stable component and survives onShow/onHide")
    void componentAndLifecycle() {
        ServersPanel panel = new ServersPanel();
        JComponent component = panel.component();
        assertNotNull(component);
        assertNotNull(component.getLayout(), "the root container is laid out");
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        assertSame(component, panel.component(), "the component is created once and reused");
    }

    @Test
    @DisplayName("detection degrades to a header and never a null note")
    void detectionDegradesGracefully() {
        ServersPanel panel = new ServersPanel();
        assertNotNull(panel.headerText());
        assertNotNull(panel.noteText());
        assertNotNull(panel.detailsText());
        assertTrue(panel.serverCount() >= 0, "the installed-server count is a real number");
    }

    @Test
    @DisplayName("the Servers category is registered and builds lazily")
    void registeredAsLazyDescriptor() {
        PanelDescriptor servers = ControlPanelRegistry.descriptors().stream()
                .filter(d -> d.displayName().equals("Servers"))
                .findFirst()
                .orElse(null);
        assertNotNull(servers, "the Servers category is registered");
        ControlPanel panel = servers.get();
        assertNotNull(panel, "the Servers panel builds in the test JVM");
        assertTrue(panel instanceof ServersPanel);
    }
}
