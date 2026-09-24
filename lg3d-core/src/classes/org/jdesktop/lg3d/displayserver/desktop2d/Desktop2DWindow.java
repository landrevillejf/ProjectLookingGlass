/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.awt.Dimension;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JInternalFrame;
import javax.swing.JDesktopPane;

/**
 * One application window of the 2D desktop: an MDI internal frame hosting a
 * conventional Swing panel, the 2D counterpart of the 3D desktop's
 * {@code Frame3D}/{@code TitledSwingWindow}.
 *
 * <p>The frame is sized to the panel's preferred size, clamped to what the
 * desktop pane can actually show, and cascaded so several windows do not stack
 * exactly on top of each other.</p>
 */
public class Desktop2DWindow extends JInternalFrame {

    /** Cascade offset between consecutively opened windows. */
    private static final int CASCADE_STEP = 28;

    /** Margin kept around a window so its title bar stays reachable. */
    private static final int EDGE_MARGIN = 20;

    private final String appName;

    /**
     * The start-menu descriptor command this window was launched from, so the
     * session can relaunch it; null for a window that cannot be relaunched.
     */
    private final String command;

    /** The classpath icon resource, so a restored window keeps its art; may be null. */
    private final String iconResource;

    /**
     * @param title    the window title (the application's name)
     * @param icon     the application icon, or null for none
     * @param content  the application's Swing panel
     * @param appName  the application name used by the taskbar button
     */
    public Desktop2DWindow(String title, Icon icon, JComponent content,
                           String appName) {
        this(title, icon, content, appName, null, null);
    }

    /**
     * @param title        the window title (the application's name)
     * @param icon         the application icon, or null for none
     * @param content      the application's Swing panel
     * @param appName      the application name used by the taskbar button
     * @param command      the descriptor command to relaunch this app from, or
     *                     null if it cannot be relaunched (not session-saved)
     * @param iconResource the classpath icon location, or null for none
     */
    public Desktop2DWindow(String title, Icon icon, JComponent content,
                           String appName, String command, String iconResource) {
        super(title, true, true, true, true);
        this.appName = appName;
        this.command = command;
        this.iconResource = iconResource;
        setFrameIcon(icon);
        getContentPane().add(content);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    /** The application name (taskbar button label). */
    public String getAppName() {
        return appName;
    }

    /** The descriptor command this window was launched from, or null. */
    public String getCommand() {
        return command;
    }

    /** The classpath icon location this window was built with, or null. */
    public String getIconResource() {
        return iconResource;
    }

    /**
     * Sizes and places the window inside {@code desktop}, then shows it and
     * gives it the focus. Call on the EDT after the window has been added to
     * the desktop pane.
     */
    public void showIn(JDesktopPane desktop) {
        pack();
        clampTo(desktop);
        cascadeIn(desktop);
        setVisible(true);
        try {
            setSelected(true);
        } catch (java.beans.PropertyVetoException pve) {
            // Another window refused to give up the selection; not fatal.
        }
        toFront();
    }

    /** Never larger than the desktop pane, never smaller than a usable window. */
    private void clampTo(JDesktopPane desktop) {
        Dimension available = desktop.getSize();
        if (available.width <= 0 || available.height <= 0) {
            return;
        }
        int maxWidth = Math.max(200, available.width - EDGE_MARGIN);
        int maxHeight = Math.max(140, available.height - EDGE_MARGIN);
        Dimension size = getSize();
        size.width = Math.min(size.width, maxWidth);
        size.height = Math.min(size.height, maxHeight);
        size.width = Math.max(size.width, Math.min(320, maxWidth));
        size.height = Math.max(size.height, Math.min(200, maxHeight));
        setSize(size);
    }

    /** Offsets the window from the ones already open, wrapping at the edges. */
    private void cascadeIn(JDesktopPane desktop) {
        int depth = 0;
        for (JInternalFrame frame : desktop.getAllFrames()) {
            if (frame != this && frame.isVisible() && !frame.isIcon()) {
                depth++;
            }
        }
        int offset = (depth % 8) * CASCADE_STEP;
        Dimension available = desktop.getSize();
        int x = EDGE_MARGIN + offset;
        int y = EDGE_MARGIN + offset;
        if (available.width > 0) {
            x = Math.min(x, Math.max(0, available.width - getWidth() - EDGE_MARGIN));
        }
        if (available.height > 0) {
            y = Math.min(y, Math.max(0, available.height - getHeight() - EDGE_MARGIN));
        }
        setLocation(x, y);
    }
}
