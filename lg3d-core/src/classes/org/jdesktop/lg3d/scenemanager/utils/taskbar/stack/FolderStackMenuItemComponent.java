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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jdesktop.lg3d.scenemanager.utils.startmenu.model.panel.PanelMenuItemComponent;
import org.jdesktop.lg3d.utils.action.ActionNoArg;
import org.jdesktop.lg3d.utils.action.AppLaunchAction;
import org.jdesktop.lg3d.utils.eventadapter.MouseClickedEventAdapter;
import org.jdesktop.lg3d.utils.system.Opener;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jdesktop.lg3d.wg.event.MouseEvent3D.ButtonId;

/**
 * A row of a dock folder stack list: renders exactly like an application-list
 * row (glassy panel, MIME icon, bold label) and opens the file system entry the
 * row stands for when clicked - files with {@code xdg-open} via {@link Opener},
 * sub-folders and the trailing <em>Show in File Manager</em> row in the lg3d
 * file manager when it is on the classpath (falling back to {@code xdg-open}).
 *
 * <p>The base class already posts a {@code MenuItemExecutedEvent} on button 1
 * (its {@code ExecuteItemAction} finds no command to launch), which is what
 * hides the list after a row is picked - the same flow as the app list.</p>
 */
public class FolderStackMenuItemComponent extends PanelMenuItemComponent {

    private static final Logger logger = Logger.getLogger("lg.scenemanager");

    /** Fully-qualified main class of the Stage 3 file manager. */
    static final String FILE_MANAGER_CLASS =
            "org.jdesktop.lg3d.apps.filemanager.FileManager";

    /**
     * Instantiated reflectively by {@code FolderStackStartMenuModel}, which
     * passes the runtime item class ({@link FolderStackMenuItem}).
     */
    public FolderStackMenuItemComponent(FolderStackMenuItem item) {
        super(item);
    }

    @Override
    public void initialize() {
        super.initialize();
        final FolderStackMenuItem item = (FolderStackMenuItem) menuItem;
        addListener(new MouseClickedEventAdapter(ButtonId.BUTTON1,
                new ActionNoArg() {
                    @Override
                    public void performAction(LgEventSource source) {
                        open(item);
                    }
                }));
    }

    /** Opens the entry a row stands for (see class comment). */
    static void open(FolderStackMenuItem item) {
        if (item == null || item.getPath() == null) {
            return;
        }
        if (item.isFolderAction() || Files.isDirectory(item.getPath())) {
            openInFileManager(item.getPath());
        } else {
            Opener.open(item.getPath());
        }
    }

    /**
     * Opens a folder in the lg3d file manager at that directory when the file
     * manager is on the classpath; otherwise falls back to {@code xdg-open}
     * (the desktop's own file manager). Guarded so a missing file manager
     * never throws.
     */
    static boolean openInFileManager(Path dir) {
        try {
            Class.forName(FILE_MANAGER_CLASS);
            new AppLaunchAction("java " + FILE_MANAGER_CLASS + " "
                    + dir.toAbsolutePath(),
                    FolderStackMenuItemComponent.class.getClassLoader())
                    .performAction(null);
            return true;
        } catch (ClassNotFoundException notPresent) {
            return Opener.open(dir);
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "File manager launch failed for " + dir, e);
            return Opener.open(dir);
        }
    }
}
