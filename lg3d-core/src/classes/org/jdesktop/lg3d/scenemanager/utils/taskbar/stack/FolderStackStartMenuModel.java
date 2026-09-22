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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jdesktop.lg3d.scenemanager.utils.startmenu.data.MenuGroup;
import org.jdesktop.lg3d.scenemanager.utils.startmenu.data.MenuItem;
import org.jdesktop.lg3d.scenemanager.utils.startmenu.model.MenuGroupComponent;
import org.jdesktop.lg3d.scenemanager.utils.startmenu.model.MenuItemComponent;
import org.jdesktop.lg3d.scenemanager.utils.startmenu.model.panel.PanelStartMenuModel;

/**
 * The expanded view of a dock folder stack: the very same glassy vertical list
 * the start menu shows for its application groups, populated from a real
 * folder ({@code ~/Documents}, {@code ~/Downloads}) instead of {@code .lgcfg}
 * entries. Raising, hiding, the front-of-window lift, the hover region and the
 * mouse-wheel cycling all come from {@link PanelStartMenuModel}, so a stack
 * list behaves exactly like the application list.
 *
 * <p>Rows are the folder's most-recently-modified entries, newest first (see
 * {@link FolderStackModel}), capped at {@value #MAX_LIST_ENTRIES} so the column
 * stays on screen, plus a trailing <em>Show in File Manager</em> row that opens
 * the whole folder. The content is rescanned on every raise and the rows are
 * rebuilt only when the folder actually changed.</p>
 *
 * <p>{@code StartMenuModel} listens to the menu events of the whole desktop, so
 * every add/change/edit callback is filtered here to this stack's own group:
 * without that the model would adopt the application menu's groups and items
 * (and a group change in the app menu would swap this list's content).</p>
 */
public class FolderStackStartMenuModel extends PanelStartMenuModel {

    /** Most folder entries listed; the rest are left to the file manager. */
    private static final int MAX_LIST_ENTRIES = 12;

    /** Longest row label; longer file names are ellipsised to stay on the row. */
    private static final int MAX_LABEL = 28;

    /** Label of the trailing row that opens the folder itself. */
    private static final String SHOW_IN_FILE_MANAGER = "Show in File Manager";

    /**
     * Margin over the 500 ms hide animation after which the lowered column is
     * switched off completely (see {@link #changeVisible}).
     */
    private static final int HIDE_ANIM_MS = 600;

    /**
     * The stack list currently raised. Opening one (e.g. hovering Downloads)
     * dismisses the other (e.g. Documents) so two lists are never left on
     * screen showing stale content when the pointer moves between dock icons.
     */
    private static FolderStackStartMenuModel current;

    /** The folder backing this list. */
    private final FolderStackModel folderModel;

    /** The single group holding this stack's rows. */
    private final MenuGroup group;

    /** Signature of the content the rows were last built from. */
    private String contentSignature = "";

    /**
     * Detaches the lowered column once the hide animation ends; cancelled by
     * any raise so a re-raise inside the animation window stays visible.
     */
    private javax.swing.Timer hideTimer;

    /** This stack's row column; detached while the list is lowered. */
    private MenuGroupComponent groupComp;

    public FolderStackStartMenuModel(FolderStackModel folderModel) {
        this.folderModel = folderModel;
        // "stack:" prefix keeps the name clear of any application menu group.
        this.group = new MenuGroup("stack:" + folderModel.getDisplayName());
    }

    @Override
    public void initialize() {
        super.initialize();
        // Installs the group column as this model's current (and only) group.
        addMenuGroup(group, true);
        groupComp = getMenuGroupComponent(group);
        // The list starts lowered: drop the column straight away so no stub
        // shows above the taskbar before the first raise.
        detachIfLowered();
    }

    /**
     * Removes the row column from the scene graph while the list is lowered.
     * The application list lowers into its taskbar button, which hides the
     * shrunken column; a dock stack has no button to sink into, so the
     * leftover stub would keep showing above the bar. Detaching the column is
     * the same mechanism {@code StartMenuModel.changeGroup} uses, and unlike
     * {@code setVisible} it leaves the raise/hide pose animations untouched.
     */
    private void detachIfLowered() {
        if (!isVisible() && groupComp != null
                && groupComp.getParent() != null) {
            removeChild(groupComp);
        }
    }

    /**
     * Rescans the folder and rebuilds the rows when its content changed since
     * the last build. Called on every raise so a stack always lists what is
     * really in the folder right now.
     */
    private void refreshContent() {
        folderModel.refresh();
        List<FolderStackModel.StackItem> items = folderModel.getItems();
        int n = Math.min(items.size(), MAX_LIST_ENTRIES);

        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < n; i++) {
            FolderStackModel.StackItem item = items.get(i);
            sig.append(item.getName()).append(':').append(item.getModified())
                    .append(':').append(item.isDirectory()).append(';');
        }
        sig.append('#').append(items.size());
        if (sig.toString().equals(contentSignature)) {
            return;
        }
        contentSignature = sig.toString();

        MenuGroupComponent groupComp = getMenuGroupComponent(group);
        for (MenuItemComponent comp : new ArrayList<>(menuItemComps.values())) {
            groupComp.removeChild(comp);
        }
        // Row components are cached by item and items compare by name, so a
        // stale cache would rebind a renamed or replaced file to an old row.
        menuItemComps.clear();
        group.getItems().clear();

        // The column layout places the first row at the bottom, so the
        // trailing action row is added first and the entries oldest-first,
        // which reads newest-at-top on screen.
        Set<String> labels = new HashSet<>();
        addRow(uniqueLabel(SHOW_IN_FILE_MANAGER, labels),
                folderModel.getDirectory(), true,
                StackIconCache.folderIconUrl(folderModel.getDirectory()),
                labels);
        for (int i = n - 1; i >= 0; i--) {
            FolderStackModel.StackItem item = items.get(i);
            addRow(uniqueLabel(ellipsise(item.getName()), labels),
                    item.getPath(), false, StackIconCache.iconUrlFor(item),
                    labels);
        }
    }

    private void addRow(String name, Path path, boolean folderAction,
                        URL iconUrl, Set<String> labels) {
        FolderStackMenuItem item =
                new FolderStackMenuItem(name, path, folderAction, iconUrl);
        group.addItem(item);
        addMenuItem(item, group);
    }

    /** Cuts a label to {@link #MAX_LABEL} characters with an ellipsis. */
    private static String ellipsise(String name) {
        if (name == null) {
            return "";
        }
        return (name.length() > MAX_LABEL)
                ? name.substring(0, MAX_LABEL - 1) + "\u2026" : name;
    }

    /**
     * Keeps row labels distinct: menu items compare by name, so two rows
     * sharing a (truncated) label would collapse into one cached component.
     */
    private static String uniqueLabel(String label, Set<String> used) {
        String candidate = label;
        int n = 2;
        while (!used.add(candidate)) {
            candidate = label + " (" + n++ + ")";
        }
        return candidate;
    }

    @Override
    public void changeVisible(boolean visible, boolean redoAnim) {
        if (visible) {
            refreshContent();
            FolderStackStartMenuModel other = current;
            if (other != null && other != this) {
                other.changeVisible(false, false);
            }
            current = this;
            if (hideTimer != null) {
                hideTimer.stop();
                hideTimer = null;
            }
            // Re-attach the column the lowered state detached, before the
            // raise animation renders it.
            if (groupComp != null && groupComp.getParent() == null) {
                addChild(groupComp);
            }
        } else if (current == this) {
            current = null;
        }
        boolean wasVisible = isVisible();
        super.changeVisible(visible, redoAnim);
        if (!visible) {
            if (hideTimer != null) {
                hideTimer.stop();
            }
            if (wasVisible) {
                hideTimer = new javax.swing.Timer(HIDE_ANIM_MS,
                        e -> detachIfLowered());
                hideTimer.setRepeats(false);
                hideTimer.start();
            } else {
                detachIfLowered();
            }
        }
    }

    // ------------------------------------------------------------------
    // Event isolation: this model only ever handles its own folder group.

    @Override
    public void addMenuItem(MenuItem item, MenuGroup parentGroup) {
        if (parentGroup == group) {
            super.addMenuItem(item, parentGroup);
        }
    }

    @Override
    public void addMenuGroup(MenuGroup newGroup, boolean isDefaultGroup) {
        if (newGroup == group) {
            super.addMenuGroup(newGroup, isDefaultGroup);
        }
    }

    @Override
    public void addMenuGroup(MenuGroup newGroup) {
        if (newGroup == group) {
            super.addMenuGroup(newGroup);
        }
    }

    @Override
    public void addMenuGroupLink(MenuGroup localGroup, MenuGroup remoteGroup) {
        // A stack list holds a single group and never links to another one.
    }

    @Override
    public void changeGroup(MenuGroup newGroup) {
        // Ditto: group changes of the application menu are not for this list.
    }

    @Override
    public void cycleItems(int clicks) {
        // Wheel events reach every model; only the raised list may cycle.
        if (visible) {
            super.cycleItems(clicks);
        }
    }

    @Override
    public void editMenuItem(MenuItem item) {
        // Stack rows are not editable menu entries.
    }

    @Override
    public void editMenuGroup(MenuGroup group) {
        // Stack rows are not editable menu entries.
    }

    @Override
    public void editMenuGroupLink(MenuGroup localGroup, MenuGroup remoteGroup) {
        // A stack list has no group links.
    }

    /** The folder this list shows. */
    public FolderStackModel getFolderModel() {
        return folderModel;
    }
}
