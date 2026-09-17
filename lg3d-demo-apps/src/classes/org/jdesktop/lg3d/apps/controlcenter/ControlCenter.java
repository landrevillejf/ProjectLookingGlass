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
package org.jdesktop.lg3d.apps.controlcenter;

import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.SwingNode;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jogamp.vecmath.Vector3f;

/**
 * The control center application: a {@link Frame3D} hosting the
 * {@link ControlCenterPanel} (Display / Users / System / Appearance) on a
 * {@link SwingNode}.
 */
public class ControlCenter {

    private static final int PANEL_W = 720;
    private static final int PANEL_H = 500;

    public static void main(String[] args) {
        new ControlCenter();
    }

    public ControlCenter() {
        final ControlCenterPanel panel = new ControlCenterPanel();

        SwingNode swingNode = new SwingNode();
        swingNode.setJPanel(panel);
        swingNode.setTransparency(0.0f);

        final Frame3D frame3d = new Frame3D();
        frame3d.setName("Control Center");
        frame3d.addChild(swingNode);

        Toolkit3D tk = Toolkit3D.getToolkit3D();
        float w = tk.widthNativeToPhysical(PANEL_W);
        float h = tk.heightNativeToPhysical(PANEL_H);
        frame3d.setPreferredSize(new Vector3f(w, h, 0.01f));
        frame3d.changeEnabled(true);
        frame3d.changeVisible(true);
    }
}
