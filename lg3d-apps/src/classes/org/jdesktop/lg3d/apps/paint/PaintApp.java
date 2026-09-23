/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.paint;

import java.awt.Color;
import javax.swing.SwingUtilities;

/**
 * Entry point for the Paint app. It is launched in-JVM from the desktop's start
 * menu (or via {@code -Dlg.swingapp}), so {@link #main} first installs the
 * mandatory cross-platform (Metal) look-and-feel - the frame is painted offscreen
 * into a texture by the capture layer, where the Synth/GTK system LAF would throw
 * - then builds and shows the {@link PaintFrame} on the EDT. The frame is an
 * ordinary {@code JFrame}, so {@code SwingNodeWindowCapture} picks it up and
 * presents it as a decorated 3D desktop window.
 *
 * <p>A static guard makes the app single-instance: a second launch while a Paint
 * window is already up is ignored, matching the native-app behaviour.</p>
 */
public class PaintApp {

    private static PaintFrame frame;

    private PaintApp() {
    }

    public static void main(String[] args) {
        if (frame != null) {
            // Already running; bring the existing window forward instead.
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    frame.toFront();
                }
            });
            return;
        }
        // Metal LAF must be installed before any Swing component is created.
        // The helper lives with the 3D window classes, so on the conventional
        // Swing (2D) desktop - a JVM with no Java 3D at all - it cannot load;
        // the platform LAF is the right look there anyway.
        try {
            org.jdesktop.lg3d.apps.TitledSwingWindow.installHostedLookAndFeel();
        } catch (Throwable t) {
            // Non-3D desktop: keep the default look and feel.
        }
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                PaintDocument doc = PaintDocument.create(800, 600, Color.WHITE);
                frame = new PaintFrame(doc);
                frame.setVisible(true);
            }
        });
    }
}
