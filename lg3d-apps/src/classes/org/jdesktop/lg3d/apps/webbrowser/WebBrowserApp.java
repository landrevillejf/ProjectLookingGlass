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
import java.awt.Image;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import javafx.application.Platform;
import javax.imageio.ImageIO;
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

    /** Classpath location of the browser window icon (assembled lg3d-core resource). */
    static final String ICON_RESOURCE = "resources/images/icon/webbrowser.png";

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
        Image icon = loadIcon();
        if (icon != null) {
            // Without this the standalone / child-process frame shows the OS
            // default window glyph (the "home folder"), because a bare JFrame
            // has no icon. The 2D MDI frame gets its icon from the descriptor
            // via lg3d-core; this covers the top-level surfaces.
            frame.setIconImage(icon);
        }
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

    /**
     * Loads the browser window icon from the assembled lg3d-core resources
     * ({@value #ICON_RESOURCE}). Best-effort: a missing or unreadable resource
     * yields null and the frame simply keeps the default icon, so the browser
     * still opens.
     *
     * @return the icon image, or null when unavailable
     */
    static Image loadIcon() {
        ClassLoader cl = WebBrowserApp.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(ICON_RESOURCE)) {
            if (in != null) {
                return ImageIO.read(in);
            }
        } catch (IOException | RuntimeException e) {
            // fall through: no icon is non-fatal
        }
        return null;
    }
}
