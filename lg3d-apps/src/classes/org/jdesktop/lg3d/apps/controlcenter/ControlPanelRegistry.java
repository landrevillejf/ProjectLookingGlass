/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.controlcenter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Discovers the control center's category panels. The built-in panels
 * (Appearance, Customization, Desktop, Display, Sound, Power, Mouse & Keyboard,
 * Shortcuts, Notifications, Workspaces, Quick Launch, Default Applications,
 * Network, Bluetooth, Printing, Date & Time, Language & Region, Users, System,
 * Services, System Update, Storage, Schedule, Task Scheduler) are registered on first access; extra panels can be
 * contributed with {@link #register(ControlPanel)} before the control center
 * window is built.
 *
 * <p>Categories are registered as {@link PanelDescriptor}s: the control center
 * learns each category's name up front but the (potentially expensive) panel is
 * constructed <em>lazily</em>, only when the user actually opens that category.
 * This matters because building a panel can block on platform I/O - Network,
 * Bluetooth, Printing, Date & Time, Language & Region and Mouse & Keyboard shell
 * out to {@code nmcli} / {@code bluetoothctl} / {@code rfkill} / {@code lpstat} /
 * {@code timedatectl} / {@code localectl} / {@code xset}, Appearance scans the
 * wallpaper directory and decodes thumbnails, System reads {@code /proc} - so
 * constructing all of them eagerly froze the window for seconds before it even
 * appeared. A panel that cannot be constructed in this JVM (a missing Java 3D
 * runtime, say) yields {@code null} from {@link PanelDescriptor#get()} rather
 * than throwing, so one bad category cannot take the whole control center with
 * it.</p>
 *
 * <p>Every panel registers in each desktop mode, so the control center shows the
 * same categories on the 3D desktop and on the conventional Swing (2D) desktop.
 * The Appearance and Desktop panels drive whichever desktop is running: on the
 * 3D desktop they post events through lg3d's connector (wallpaper textures,
 * taskbar re-layout), and on the 2D desktop they call into
 * {@link org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D} instead, so the
 * same settings take live effect there.</p>
 */
public final class ControlPanelRegistry {

    private static final Logger logger = Logger.getLogger("lg.apps.controlcenter");

    private static final List<PanelDescriptor> DESCRIPTORS = new ArrayList<>();
    private static boolean defaultsAdded;

    private ControlPanelRegistry() {
        // no instances
    }

    /**
     * A control-panel category known by name whose panel is constructed lazily,
     * on first {@link #get()}, and memoized thereafter. Listing descriptors is
     * cheap (it constructs nothing), so the control center can populate its
     * navigation immediately and pay each panel's cost only when it is opened.
     */
    public static final class PanelDescriptor {
        private final String displayName;
        private final Supplier<ControlPanel> factory;
        private ControlPanel instance;
        private boolean attempted;

        PanelDescriptor(String displayName, Supplier<ControlPanel> factory) {
            this.displayName = displayName;
            this.factory = factory;
        }

        /** Wraps an already-constructed panel (used by {@link #register}). */
        PanelDescriptor(ControlPanel prebuilt) {
            this.displayName = prebuilt.displayName();
            this.factory = null;
            this.instance = prebuilt;
            this.attempted = true;
        }

        /** The category name, available without constructing the panel. */
        public String displayName() {
            return displayName;
        }

        /**
         * Constructs the panel on first call and memoizes it; returns
         * {@code null} if the panel cannot be built in this JVM (the failure is
         * logged, not thrown, and is not retried).
         */
        public synchronized ControlPanel get() {
            if (!attempted) {
                attempted = true;
                try {
                    instance = factory.get();
                } catch (Throwable t) {
                    instance = null;
                    logger.log(Level.WARNING,
                            "Cannot build control panel " + displayName, t);
                }
            }
            return instance;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /**
     * Registers an additional, already-constructed category panel. Skipped if a
     * category with the same display name is already registered.
     */
    public static synchronized void register(ControlPanel panel) {
        if (panel == null) {
            return;
        }
        for (PanelDescriptor d : DESCRIPTORS) {
            if (d.displayName().equals(panel.displayName())) {
                return;
            }
        }
        DESCRIPTORS.add(new PanelDescriptor(panel));
    }

    /**
     * The registered category descriptors, in display order (defaults included).
     * Listing them constructs nothing, so the control center can show its
     * navigation immediately and build each panel on demand.
     */
    public static synchronized List<PanelDescriptor> descriptors() {
        ensureDefaults();
        return new ArrayList<>(DESCRIPTORS);
    }

    /**
     * The registered panels, in display order, constructing any not yet built.
     * Retained for callers that genuinely want every panel up front; the control
     * center shell itself uses {@link #descriptors()} and builds lazily. Panels
     * that cannot be constructed are omitted.
     */
    public static synchronized List<ControlPanel> panels() {
        ensureDefaults();
        List<ControlPanel> list = new ArrayList<>(DESCRIPTORS.size());
        for (PanelDescriptor d : DESCRIPTORS) {
            ControlPanel p = d.get();
            if (p != null) {
                list.add(p);
            }
        }
        return list;
    }

    private static void ensureDefaults() {
        if (defaultsAdded) {
            return;
        }
        defaultsAdded = true;
        addDefault(AppearancePanel::new, "Appearance");
        addDefault(CustomizationPanel::new, "Customization");
        addDefault(DesktopPanel::new, "Desktop");
        addDefault(DisplayPanel::new, "Display");
        addDefault(SoundPanel::new, "Sound");
        addDefault(PowerPanel::new, "Power");
        addDefault(InputPanel::new, "Mouse & Keyboard");
        addDefault(ShortcutsPanel::new, "Shortcuts");
        addDefault(NotificationsPanel::new, "Notifications");
        addDefault(WorkspacesPanel::new, "Workspaces");
        addDefault(QuickLaunchPanel::new, "Quick Launch");
        addDefault(FileAssociationsPanel::new, "Default Applications");
        addDefault(NetworkPanel::new, "Network");
        addDefault(BluetoothPanel::new, "Bluetooth");
        addDefault(PrintingPanel::new, "Printing");
        addDefault(DateTimePanel::new, "Date & Time");
        addDefault(LocalePanel::new, "Language & Region");
        addDefault(UsersPanel::new, "Users");
        addDefault(SystemInfoPanel::new, "System");
        addDefault(ServicesPanel::new, "Services");
        addDefault(SystemUpdatePanel::new, "System Update");
        addDefault(StoragePanel::new, "Storage");
        addDefault(SchedulePanel::new, "Schedule");
        addDefault(TaskSchedulerPanel::new, "Task Scheduler");
    }

    /**
     * Registers one built-in category by name, deferring its construction to the
     * first {@link PanelDescriptor#get()}.
     */
    private static void addDefault(Supplier<ControlPanel> factory, String name) {
        DESCRIPTORS.add(new PanelDescriptor(name, factory));
    }
}
