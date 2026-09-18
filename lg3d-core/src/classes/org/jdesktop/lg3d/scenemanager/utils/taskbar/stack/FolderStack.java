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

import java.net.URL;
import java.nio.file.Path;
import org.jdesktop.lg3d.utils.action.ActionBoolean;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.component.Pseudo3DIcon;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.eventadapter.MouseEnteredEventAdapter;
import org.jdesktop.lg3d.wg.Tapp;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Vector3f;

/**
 * An OSX-style folder stack on the right side of the taskbar: a folder
 * {@link Pseudo3DIcon} that opens a {@link FolderStackPopup} fanning out the
 * folder's most-recent entries as an arc of icons (Leopard-style). Hovering the
 * icon opens the fan; clicking it toggles the fan open/closed.
 *
 * <p>Instances are created by {@link StacksPlugin} (one for Documents, one for
 * Downloads) and posted to the taskbar with a negative item index so they sit
 * immediately before the Exit icon.</p>
 */
public class FolderStack extends Tapp {

    private final FolderStackModel model;
    private final String displayName;
    private FolderStackPopup popup;

    /**
     * @param directory   the folder this stack represents
     * @param displayName human-readable stack name (e.g. "Documents")
     * @param iconUrl     taskbar icon image (a folder glyph)
     */
    public FolderStack(Path directory, String displayName, URL iconUrl) {
        this.model = new FolderStackModel(directory);
        this.displayName = displayName;

        Pseudo3DIcon icon = new Pseudo3DIcon(iconUrl);
        // Hovering the dock icon fans out the recent entries (Leopard-style).
        icon.addListener(new MouseEnteredEventAdapter(new ActionBoolean() {
            @Override
            public void performAction(LgEventSource source, boolean entered) {
                if (entered) {
                    show();
                }
            }
        }));
        // A click still toggles, giving an explicit way to dismiss the fan.
        icon.addListener(new MouseClickedEventAdapter(new ActionNoArg() {
            @Override
            public void performAction(LgEventSource source) {
                toggle();
            }
        }));
        addChild(icon);
        setPreferredSize(icon.getPreferredSize(new Vector3f()));
    }

    public FolderStackModel getModel() {
        return model;
    }

    public String getStackDisplayName() {
        return displayName;
    }

    /** Shows the popup if it is not already visible. */
    public synchronized void show() {
        if (popup == null) {
            popup = new FolderStackPopup(model);
        }
        if (!popup.isVisible()) {
            popup.show();
        }
    }

    /** Shows the popup if hidden, hides it if shown. */
    public synchronized void toggle() {
        if (popup != null && popup.isVisible()) {
            popup.hide();
        } else {
            show();
        }
    }
}
