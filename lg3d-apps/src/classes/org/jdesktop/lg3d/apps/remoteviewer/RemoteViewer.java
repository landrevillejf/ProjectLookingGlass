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

import org.jdesktop.lg3d.apps.TitledSwingWindow;
import org.jdesktop.lg3d.wg.Frame3D;

/**
 * The Remote Viewer application (ported from the standalone jrdesktop /
 * Remote Viewer RMI remote-desktop tool): the original {@link RemoteViewerPanel}
 * Swing UI (server start/stop, status, viewer connect, file transfer, about,
 * exit - unchanged from the upstream {@code MainFrame}) presented as an
 * integrated 3D desktop window (title bar plus minimize / maximize / close)
 * via {@link TitledSwingWindow}, exactly like every other hosted lg3d
 * application (Password Manager, Docker Manager, Paint, ...).
 */
public class RemoteViewer {

    private static RemoteViewerPanel panel;
    private static Frame3D frame;

    public static void main(String[] args) {
        show();
    }

    /**
     * Shows the Remote Viewer window, reusing the existing one if already
     * open. Mirrors the idempotent "don't open a second window" behaviour of
     * the original standalone {@code MainFrame.main(String[])} / SysTray
     * double-click, adapted to the hosted (non-{@code JFrame}) panel.
     */
    public static synchronized void show() {
        if (panel != null) {
            return;
        }
        TitledSwingWindow.installHostedLookAndFeel();
        panel = new RemoteViewerPanel();
        frame = TitledSwingWindow.show("Remote Viewer", panel,
                RemoteViewerPanel.WIDTH_PX, RemoteViewerPanel.HEIGHT_PX);
    }

    private RemoteViewer() {
    }
}
