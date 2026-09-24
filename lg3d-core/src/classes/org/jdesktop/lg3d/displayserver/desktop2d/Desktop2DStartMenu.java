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

import java.awt.Image;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.GroupSpec;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.MenuModel;

/**
 * Builds the 2D desktop's start menu (a {@link JPopupMenu}) from the same
 * descriptors the 3D start menu reads.
 *
 * <p>The group graph in {@code startmenu.lgcfg} is traversable in both
 * directions - "Main" links to "Demos" and "Demos" links back to "Main" - so
 * sub-menus are built with an ancestor guard and a depth cap; a link back to an
 * ancestor is dropped instead of recursing forever. Applications the 2D desktop
 * cannot run (pure Java 3D apps) stay visible but disabled, with a tooltip
 * saying so, rather than silently disappearing.</p>
 */
public final class Desktop2DStartMenu {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /** Deepest sub-menu nesting; the shipped config needs three levels. */
    static final int MAX_DEPTH = 4;

    /** Menu icon edge, in pixels. */
    static final int ICON_SIZE = 16;

    /** Shown when no descriptor defined any menu group. */
    static final String NO_APPS_LABEL = "(no applications configured)";

    /** What the desktop does with a clicked entry. */
    public interface Launcher {
        /** Runs {@code item} (panel app, Swing app or external command). */
        void launch(ItemSpec item);
    }

    /** Icon cache: the same few PNGs are referenced by many entries. */
    private static final Map<String, Icon> ICON_CACHE =
            Collections.synchronizedMap(new HashMap<String, Icon>());

    private Desktop2DStartMenu() {
        // no instances
    }

    /**
     * Builds the whole start menu for {@code model}.
     *
     * @param model     the groups and items read from the descriptors
     * @param launcher  invoked when an enabled entry is chosen
     */
    public static JPopupMenu build(MenuModel model, Launcher launcher) {
        JPopupMenu menu = new JPopupMenu();
        GroupSpec root = model.getRootGroup();
        if (root == null) {
            JMenuItem none = new JMenuItem(NO_APPS_LABEL);
            none.setEnabled(false);
            menu.add(none);
            return menu;
        }
        Set<String> ancestors = new LinkedHashSet<>();
        ancestors.add(root.getName());
        appendItems(menu, model.getItemsOf(root.getName()), launcher);
        appendGroups(menu, model, root, launcher, ancestors, 1);
        appendItems(menu, model.getOrphanItems(), launcher);
        return menu;
    }

    /** Adds {@code group}'s own items, then a sub-menu per linked group. */
    private static void appendGroups(JComponent container, MenuModel model,
                                     GroupSpec group, Launcher launcher,
                                     Set<String> ancestors, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        List<GroupSpec> children = model.getLinkedGroups(group);
        for (GroupSpec child : children) {
            if (ancestors.contains(child.getName())) {
                // A back-link to an ancestor ("Main" inside "Demos").
                continue;
            }
            JMenu subMenu = new JMenu(child.getName());
            subMenu.setToolTipText(child.getDesc());
            Set<String> nested = new LinkedHashSet<>(ancestors);
            nested.add(child.getName());
            appendItems(subMenu, model.getItemsOf(child.getName()), launcher);
            appendGroups(subMenu, model, child, launcher, nested, depth + 1);
            if (subMenu.getItemCount() == 0) {
                // An empty category is noise; the 3D menu skips it as well.
                continue;
            }
            addTo(container, subMenu);
        }
    }

    private static void appendItems(JComponent container, List<ItemSpec> items,
                                    Launcher launcher) {
        for (ItemSpec item : items) {
            JMenuItem entry = createItem(item, launcher);
            if (entry != null) {
                addTo(container, entry);
            }
        }
    }

    /** A JPopupMenu has no addItem; route both container kinds through one path. */
    private static void addTo(JComponent container, JMenuItem entry) {
        if (container instanceof JMenu) {
            ((JMenu) container).add(entry);
        } else if (container instanceof JPopupMenu) {
            ((JPopupMenu) container).add(entry);
        }
    }

    /**
     * Creates one menu entry, or null when the entry must be omitted entirely
     * (an external command whose executable is not installed).
     */
    static JMenuItem createItem(ItemSpec item, final Launcher launcher) {
        Desktop2DAppRegistry.Kind kind =
                Desktop2DAppRegistry.classify(item.getCommand());
        if (kind == Desktop2DAppRegistry.Kind.EXTERNAL
                && !Desktop2DAppRegistry.isExternalAvailable(item.getCommand())) {
            logger.log(Level.FINE, "Executable not found, skipping 2D menu item {0}",
                    item.getName());
            return null;
        }
        JMenuItem entry = new JMenuItem(item.getName(),
                icon(item.getIconResource()));
        if (kind == Desktop2DAppRegistry.Kind.UNAVAILABLE) {
            entry.setEnabled(false);
            entry.setToolTipText(Desktop2DAppRegistry.UNAVAILABLE_TOOLTIP);
            return entry;
        }
        entry.setToolTipText(item.getDesc() != null ? item.getDesc()
                : item.getCommand());
        entry.addActionListener(e -> launcher.launch(item));
        return entry;
    }

    /** Loads and caches a classpath icon, scaled to the menu icon size. */
    static Icon icon(String resource) {
        if (resource == null || resource.isBlank()) {
            return null;
        }
        Icon cached = ICON_CACHE.get(resource);
        if (cached != null) {
            return cached;
        }
        Icon loaded = loadIcon(resource);
        if (loaded != null) {
            ICON_CACHE.put(resource, loaded);
        }
        return loaded;
    }

    private static Icon loadIcon(String resource) {
        try {
            URL url = Desktop2DStartMenu.class.getClassLoader().getResource(resource);
            if (url == null) {
                return null;
            }
            ImageIcon full = new ImageIcon(url);
            if (full.getIconWidth() <= 0 || full.getIconHeight() <= 0) {
                return null;
            }
            if (full.getIconWidth() <= ICON_SIZE && full.getIconHeight() <= ICON_SIZE) {
                return full;
            }
            return new ImageIcon(full.getImage().getScaledInstance(
                    ICON_SIZE, ICON_SIZE, Image.SCALE_SMOOTH));
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not load menu icon " + resource, e);
            return null;
        }
    }
}
