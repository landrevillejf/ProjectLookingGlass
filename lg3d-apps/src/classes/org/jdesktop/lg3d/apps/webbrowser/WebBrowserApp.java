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

import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javafx.application.Platform;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * The full browser as a top-level {@link JFrame}: a {@link BrowserPanel} in its
 * own window. This is the {@code main} the 3D desktop's
 * {@link BrowserPreviewPanel} spawns into a <em>separate child-process JVM</em>
 * (via its "Open Full Browser" button), and it is also runnable standalone.
 *
 * <p>A heavyweight top-level frame is the ideal host for the {@code JFXPanel}
 * inside {@link BrowserPanel}: the JavaFX surface is a native peer that composites
 * correctly against a real window, which is exactly what the 3D desktop cannot
 * offer (its windows are captured offscreen into a texture, where a heavyweight
 * peer paints blank). Running this in its own JVM also keeps JavaFX and its
 * WebKit/OpenGL pipeline out of the Java&nbsp;3D process.</p>
 *
 * <p>The frame disposes rather than exits, releasing the JavaFX engines and
 * toolkit on close; because this JVM exists solely to host the browser, it then
 * terminates so no stray process lingers after the last window closes.</p>
 */
public final class WebBrowserApp {

    private WebBrowserApp() {
        // no instances
    }

    /**
     * Shows the browser frame.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(WebBrowserApp::createAndShow);
    }

    private static void createAndShow() {
        JFrame frame = new JFrame("Web Browser");
        BrowserPanel panel = new BrowserPanel();
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.getContentPane().add(panel, BorderLayout.CENTER);
        frame.setSize(BrowserPanel.WIDTH_PX, BrowserPanel.HEIGHT_PX);
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                panel.shutdown();
                // This JVM was spawned only to host the browser, so let it exit
                // once the toolkit is torn down; a lingering Swing/EDT thread
                // would otherwise keep the process alive after the last window.
                Platform.exit();
                System.exit(0);
            }
        });
        frame.setVisible(true);
    }
}
