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
package org.jdesktop.lg3d.apps.taskmanager;

import org.jdesktop.lg3d.apps.TitledSwingWindow;
import org.jdesktop.lg3d.wg.Frame3D;

/**
 * The task manager application: the {@link TaskManagerPanel} Swing UI presented
 * as an integrated 3D desktop window (title bar plus minimize / maximize /
 * close) via {@link TitledSwingWindow}.
 *
 * <p>The panel drives its own two-second refresh timer; its Close button stops
 * the timer and clears the cached CPU samples before the window is closed.</p>
 */
public class TaskManager {

    private static final int PANEL_W = 680;
    private static final int PANEL_H = 480;

    public static void main(String[] args) {
        new TaskManager();
    }

    public TaskManager() {
        TitledSwingWindow.installHostedLookAndFeel();
        final TaskManagerPanel panel = new TaskManagerPanel();
        final Frame3D frame =
                TitledSwingWindow.show("Task Manager", panel, PANEL_W, PANEL_H);
        panel.setOnClose(() -> frame.changeEnabled(false));
    }
}
