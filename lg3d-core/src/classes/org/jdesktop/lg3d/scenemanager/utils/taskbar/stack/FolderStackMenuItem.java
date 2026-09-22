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
import org.jdesktop.lg3d.scenemanager.utils.startmenu.data.DisplayResource;
import org.jdesktop.lg3d.scenemanager.utils.startmenu.data.MenuItem;

/**
 * A start-menu item backed by a real file system entry of a dock folder stack
 * (a file or sub-folder of {@code ~/Documents} / {@code ~/Downloads}), or by
 * the trailing <em>Show in File Manager</em> row of the list.
 *
 * <p>The item renders exactly like an application-list row: the icon URL is
 * carried as an {@link DisplayResource.Type#ICON} display resource (a cached
 * PNG of the desktop's own MIME icon, see {@link StackIconCache}) so
 * {@code PanelMenuItemComponent} draws it unchanged.</p>
 */
public class FolderStackMenuItem extends MenuItem {

    /** The file or directory this row opens. */
    private final Path path;

    /**
     * True for the trailing row that opens the stack's folder in the file
     * manager instead of opening {@link #getPath()} itself.
     */
    private final boolean folderAction;

    /**
     * @param name         the row label (the file name, or the action label)
     * @param path         the file system entry the row opens
     * @param folderAction true for the <em>Show in File Manager</em> row
     * @param iconUrl      cached PNG of the entry's icon, or null for the
     *                     model's default glyph
     */
    public FolderStackMenuItem(String name, Path path,
                               boolean folderAction, URL iconUrl) {
        super(name);
        this.path = path;
        this.folderAction = folderAction;
        // MenuItem(String) leaves the display resource null, which the panel
        // item component does not tolerate; fall back to the default glyph.
        setDisplayResource(iconUrl != null
                ? new DisplayResource(iconUrl) : new DisplayResource());
    }

    public Path getPath() {
        return path;
    }

    public boolean isFolderAction() {
        return folderAction;
    }
}
