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

import javax.swing.JFrame;
import javax.swing.WindowConstants;

/**
 * The standalone (CLI {@code viewer} mode) host for the Remote Viewer: a thin
 * {@link JFrame} wrapper around the shared {@link ViewerPanel}.
 *
 * <p>This is the <em>only</em> place a top-level window is created for the
 * viewer, and it is used only when the viewer runs outside a desktop - i.e.
 * {@code Main ... viewer} with no 2D/3D lg3d desktop in the JVM (see
 * {@link org.jdesktop.lg3d.apps.remoteviewer.ViewerHost}, which prefers hosting
 * the panel inside the desktop and falls back here). Inside the desktop the
 * panel is hosted as a 2D MDI internal
 * frame or a 3D {@code Frame3D} instead, so it belongs to the desktop and is
 * captured by the desktop screenshot rather than escaping it as a stray OS
 * window.</p>
 *
 * <p>All of the viewer's content and behaviour lives in {@link ViewerPanel};
 * this class only supplies the window chrome and maps the panel's three host
 * hooks onto ordinary {@code JFrame} idioms: Close disposes the frame, a title
 * change calls {@link #setTitle}, and the Full/Normal toggle maximizes /
 * restores the frame.</p>
 *
 * @author benbac
 */
public class ViewerGUI extends JFrame {

    /** Creates and shows the standalone viewer window for {@code recorder}. */
    public ViewerGUI(Recorder recorder) {
        ViewerPanel panel = recorder.viewerPanel;
        setTitle("Remote Viewer");
        // Never EXIT_ON_CLOSE: even standalone, closing must not tear down a JVM
        // that may be hosting more than this window.
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setContentPane(panel);

        // Map the panel's host hooks onto this frame's window idioms.
        panel.setOnClose(this::dispose);
        panel.setOnTitleChange(this::setTitle);
        panel.setOnMaximizeToggle(this::toggleMaximized);

        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    /** Maximizes the frame, or restores it if it is already maximized. */
    private void toggleMaximized() {
        if ((getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH) {
            setExtendedState(JFrame.NORMAL);
        } else {
            setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);
        }
    }
}
