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

/**
 * The task manager application: a 100% lg3d-native 3D process monitor
 * ({@link TaskManagerFrame3D}), launched in-JVM by the desktop from
 * {@code taskmanager.lgcfg}.
 */
public class TaskManager {

    public static void main(String[] args) {
        new TaskManager();
    }

    public TaskManager() {
        TaskManagerFrame3D frame3d = new TaskManagerFrame3D();
        frame3d.changeEnabled(true);
        frame3d.changeVisible(true);
    }
}
