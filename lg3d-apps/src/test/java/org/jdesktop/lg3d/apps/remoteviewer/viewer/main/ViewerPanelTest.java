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
package org.jdesktop.lg3d.apps.remoteviewer.viewer.main;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Window;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.JPanel;
import org.jdesktop.lg3d.apps.remoteviewer.viewer.rmi.Viewer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;

/**
 * Headless tests for the Remote Viewer's live-viewing {@link ViewerPanel}.
 *
 * <p>The viewer used to be a {@code ViewerGUI extends JFrame} created eagerly by
 * {@link Recorder}'s constructor. A top-level {@code JFrame} is an OS window
 * owned by the host window manager, so it was neither a child of the 2D
 * desktop's {@code JDesktopPane} nor part of the 3D scene: the internal screen
 * capture painted each top-level frame into its own file and the viewer never
 * appeared in {@code lgscreen-0-0.png}. Extracting the content into a plain
 * {@link JPanel} that a host embeds is the fix, and the regression these tests
 * pin is exactly that: <em>constructing the viewer content must not create a
 * top-level {@link Window}</em>.</p>
 *
 * <p>They also guard the three host hooks the 2D / 3D / standalone hosts wire
 * ({@code setOnClose}, {@code setOnTitleChange}, {@code setOnMaximizeToggle})
 * and that the confirmed Close action delegates to the host callback instead of
 * disposing a window or killing the JVM. CI-safe: {@code java.awt.headless=true},
 * no display, no RMI server.</p>
 */
class ViewerPanelTest {

    private static Recorder newRecorder() {
        // Recorder is a daemon thread that parks in wait(); its constructor also
        // builds the ViewerPanel under test (recorder.viewerPanel), so it must be
        // constructible headless. No connect() happens, so no server is needed.
        return new Recorder(new Viewer());
    }

    @Test
    @DisplayName("constructing the viewer panel creates no top-level Window")
    void panelCreatesNoTopLevelWindow() {
        Recorder recorder = newRecorder();
        int before = Window.getWindows().length;
        ThrowingSupplier<ViewerPanel> ctor = () -> new ViewerPanel(recorder);
        ViewerPanel panel = assertDoesNotThrow(ctor);
        int after = Window.getWindows().length;

        assertNotNull(panel);
        assertTrue(panel instanceof JPanel);
        assertTrue(panel.getComponentCount() > 0,
                "the panel should lay out its toolbar and screen view");
        // The regression that caused the viewer to escape the desktop screenshot:
        // a JFrame/Window created here would be a stray top-level OS window.
        assertEquals(before, after,
                "constructing ViewerPanel must not create a top-level Window");
    }

    @Test
    @DisplayName("the panel exposes the three host hooks the hosts wire")
    void exposesHostHooks() throws NoSuchMethodException {
        assertNotNull(ViewerPanel.class.getMethod("setOnClose", Runnable.class));
        assertNotNull(ViewerPanel.class.getMethod(
                "setOnTitleChange", Consumer.class));
        assertNotNull(ViewerPanel.class.getMethod(
                "setOnMaximizeToggle", Runnable.class));
    }

    @Test
    @DisplayName("the panel advertises a positive host size")
    void panelAdvertisesHostSize() {
        // ViewerHost hands these to TitledSwingWindow, which sizes the SwingNode
        // / Frame3D from them.
        assertTrue(ViewerPanel.WIDTH_PX > 0);
        assertTrue(ViewerPanel.HEIGHT_PX > 0);
    }

    @Test
    @DisplayName("Close runs the host callback instead of killing the JVM")
    void closeRunsHostCallbackNotSystemExit() {
        Recorder recorder = newRecorder();
        ViewerPanel panel = new ViewerPanel(recorder);
        AtomicBoolean closed = new AtomicBoolean(false);
        panel.setOnClose(() -> closed.set(true));
        // closeViewer() is the confirmed-Close action. It must delegate to the
        // host close callback; were it to call System.exit(0) the whole test JVM
        // would die here instead of reaching the assertion. No session is live
        // (isRecording() is false), so the viewer.Stop() branch is skipped.
        panel.closeViewer();
        assertTrue(closed.get(), "Close must invoke the host close callback");
    }

    @Test
    @DisplayName("the maximize toggle runs the host callback (no OS full-screen)")
    void maximizeToggleRunsHostCallback() {
        Recorder recorder = newRecorder();
        ViewerPanel panel = new ViewerPanel(recorder);
        AtomicBoolean toggled = new AtomicBoolean(false);
        panel.setOnMaximizeToggle(() -> toggled.set(true));
        // F11 on the screen and the Full/Normal button both route here; the host
        // decides how to maximize-within-desktop. No callback set means a no-op
        // (headless-safe), never GraphicsDevice.setFullScreenWindow.
        panel.toggleMaximize();
        assertTrue(toggled.get(),
                "toggleMaximize must invoke the host maximize callback");
    }
}
