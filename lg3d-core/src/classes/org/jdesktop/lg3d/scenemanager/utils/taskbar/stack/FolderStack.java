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

import java.net.URL;
import java.nio.file.Path;
import org.jdesktop.lg3d.utils.action.ActionBoolean;
import org.jdesktop.lg3d.utils.component.Pseudo3DIcon;
import org.jdesktop.lg3d.utils.eventadapter.MouseHoverEventAdapter;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Tapp;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Vector3f;

/**
 * A folder stack on the right side of the taskbar: a folder
 * {@link Pseudo3DIcon} whose hover raises the folder's entries as the same
 * glassy vertical list the start menu shows for its application groups
 * ({@link FolderStackStartMenuModel}), anchored above the icon and opening
 * leftward since the stack sits at the right screen edge. Leaving the icon -
 * and the list - hides it again: exactly the application list's behaviour.
 *
 * <p>Instances are created by {@link StacksPlugin} (one for Documents, one for
 * Downloads) and posted to the taskbar with a negative item index so they sit
 * immediately before the Exit icon.</p>
 */
public class FolderStack extends Tapp {

    /** Half the width of a list row (the panel item's preferred width). */
    private static final float COLUMN_HALF_WIDTH = 0.0225f;

    /** X offset the raised pose applies to the column (see StartMenuModel). */
    private static final float RAISED_X_OFFSET = 0.005f;

    /** Gap kept between the dock icon and the list's near edge. */
    private static final float ICON_GAP = 0.004f;

    private final FolderStackModel model;
    private final String displayName;
    private final FolderStackStartMenuModel menuModel;

    /**
     * @param directory   the folder this stack represents
     * @param displayName human-readable stack name (e.g. "Documents")
     * @param iconUrl     taskbar icon image (a folder glyph)
     */
    public FolderStack(Path directory, String displayName, URL iconUrl) {
        this.model = new FolderStackModel(directory);
        this.displayName = displayName;

        Pseudo3DIcon icon = new Pseudo3DIcon(iconUrl);
        addChild(icon);
        setPreferredSize(icon.getPreferredSize(new Vector3f()));

        menuModel = new FolderStackStartMenuModel(model);
        menuModel.initialize();

        // The list column is centred on the model origin; shift it fully to
        // the left of the icon so a right-docked stack opens leftward, like
        // any desktop menu raised near the right screen edge.
        Component3D anchor = new Component3D();
        Vector3f iconSize = getPreferredSize(new Vector3f());
        anchor.setTranslation(-(iconSize.x * 0.5f + ICON_GAP
                + COLUMN_HALF_WIDTH - RAISED_X_OFFSET), 0.0f, 0.0f);
        anchor.addChild(menuModel);
        addChild(anchor);

        // Same hover contract as the application list: raise on enter, hide on
        // leave. The raised list keeps the hover alive through its pickable
        // region and rows, so moving onto the list does not dismiss it.
        addListener(new MouseHoverEventAdapter(0, 500, 0, new ActionBoolean() {
            @Override
            public void performAction(LgEventSource source, boolean entered) {
                if (entered && !menuModel.isVisible()) {
                    menuModel.changeVisible(true, false);
                } else if (!entered && menuModel.isVisible()) {
                    menuModel.changeVisible(false, false);
                }
            }
        }));
    }

    public FolderStackModel getModel() {
        return model;
    }

    public String getStackDisplayName() {
        return displayName;
    }

    /** The raised list of this stack. */
    public FolderStackStartMenuModel getMenuModel() {
        return menuModel;
    }
}
