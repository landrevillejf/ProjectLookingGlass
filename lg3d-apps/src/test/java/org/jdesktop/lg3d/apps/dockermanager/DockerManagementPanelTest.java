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
package org.jdesktop.lg3d.apps.dockermanager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;

/**
 * Headless construction test for the Docker Manager panel.
 *
 * <p>Building {@link DockerManagementPanel} lays out the terminal / containers /
 * files / images tabs and probes the system {@code docker} CLI on a background
 * thread. The panel must never throw out of its constructor: with no docker on
 * the PATH the probe degrades to a readable "not running" status instead of
 * failing. {@code java.awt.headless=true} (set by the module's test task) keeps
 * this CI-safe with no display and no docker daemon, and {@link
 * DockerManagementPanel#cleanup()} shuts the probe's executor down.</p>
 */
class DockerManagementPanelTest {

    @Test
    @DisplayName("the no-arg panel constructs headless without throwing")
    void panelConstructsHeadless() {
        // The 2D desktop builds the panel reflectively through this constructor
        // (Desktop2DAppRegistry.PANEL_APPS), so it must exist and not throw.
        ThrowingSupplier<DockerManagementPanel> ctor = DockerManagementPanel::new;
        DockerManagementPanel panel = assertDoesNotThrow(ctor);
        assertNotNull(panel);
        assertNotNull(panel.getLayout());
        assertTrue(panel.getComponentCount() > 0, "the panel should lay out its tabs");
        assertDoesNotThrow(panel::cleanup);
    }

    @Test
    @DisplayName("the panel is a Swing panel and the wrapper advertises a host size")
    void panelIsSwingAndWrapperAdvertisesHostSize() {
        DockerManagementPanel panel = new DockerManagementPanel();
        try {
            assertTrue(panel instanceof JPanel);
            // The wrapper hands these to TitledSwingWindow, which sizes the
            // SwingNode / Frame3D from them (the panel itself lays out to its
            // tabs' natural preferred size, exactly as in the source plugin).
            assertTrue(DockerManager.WIDTH_PX > 0);
            assertTrue(DockerManager.HEIGHT_PX > 0);
        } finally {
            panel.cleanup();
        }
    }

    @Test
    @DisplayName("a project-root constructor browses the given directory")
    void panelAcceptsProjectRoot() {
        File root = new File(System.getProperty("java.io.tmpdir"));
        ThrowingSupplier<DockerManagementPanel> ctor =
                () -> new DockerManagementPanel(root);
        DockerManagementPanel panel = assertDoesNotThrow(ctor);
        try {
            assertNotNull(panel);
            assertDoesNotThrow(panel::refreshAll);
        } finally {
            panel.cleanup();
        }
    }
}
