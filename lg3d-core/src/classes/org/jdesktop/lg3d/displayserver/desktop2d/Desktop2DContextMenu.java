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
import javax.swing.Icon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import com.protonmail.landrevillejf.IconManager;
import com.protonmail.landrevillejf.IconManager.IconCategory;
import org.jdesktop.lg3d.i18n.Messages;

/**
 * Builds the 2D/Swing desktop's background context menu (a {@link JPopupMenu})
 * shown on a right-click of the wallpaper.
 *
 * <p>Like {@link Desktop2DStartMenu} and {@link Desktop2DFolderMenu}, this is a
 * Java 3D-free static builder driven entirely by an {@link Actions} callback, so
 * the menu structure and its wiring are unit-testable without a live desktop.
 * The menu offers the operations common to a conventional desktop, grouped as
 * launchers (terminal, file manager), personalisation (wallpaper, control
 * center), window arrangement (the MDI cascade/tile/minimise/restore), power
 * (lock/suspend/reboot/shut down) and session (refresh, exit).</p>
 */
public final class Desktop2DContextMenu {

    // Menu labels, in the run's language (see org.jdesktop.lg3d.i18n); package-visible so
    // the headless test can assert on them.
    static final String TERMINAL = Messages.tr("lg2d.ctx.openTerminal");
    static final String FILE_MANAGER = Messages.tr("lg2d.ctx.openFileManager");
    static final String CHANGE_WALLPAPER = Messages.tr("lg2d.ctx.changeWallpaper");
    static final String DESKTOP_SETTINGS = Messages.tr("lg2d.ctx.desktopSettings");
    static final String DO_NOT_DISTURB = Messages.tr("lg2d.ctx.dnd");
    static final String CASCADE = Messages.tr("lg2d.ctx.cascade");
    static final String TILE = Messages.tr("lg2d.ctx.tile");
    static final String MINIMIZE_ALL = Messages.tr("lg2d.ctx.minimizeAll");
    static final String RESTORE_ALL = Messages.tr("lg2d.ctx.restoreAll");
    static final String LOCK_SCREEN = Messages.tr("lg2d.ctx.lockScreen");
    static final String SUSPEND = Messages.tr("lg2d.ctx.suspend");
    static final String REBOOT = Messages.tr("lg2d.ctx.reboot");
    static final String SHUT_DOWN = Messages.tr("lg2d.ctx.shutDown");
    static final String REFRESH = Messages.tr("lg2d.ctx.refresh");
    static final String EXIT = Messages.tr("lg2d.ctx.exit");

    /** Placeholder shown when no bundled wallpaper could be resolved. */
    static final String NO_WALLPAPERS = Messages.tr("lg2d.ctx.noWallpapers");

    /** Menu icon edge, in pixels. */
    private static final int ICON_SIZE = 16;

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

        /** True when a session lock tool is installed; hides the entry otherwise. */
        boolean canLockScreen();

        /** True when the machine can be suspended; hides the entry otherwise. */
        boolean canSuspend();

        /** True when the machine can be rebooted/powered off; hides those entries. */
        boolean canPowerOff();

        void lockScreen();

        void suspend();

        void reboot();

        void shutDown();

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
            menu.add(item(TERMINAL, loadIcon(IconCategory.DEVELOPMENT, "Application"), e -> actions.openTerminal()));
        }
        menu.add(item(FILE_MANAGER, loadIcon(IconCategory.NAVIGATION, "Home"), e -> actions.openFileManager()));
        menu.addSeparator();

        // Personalisation.
        menu.add(wallpaperMenu(actions, wallpapers));
        menu.add(item(DESKTOP_SETTINGS, loadIcon(IconCategory.GENERAL, "Preferences"), e -> actions.openDesktopSettings()));
        menu.add(dndItem(actions));
        menu.addSeparator();

        // Window arrangement (meaningless with no windows open).
        boolean hasWindows = actions.windowCount() > 0;
        menu.add(gated(CASCADE, hasWindows, loadIcon(IconCategory.TEXT, "AlignJustify"), e -> actions.cascadeWindows()));
        menu.add(gated(TILE, hasWindows, loadIcon(IconCategory.TEXT, "AlignCenter"), e -> actions.tileWindows()));
        menu.add(gated(MINIMIZE_ALL, hasWindows, loadIcon(IconCategory.GENERAL, "Minimize"), e -> actions.minimizeAllWindows()));
        menu.add(gated(RESTORE_ALL, hasWindows, loadIcon(IconCategory.GENERAL, "Maximize"), e -> actions.restoreAllWindows()));
        menu.addSeparator();

        // Session power / lock (each omitted when the tool is unavailable).
        boolean hasPowerEntries = false;
        if (actions.canLockScreen()) {
            menu.add(item(LOCK_SCREEN, loadIcon(IconCategory.GENERAL, "Lock"), e -> actions.lockScreen()));
            hasPowerEntries = true;
        }
        if (actions.canSuspend()) {
            menu.add(item(SUSPEND, loadIcon(IconCategory.GENERAL, "Sleep"), e -> actions.suspend()));
            hasPowerEntries = true;
        }
        if (actions.canPowerOff()) {
            if (hasPowerEntries) {
                menu.addSeparator();
            }
            menu.add(item(REBOOT, loadIcon(IconCategory.GENERAL, "Redo"), e -> actions.reboot()));
            menu.add(item(SHUT_DOWN, loadIcon(IconCategory.GENERAL, "Stop"), e -> actions.shutDown()));
            hasPowerEntries = true;
        }
        if (hasPowerEntries) {
            menu.addSeparator();
        }

        // Session.
        menu.add(item(REFRESH, loadIcon(IconCategory.GENERAL, "Refresh"), e -> actions.refresh()));
        menu.add(item(EXIT, loadIcon(IconCategory.MEDIA, "Stop"), e -> actions.exit()));

        return menu;
    }

    private static JMenuItem item(String label, ActionListener listener) {
        return item(label, null, listener);
    }

    private static JMenuItem item(String label, Icon icon, ActionListener listener) {
        JMenuItem entry = new JMenuItem(label, icon);
        entry.addActionListener(listener);
        return entry;
    }

    private static JMenuItem gated(String label, boolean enabled, ActionListener listener) {
        JMenuItem entry = item(label, listener);
        entry.setEnabled(enabled);
        return entry;
    }

    private static JMenuItem gated(String label, boolean enabled, Icon icon, ActionListener listener) {
        JMenuItem entry = item(label, icon, listener);
        entry.setEnabled(enabled);
        return entry;
    }

    private static JMenuItem dndItem(Actions actions) {
        JCheckBoxMenuItem entry =
                new JCheckBoxMenuItem(DO_NOT_DISTURB, loadIcon(IconCategory.GENERAL, "Bell"),
                        actions.isDoNotDisturbActive());
        entry.setToolTipText(Messages.tr("lg2d.ctx.dnd.tooltip"));
        entry.addActionListener(e -> actions.toggleDoNotDisturb());
        return entry;
    }

    private static JMenu wallpaperMenu(Actions actions, List<Wallpaper> wallpapers) {
        JMenu menu = new JMenu(CHANGE_WALLPAPER);
        menu.setIcon(loadIcon(IconCategory.GENERAL, "Image"));
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

    /** Loads an IconManager icon, returning null on failure. */
    private static Icon loadIcon(IconCategory category, String name) {
        try {
            return IconManager.loadIcon(category, name, ICON_SIZE, ICON_SIZE);
        } catch (Throwable t) {
            // IconManager missing or unable to render: degrade gracefully
            return null;
        }
    }
}
