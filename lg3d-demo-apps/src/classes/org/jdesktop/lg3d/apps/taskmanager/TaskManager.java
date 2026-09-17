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
package org.jdesktop.lg3d.apps.taskmanager;

import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.SwingNode;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jogamp.vecmath.Vector3f;

/**
 * The task manager application: a {@link Frame3D} hosting the
 * {@link TaskManagerPanel} Swing UI on a {@link SwingNode}.
 *
 * <p>The panel drives its own two-second refresh timer; closing the window
 * stops the timer and clears the cached CPU samples.</p>
 */
public class TaskManager {

    private static final int PANEL_W = 680;
    private static final int PANEL_H = 480;

    public static void main(String[] args) {
        new TaskManager();
    }

    public TaskManager() {
        final TaskManagerPanel panel = new TaskManagerPanel();

        SwingNode swingNode = new SwingNode();
        swingNode.setJPanel(panel);
        swingNode.setTransparency(0.0f);

        final Frame3D frame3d = new Frame3D();
        frame3d.setName("Task Manager");
        frame3d.addChild(swingNode);

        panel.setOnClose(new Runnable() {
            @Override
            public void run() {
                frame3d.changeEnabled(false);
            }
        });

        Toolkit3D tk = Toolkit3D.getToolkit3D();
        float w = tk.widthNativeToPhysical(PANEL_W);
        float h = tk.heightNativeToPhysical(PANEL_H);
        frame3d.setPreferredSize(new Vector3f(w, h, 0.01f));
        frame3d.changeEnabled(true);
        frame3d.changeVisible(true);
    }
}
