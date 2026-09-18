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

/**
 * The control center application: a 100% lg3d-native 3D shell
 * ({@link ControlCenterFrame3D}) with Display / Users / System / Appearance
 * pages, launched in-JVM by the desktop from {@code controlcenter.lgcfg}.
 */
public class ControlCenter {

    public static void main(String[] args) {
        new ControlCenter();
    }

    public ControlCenter() {
        ControlCenterFrame3D frame3d = new ControlCenterFrame3D();
        frame3d.changeEnabled(true);
        frame3d.changeVisible(true);
    }
}
