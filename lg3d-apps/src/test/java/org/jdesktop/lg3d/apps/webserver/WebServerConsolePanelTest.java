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
package org.jdesktop.lg3d.apps.webserver;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;

/**
 * Headless construction test for the Web Server Console panel.
 *
 * <p>Building {@link WebServerConsolePanel} lays out the toolbar, status header and
 * the Overview / Applications / Logs tabs, and kicks off a background read of the
 * server. The panel must never throw out of a public method: with no server up the
 * read degrades to a readable "server down" status instead of failing, and
 * {@code dispose()}/{@code refresh()} are safe at any time.
 * {@code java.awt.headless=true} (set by the module's test task) keeps this
 * CI-safe with no display, no server and no docker daemon, mirroring
 * {@code DockerManagementPanelTest}.</p>
 */
class WebServerConsolePanelTest {

    @Test
    @DisplayName("the no-arg panel constructs headless without throwing")
    void panelConstructsHeadless() {
        // The 2D desktop builds the panel through WebServerPanel, which wraps this
        // no-arg constructor (Desktop2DAppRegistry.PANEL_APPS), so it must exist
        // and not throw.
        ThrowingSupplier<WebServerConsolePanel> ctor = WebServerConsolePanel::new;
        WebServerConsolePanel panel = assertDoesNotThrow(ctor);
        try {
            assertNotNull(panel);
            assertNotNull(panel.getLayout());
            assertTrue(panel.getComponentCount() > 0,
                    "the panel should lay out its header and tabs");
            assertEquals(3, panel.getTabCount(), "Overview / Applications / Logs");
            assertDoesNotThrow(panel::refresh);
        } finally {
            assertDoesNotThrow(panel::dispose);
        }
    }

    @Test
    @DisplayName("a project-root constructor builds without throwing")
    void panelAcceptsProjectRoot() {
        File root = new File(System.getProperty("java.io.tmpdir"));
        ThrowingSupplier<WebServerConsolePanel> ctor = () -> new WebServerConsolePanel(root);
        WebServerConsolePanel panel = assertDoesNotThrow(ctor);
        try {
            assertNotNull(panel);
            assertDoesNotThrow(panel::refresh);
        } finally {
            panel.dispose();
        }
    }

    @Test
    @DisplayName("the panel is Swing, the wrappers advertise a host size, dispose is idempotent")
    void panelIsSwingAndDisposeIsIdempotent() {
        WebServerConsolePanel panel = new WebServerConsolePanel();
        try {
            assertTrue(panel instanceof JPanel);
            assertTrue(WebServerConsole.WIDTH_PX > 0);
            assertTrue(WebServerConsole.HEIGHT_PX > 0);
            // The 3D wrapper and the 2D shim both hand these to their host.
            assertEquals(WebServerConsolePanel.WIDTH_PX, WebServerConsole.WIDTH_PX);
            assertEquals(WebServerConsolePanel.HEIGHT_PX, WebServerPanel.HEIGHT_PX);
        } finally {
            panel.dispose();
        }
        assertTrue(panel.isDisposed());
        assertDoesNotThrow(panel::dispose);   // idempotent
        assertDoesNotThrow(panel::refresh);   // a no-op after dispose, never a throw
    }

    @Test
    @DisplayName("the 2D shim wraps the console and disposes it")
    void shimWrapsTheConsole() {
        WebServerPanel shim = assertDoesNotThrow(WebServerPanel::new);
        try {
            assertNotNull(shim.getMainPanel());
            assertTrue(shim.getComponentCount() > 0);
        } finally {
            assertDoesNotThrow(shim::dispose);
        }
    }

    @Test
    @DisplayName("with no server up the panel degrades to a readable status, never throws")
    void degradesWithNoServer() throws Exception {
        // Port 1 on loopback answers nothing, so the background read fails fast
        // (connection refused) and the panel must report "down" rather than hang.
        WebServerConsolePanel panel = new WebServerConsolePanel(
                new ManagementClient("http://127.0.0.1:1"),
                new ServerProcessSupervisor(List.of(), unused -> {
                    throw new AssertionError("the degrade path must not fork a server");
                }));
        try {
            long deadline = System.currentTimeMillis() + 5_000L;
            String status = panel.getStatusText();
            while (System.currentTimeMillis() < deadline
                    && (status == null || !status.toLowerCase().contains("down"))) {
                Thread.sleep(50L);
                status = panel.getStatusText();
            }
            assertNotNull(status);
            assertTrue(status.toLowerCase().contains("down"),
                    "with no server the status should report it is down, was: " + status);
            assertDoesNotThrow(panel::refresh);
        } finally {
            panel.dispose();
        }
    }
}
