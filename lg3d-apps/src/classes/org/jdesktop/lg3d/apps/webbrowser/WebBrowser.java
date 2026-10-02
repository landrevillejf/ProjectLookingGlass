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
package org.jdesktop.lg3d.apps.webbrowser;

import org.jdesktop.lg3d.apps.TitledSwingWindow;

/**
 * The Web Browser application's 3D-desktop entry point (Start Menu &rarr;
 * Internet &rarr; Web Browser). It shows the pure-Swing
 * {@link BrowserPreviewPanel} as an integrated 3D window via
 * {@link TitledSwingWindow}, which hosts the panel on a {@code SwingNode} quad
 * below a draggable glassy title bar.
 *
 * <p>Deliberately, this builds <em>no</em> JavaFX and no {@code Frame3D}-touching
 * {@code WebView}: a JavaFX surface is a heavyweight peer that paints blank when
 * the {@code SwingNode} captures it offscreen, and initialising JavaFX here would
 * clash with the Java&nbsp;3D OpenGL context. The preview's "Open Full Browser"
 * button instead spawns {@link WebBrowserApp} into a separate child-process JVM,
 * so the interactive {@link BrowserPanel} runs isolated from this process.</p>
 *
 * <p>In the 2D/Swing desktop the very same command opens the full interactive
 * {@link BrowserPanel} as an MDI internal frame (see {@code Desktop2DAppRegistry}),
 * so this wrapper is never loaded there &mdash; only the panel is.</p>
 */
public class WebBrowser {

    /**
     * Shows the preview window.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        new WebBrowser();
    }

    /** Builds and shows the 3D-desktop preview window. */
    public WebBrowser() {
        // Metal, not the platform Synth LAF: Synth widgets NPE when SwingNode
        // paints them offscreen. Must run before the panel is constructed.
        TitledSwingWindow.installHostedLookAndFeel();
        TitledSwingWindow.show(
                "Web Browser",
                new BrowserPreviewPanel(),
                BrowserPreviewPanel.WIDTH_PX,
                BrowserPreviewPanel.HEIGHT_PX);
    }
}
