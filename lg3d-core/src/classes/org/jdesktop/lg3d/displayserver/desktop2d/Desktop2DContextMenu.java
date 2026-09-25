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

import java.awt.event.ActionListener;
import java.net.URL;
import java.util.List;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

/**
 * Builds the 2D/Swing desktop's background context menu (a {@link JPopupMenu})
 * shown on a right-click of the wallpaper.
 *
 * <p>Like {@link Desktop2DStartMenu} and {@link Desktop2DFolderMenu}, this is a
 * Java 3D-free static builder driven entirely by an {@link Actions} callback, so
 * the menu structure and its wiring are unit-testable without a live desktop.
 * The menu offers the operations common to a conventional desktop, grouped as
 * launchers (terminal, file manager), personalisation (wallpaper, control
 * center), window arrangement (the MDI cascade/tile/minimise/restore) and
 * session (refresh, exit).</p>
 */
public final class Desktop2DContextMenu {

    // Menu labels; package-visible so the headless test can assert on them.
    static final String TERMINAL = "Open Terminal";
    static final String FILE_MANAGER = "Open File Manager";
    static final String CHANGE_WALLPAPER = "Change Wallpaper";
    static final String DESKTOP_SETTINGS = "Desktop Settings...";
    static final String DO_NOT_DISTURB = "Do Not Disturb";
    static final String CASCADE = "Cascade Windows";
    static final String TILE = "Tile Windows";
    static final String MINIMIZE_ALL = "Minimize All Windows";
    static final String RESTORE_ALL = "Restore All Windows";
    static final String REFRESH = "Refresh";
    static final String EXIT = "Exit...";

    /** Placeholder shown when no bundled wallpaper could be resolved. */
    static final String NO_WALLPAPERS = "(no wallpapers)";

    /**
     * What the desktop does with a chosen entry. Implemented by
     * {@link Desktop2D}; kept as a callback so this builder touches no Swing
     * desktop state directly and stays testable with a fake.
     */
    public interface Actions {
        /** True when a terminal executable is installed; hides the entry otherwise. */
        boolean isTerminalAvailable();

        void openTerminal();

        void openFileManager();

        void changeWallpaper(URL url);

        void openDesktopSettings();

        /** True when notification toasts are currently suppressed (Do Not Disturb). */
        boolean isDoNotDisturbActive();

        /** Flips Do Not Disturb on or off. */
        void toggleDoNotDisturb();

        void cascadeWindows();

        void tileWindows();

        void minimizeAllWindows();

        void restoreAllWindows();

        void refresh();

        void exit();

        /** Number of application windows on the desktop; gates the arrangement entries. */
        int windowCount();
    }

    /** One selectable desktop backdrop: its display name and image location. */
    public record Wallpaper(String name, URL url) { }

    private Desktop2DContextMenu() {
        // no instances
    }

    /**
     * Builds the desktop background context menu.
     *
     * @param actions    invoked when an entry is chosen
     * @param wallpapers the bundled backdrops offered under "Change Wallpaper"
     */
    public static JPopupMenu build(Actions actions, List<Wallpaper> wallpapers) {
        JPopupMenu menu = new JPopupMenu();

        // Launchers.
        if (actions.isTerminalAvailable()) {
            menu.add(item(TERMINAL, e -> actions.openTerminal()));
        }
        menu.add(item(FILE_MANAGER, e -> actions.openFileManager()));
        menu.addSeparator();

        // Personalisation.
        menu.add(wallpaperMenu(actions, wallpapers));
        menu.add(item(DESKTOP_SETTINGS, e -> actions.openDesktopSettings()));
        menu.add(dndItem(actions));
        menu.addSeparator();

        // Window arrangement (meaningless with no windows open).
        boolean hasWindows = actions.windowCount() > 0;
        menu.add(gated(CASCADE, hasWindows, e -> actions.cascadeWindows()));
        menu.add(gated(TILE, hasWindows, e -> actions.tileWindows()));
        menu.add(gated(MINIMIZE_ALL, hasWindows, e -> actions.minimizeAllWindows()));
        menu.add(gated(RESTORE_ALL, hasWindows, e -> actions.restoreAllWindows()));
        menu.addSeparator();

        // Session.
        menu.add(item(REFRESH, e -> actions.refresh()));
        menu.add(item(EXIT, e -> actions.exit()));

        return menu;
    }

    private static JMenuItem item(String label, ActionListener listener) {
        JMenuItem entry = new JMenuItem(label);
        entry.addActionListener(listener);
        return entry;
    }

    private static JMenuItem gated(String label, boolean enabled, ActionListener listener) {
        JMenuItem entry = item(label, listener);
        entry.setEnabled(enabled);
        return entry;
    }

    private static JMenuItem dndItem(Actions actions) {
        JCheckBoxMenuItem entry =
                new JCheckBoxMenuItem(DO_NOT_DISTURB, actions.isDoNotDisturbActive());
        entry.setToolTipText("Suppress notification pop-ups (errors still show)");
        entry.addActionListener(e -> actions.toggleDoNotDisturb());
        return entry;
    }

    private static JMenu wallpaperMenu(Actions actions, List<Wallpaper> wallpapers) {
        JMenu menu = new JMenu(CHANGE_WALLPAPER);
        if (wallpapers == null || wallpapers.isEmpty()) {
            JMenuItem none = new JMenuItem(NO_WALLPAPERS);
            none.setEnabled(false);
            menu.add(none);
            return menu;
        }
        for (Wallpaper wallpaper : wallpapers) {
            menu.add(item(wallpaper.name(), e -> actions.changeWallpaper(wallpaper.url())));
        }
        return menu;
    }
}
