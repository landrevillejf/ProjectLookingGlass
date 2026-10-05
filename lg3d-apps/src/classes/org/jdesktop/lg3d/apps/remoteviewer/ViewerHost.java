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

import com.protonmail.landrevillejf.IconManager;
import org.jdesktop.lg3d.apps.TitledSwingWindow;
import org.jdesktop.lg3d.apps.remoteviewer.viewer.main.Recorder;
import org.jdesktop.lg3d.apps.remoteviewer.viewer.main.ViewerGUI;
import org.jdesktop.lg3d.apps.remoteviewer.viewer.main.ViewerPanel;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DWindow;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.HostedWindowResizer;
import org.jdesktop.lg3d.wg.Toolkit3D;

import javax.swing.Icon;

/**
 * Hosts the Remote Viewer's live-viewing panel ({@link Recorder#viewerPanel})
 * inside whichever desktop is running, so the viewer window <em>belongs</em> to
 * the desktop and is captured by the desktop screenshot instead of escaping it
 * as a stray top-level OS window beside the desktop.
 *
 * <p>The old code created a {@link ViewerGUI} {@code JFrame} eagerly from
 * {@link Recorder}'s constructor. A top-level {@code JFrame} is owned by the host
 * window manager: it is neither a child of the 2D desktop's {@code JDesktopPane}
 * nor part of the 3D scene, so the internal screen capture painted each
 * top-level frame into its own file and the viewer never landed in
 * {@code lgscreen-0-0.png}. This host embeds the shared panel instead, choosing
 * the container at runtime:</p>
 *
 * <ol>
 *   <li><b>2D desktop</b> - {@link Desktop2D#openHostedPanel} wraps the panel in
 *       an MDI {@link Desktop2DWindow} (a {@code JInternalFrame}). Returns null
 *       when no 2D desktop is running.</li>
 *   <li><b>3D desktop</b> - {@link TitledSwingWindow#show} hosts the panel on a
 *       {@code SwingNode} inside a {@link Frame3D}. Guarded by try/catch: with no
 *       3D toolkit it throws and we fall through.</li>
 *   <li><b>Standalone</b> - {@link ViewerGUI}, a plain {@code JFrame}, so the CLI
 *       {@code Main ... viewer} mode (no desktop in the JVM) still works.</li>
 * </ol>
 *
 * <p>Each host wires the panel's three hooks onto its own window idioms; when
 * hosted, "full screen" means maximize-within-desktop (never exclusive OS
 * full-screen, which the standalone {@code JFrame} handles on its own).</p>
 */
public final class ViewerHost {

    /** The 3D-hosted viewer frame, if any, so {@link #close()} can disable it. */
    private static Frame3D frame3d;

    private ViewerHost() {
    }

    /**
     * Shows {@code recorder}'s viewer panel in the best available host and wires
     * the panel's close / title / maximize hooks to that host.
     */
    public static void show(Recorder recorder) {
        final ViewerPanel panel = recorder.viewerPanel;

        // 1. 2D desktop: an MDI internal frame inside the JDesktopPane.
        Desktop2DWindow window = null;
        try {
            Icon icon = IconManager.loadIcon(
                    IconManager.IconCategory.DEVELOPMENT, "Host", 16, 16);
            window = Desktop2D.openHostedPanel("Remote Viewer", icon, panel);
        } catch (Throwable t) {
            // NoClassDefFoundError included: on a 3D-less / headless JVM the 2D
            // shell classes may not even load. Fall through to the next host.
            window = null;
        }
        if (window != null) {
            final Desktop2DWindow w = window;
            panel.setOnClose(w::dispose);
            panel.setOnTitleChange(w::setTitle);
            panel.setOnMaximizeToggle(() -> {
                try {
                    w.setMaximum(!w.isMaximum());
                } catch (java.beans.PropertyVetoException pve) {
                    // Another window refused to give up maximized state; ignore.
                }
            });
            return;
        }

        // 2. 3D desktop: a SwingNode inside a Frame3D.
        try {
            TitledSwingWindow.installHostedLookAndFeel();
            final Frame3D frame = TitledSwingWindow.show("Remote Viewer", panel,
                    ViewerPanel.WIDTH_PX, ViewerPanel.HEIGHT_PX);
            frame3d = frame;
            panel.setOnClose(() -> {
                frame.changeEnabled(false);
                frame3d = null;
            });
            panel.setOnTitleChange(frame::setName);
            final boolean[] maximized = {false};
            panel.setOnMaximizeToggle(() -> {
                Toolkit3D tk = Toolkit3D.getToolkit3D();
                if (maximized[0]) {
                    HostedWindowResizer.resize(frame,
                            ViewerPanel.WIDTH_PX, ViewerPanel.HEIGHT_PX);
                    maximized[0] = false;
                } else {
                    HostedWindowResizer.resize(frame,
                            tk.widthPhysicalToNative(tk.getScreenWidth()),
                            tk.heightPhysicalToNative(tk.getScreenHeight()));
                    maximized[0] = true;
                }
            });
            return;
        } catch (Throwable t) {
            // No 3D toolkit (Toolkit3D is null outside the 3D desktop): fall
            // through to the standalone JFrame.
        }

        // 3. Standalone (CLI `viewer` mode, no desktop in the JVM).
        new ViewerGUI(recorder);
    }

    /**
     * Closes a 3D-hosted viewer window, if one is open, using the desktop's
     * normal window-close idiom (disable the {@link Frame3D}). Never terminates
     * the JVM, which would tear down the desktop this app runs inside.
     */
    public static synchronized void close() {
        if (frame3d != null) {
            frame3d.changeEnabled(false);
            frame3d = null;
        }
    }
}
