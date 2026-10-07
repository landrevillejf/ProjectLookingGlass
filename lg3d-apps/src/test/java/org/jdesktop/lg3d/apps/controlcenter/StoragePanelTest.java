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
 * Headless construction test for the control center's Storage &amp; LUKS panel
 * (Phase 3 of the LFS system-management contract, §4.5).
 *
 * <p>Building {@link StoragePanel} probes for {@code lsblk} and, when present,
 * lists block devices read-only; on a host without {@code lsblk}
 * {@link org.jdesktop.lg3d.utils.system.StorageService#isAvailable()} is false,
 * so the panel degrades to its read-only note and spawns no process. It creates
 * only lightweight Swing components, so constructing it under
 * {@code java.awt.headless=true} is CI-safe. The LUKS open/close/add-key/format
 * and encrypt-disk actions run off the EDT via {@code SwingWorker}, are gated
 * behind confirmation dialogs (a <em>typed</em> confirmation for the destructive
 * format/encrypt-disk) and polkit, and are exercised on target, not here.</p>
 */
class StoragePanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        StoragePanel panel = assertDoesNotThrow(StoragePanel::new);
        assertNotNull(panel);
    }

    @Test
    @DisplayName("the panel advertises its navigation name and no icon")
    void displayNameAndIcon() {
        StoragePanel panel = new StoragePanel();
        assertEquals("Storage", panel.displayName());
        assertNull(panel.icon(), "the Storage panel ships no navigation icon");
    }

    @Test
    @DisplayName("the panel exposes a stable component and survives onShow/onHide")
    void componentAndLifecycle() {
        StoragePanel panel = new StoragePanel();
        JComponent component = panel.component();
        assertNotNull(component);
        assertNotNull(component.getLayout(), "the root container is laid out");
        assertDoesNotThrow(panel::onShow);
        assertDoesNotThrow(panel::onHide);
        assertSame(component, panel.component(), "the component is created once and reused");
    }

    @Test
    @DisplayName("the Storage category is registered and builds lazily")
    void registeredAsLazyDescriptor() {
        PanelDescriptor storage = ControlPanelRegistry.descriptors().stream()
                .filter(d -> d.displayName().equals("Storage"))
                .findFirst()
                .orElse(null);
        assertNotNull(storage, "the Storage category is registered");
        ControlPanel panel = storage.get();
        assertNotNull(panel, "the Storage panel builds in the test JVM");
        assertTrue(panel instanceof StoragePanel);
    }
}
