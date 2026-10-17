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

import org.jdesktop.lg3d.apps.TitledSwingWindow;

/**
 * The Web Server Console application: the {@code lg3d-webserver} management
 * console presented as an integrated 3D desktop window (title bar plus minimize /
 * maximize / close) via {@link TitledSwingWindow}, which hosts the
 * {@link WebServerConsolePanel} on a {@code SwingNode} quad below a draggable
 * glassy title bar.
 *
 * <p>This is the 3D-desktop entry point (Start Menu &rarr; Developers &rarr; Web
 * Server Console). In the 2D/Swing desktop the {@link WebServerPanel} shim wraps
 * the very same {@link WebServerConsolePanel} as an MDI internal frame via
 * {@code Desktop2DAppRegistry}, so this wrapper is never loaded there &mdash; only
 * the panel is.</p>
 */
public class WebServerConsole {

    /** Preferred width of the hosted panel, in pixels. */
    public static final int WIDTH_PX = WebServerConsolePanel.WIDTH_PX;
    /** Preferred height of the hosted panel, in pixels. */
    public static final int HEIGHT_PX = WebServerConsolePanel.HEIGHT_PX;

    public static void main(String[] args) {
        new WebServerConsole();
    }

    public WebServerConsole() {
        // Metal, not the platform Synth LAF: Synth widgets NPE when SwingNode
        // paints them offscreen. Must run before the panel is constructed.
        TitledSwingWindow.installHostedLookAndFeel();
        TitledSwingWindow.show(
                "Web Server Console",
                new WebServerConsolePanel(),
                WIDTH_PX,
                HEIGHT_PX);
    }
}
