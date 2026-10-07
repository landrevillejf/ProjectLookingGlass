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
 * Headless construction test for the control center's System Update panel
 * (Phase 2 of the LFS system-management contract).
 *
 * <p>Building {@link SystemUpdatePanel} probes for {@code /usr/bin/lfs-update};
 * on a host without it (as in the test JVM) {@link
 * org.jdesktop.lg3d.utils.system.LfsUpdateService#isAvailable()} is false, so the
 * panel degrades to its explanatory read-only note and spawns no process. It
 * creates only lightweight Swing components, so constructing it under
 * {@code java.awt.headless=true} is CI-safe. The check/status/upgrade actions run
 * off the EDT via {@code SwingWorker} and are exercised on target, not here.</p>
 */
class SystemUpdatePanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        SystemUpdatePanel panel = assertDoesNotThrow(SystemUpdatePanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        SystemUpdatePanel panel = new SystemUpdatePanel();
        assertEquals("System Update", panel.displayName());
        assertNull(panel.icon(), "the System Update panel ships no navigation icon");
    }

    @Test
    @DisplayName("the panel exposes a stable component and survives onShow/onHide")
    void componentAndLifecycle() {
        SystemUpdatePanel panel = new SystemUpdatePanel();
        JComponent component = panel.component();
        assertNotNull(component);
        assertNotNull(component.getLayout(), "the root container is laid out");
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        assertSame(component, panel.component(), "the component is created once and reused");
    }

    @Test
    @DisplayName("the System Update category is registered and builds lazily")
    void registeredAsLazyDescriptor() {
        PanelDescriptor update = ControlPanelRegistry.descriptors().stream()
                .filter(d -> d.displayName().equals("System Update"))
                .findFirst()
                .orElse(null);
        assertNotNull(update, "the System Update category is registered");
        ControlPanel panel = update.get();
        assertNotNull(panel, "the System Update panel builds in the test JVM");
        assertTrue(panel instanceof SystemUpdatePanel);
    }
}
