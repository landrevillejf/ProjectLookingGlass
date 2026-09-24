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
package org.jdesktop.lg3d.apps.controlcenter;

import org.jdesktop.lg3d.apps.TitledSwingWindow;

/**
 * The control center application: the {@link ControlCenterPanel} (Display /
 * Users / System / Appearance / Desktop) presented as an integrated 3D desktop
 * window (title bar plus minimize / maximize / close) via
 * {@link TitledSwingWindow}.
 */
public class ControlCenter {

    private static final int PANEL_W = 720;
    private static final int PANEL_H = 500;

    public static void main(String[] args) {
        new ControlCenter();
    }

    public ControlCenter() {
        TitledSwingWindow.installHostedLookAndFeel();
        final ControlCenterPanel panel = new ControlCenterPanel();
        TitledSwingWindow.show("Control Center", panel, PANEL_W, PANEL_H);
    }
}
