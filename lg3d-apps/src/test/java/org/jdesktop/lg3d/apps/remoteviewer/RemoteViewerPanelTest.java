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
package org.jdesktop.lg3d.apps.remoteviewer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;

/**
 * Headless tests for the Remote Viewer panel.
 *
 * <p>{@link RemoteViewerPanel} is the ported jrdesktop main GUI hosted inside the
 * lg3d desktop JVM - as a 3D {@code Frame3D} (via {@link RemoteViewer} /
 * {@code TitledSwingWindow}) and as a 2D MDI internal frame (via
 * {@code Desktop2DAppRegistry.PANEL_APPS}, which wires the close hook
 * reflectively through {@code setCloseCallback}). Its Exit button must therefore
 * close only the window: the original standalone code called
 * {@code System.exit(0)}, which inside the desktop would tear down the whole
 * session. These tests pin that contract and keep the panel's construction
 * CI-safe ({@code java.awt.headless=true}, no display, no RMI server).</p>
 */
class RemoteViewerPanelTest {

    @Test
    @DisplayName("the no-arg panel constructs headless without throwing")
    void panelConstructsHeadless() {
        // The 2D desktop builds the panel reflectively through this constructor
        // (Desktop2DAppRegistry.PANEL_APPS), so it must exist and not throw. The
        // constructor lays out the Swing controls, loads its IconManager glyphs
        // and reads the (relocated) per-user config.
        ThrowingSupplier<RemoteViewerPanel> ctor = RemoteViewerPanel::new;
        RemoteViewerPanel panel = assertDoesNotThrow(ctor);
        assertNotNull(panel);
        assertTrue(panel instanceof JPanel);
        assertTrue(panel.getComponentCount() > 0,
                "the panel should lay out its server / viewer controls");
    }

    @Test
    @DisplayName("the panel advertises a positive host size")
    void panelAdvertisesHostSize() {
        // RemoteViewer hands these to TitledSwingWindow, which sizes the
        // SwingNode / Frame3D from them.
        assertTrue(RemoteViewerPanel.WIDTH_PX > 0);
        assertTrue(RemoteViewerPanel.HEIGHT_PX > 0);
    }

    @Test
    @DisplayName("the panel exposes the setOnClose(Runnable) hook both hosts wire")
    void exposesOnCloseHook() throws NoSuchMethodException {
        // Desktop2DAppRegistry.setCloseCallback looks this method up by exactly
        // this signature and silently no-ops when it is absent - which would
        // leave the 2D Exit button with no close path. Guard the contract the
        // reflective wiring depends on.
        assertNotNull(RemoteViewerPanel.class.getMethod("setOnClose", Runnable.class));
    }

    @Test
    @DisplayName("Exit runs the host close callback instead of killing the JVM")
    void exitRunsHostCallbackNotSystemExit() {
        RemoteViewerPanel panel = new RemoteViewerPanel();
        AtomicBoolean closed = new AtomicBoolean(false);
        panel.setOnClose(() -> closed.set(true));
        // exitApplication() is the confirmed-Exit action. It must delegate to the
        // host close callback; were it to call System.exit(0) the whole test JVM
        // would die here instead of reaching the assertion. No RMI server is
        // running in the test, so the Server.Stop() branch is skipped.
        panel.exitApplication();
        assertTrue(closed.get(), "Exit must invoke the host close callback");
    }
}
