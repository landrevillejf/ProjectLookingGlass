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
package org.jdesktop.lg3d.scenemanager.utils.taskbar.stack;

import org.jdesktop.lg3d.scenemanager.utils.startmenu.data.MenuGroup;
import org.jdesktop.lg3d.scenemanager.utils.startmenu.model.panel.PanelMenuGroupComponent;

/**
 * The vertical glassy column of a dock folder stack list. Identical to the
 * application list column; this class exists (under the name the
 * {@code StartMenuModel} reflection scheme derives from
 * {@code FolderStackStartMenuModel}) so a stack list lays out and cycles its
 * rows exactly like the app list.
 */
public class FolderStackMenuGroupComponent extends PanelMenuGroupComponent {

    /**
     * Instantiated reflectively by {@code FolderStackStartMenuModel}.
     */
    public FolderStackMenuGroupComponent(MenuGroup menuGroup) {
        super(menuGroup);
    }
}
