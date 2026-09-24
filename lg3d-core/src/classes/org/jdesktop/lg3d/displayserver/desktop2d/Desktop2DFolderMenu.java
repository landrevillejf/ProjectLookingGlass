/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import org.jdesktop.lg3d.scenemanager.utils.taskbar.stack.FolderStackModel;

/**
 * The 2D desktop's Documents / Downloads dock menus: the conventional-Swing
 * counterpart of the taskbar folder stacks.
 *
 * <p>Content comes from the very same {@link FolderStackModel} the 3D stacks
 * use (it is Java 3D free), so the two desktops list the same entries in the
 * same order: the {@value #MAX_ENTRIES} most recently modified, newest first,
 * with the real system file-type icons, plus a "Show in File Manager" entry at
 * the bottom. The listing is rebuilt every time the menu pops up, matching the
 * 3D stacks, which refresh on raise.</p>
 */
public final class Desktop2DFolderMenu {

    /** How many of the most-recent entries the menu shows. */
    public static final int MAX_ENTRIES = 12;

    /** Label length before a name is ellipsised. */
    static final int MAX_LABEL = 36;

    /** The trailing entry that opens the folder itself. */
    public static final String SHOW_IN_FILE_MANAGER = "Show in File Manager";

    /** Shown when the folder is missing, unreadable or empty. */
    static final String EMPTY_LABEL = "(empty)";

    private Desktop2DFolderMenu() {
        // no instances
    }

    /**
     * The entries a folder menu lists: the {@value #MAX_ENTRIES} most recently
     * modified entries of {@code directory}, newest first.
     */
    static List<FolderStackModel.StackItem> entries(Path directory) {
        FolderStackModel model = new FolderStackModel(directory, MAX_ENTRIES);
        model.refresh();
        return new ArrayList<>(model.getItems());
    }

    /**
     * Builds the popup menu for {@code directory}.
     *
     * @param directory    the folder to list (e.g. {@code ~/Documents})
     * @param onOpenFile   invoked with the path of the clicked file
     * @param onOpenFolder invoked with the folder to show in the file manager
     */
    public static JPopupMenu create(final Path directory,
                                    final Consumer<Path> onOpenFile,
                                    final Consumer<Path> onOpenFolder) {
        final JPopupMenu menu = new JPopupMenu();
        populate(menu, directory, onOpenFile, onOpenFolder);
        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                populate(menu, directory, onOpenFile, onOpenFolder);
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                // nothing to do
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                // nothing to do
            }
        });
        return menu;
    }

    private static void populate(JPopupMenu menu, final Path directory,
                                 final Consumer<Path> onOpenFile,
                                 final Consumer<Path> onOpenFolder) {
        menu.removeAll();
        List<FolderStackModel.StackItem> items = entries(directory);
        if (items.isEmpty()) {
            JMenuItem empty = new JMenuItem(EMPTY_LABEL);
            empty.setEnabled(false);
            menu.add(empty);
        }
        for (final FolderStackModel.StackItem item : items) {
            JMenuItem entry = new JMenuItem(ellipsise(item.getName()),
                    item.getIcon());
            entry.setToolTipText(item.isDirectory()
                    ? item.getTypeLabel()
                    : item.getTypeLabel() + ", " + item.getSize() + " bytes");
            entry.addActionListener(e -> onOpenFile.accept(item.getPath()));
            menu.add(entry);
        }
        menu.addSeparator();
        JMenuItem showFolder = new JMenuItem(SHOW_IN_FILE_MANAGER);
        showFolder.addActionListener(e -> onOpenFolder.accept(directory));
        menu.add(showFolder);
    }

    /** Truncates long names with an ellipsis, as the 3D stack rows do. */
    static String ellipsise(String name) {
        if (name == null) {
            return "";
        }
        if (name.length() <= MAX_LABEL) {
            return name;
        }
        return name.substring(0, MAX_LABEL - 1) + "\u2026";
    }
}
