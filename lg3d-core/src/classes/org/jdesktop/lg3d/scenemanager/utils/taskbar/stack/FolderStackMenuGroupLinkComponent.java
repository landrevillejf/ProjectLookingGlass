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
package org.jdesktop.lg3d.scenemanager.utils.taskbar.stack;

import org.jdesktop.lg3d.scenemanager.utils.startmenu.data.MenuGroup;
import org.jdesktop.lg3d.scenemanager.utils.startmenu.model.panel.PanelMenuGroupLinkComponent;

/**
 * Group-link row class of a dock folder stack list. A stack list holds a
 * single group (the folder's entries) and never links to another group, so
 * this component is never instantiated; it exists only because the
 * {@code StartMenuModel} reflection scheme resolves the three component class
 * names from the model class name and logs a severe error when one is missing.
 */
public class FolderStackMenuGroupLinkComponent
        extends PanelMenuGroupLinkComponent {

    /**
     * Instantiated reflectively by {@code StartMenuModel} (never in practice).
     */
    public FolderStackMenuGroupLinkComponent(MenuGroup localGroup,
                                             MenuGroup remoteGroup) {
        super(localGroup, remoteGroup);
    }
}
