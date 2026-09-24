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
package org.jdesktop.lg3d.apps.controlcenter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Discovers the control center's category panels. The five built-in panels
 * (Display, Users, System, Appearance, Desktop) are registered on first access;
 * extra panels can be contributed with {@link #register(ControlPanel)} before
 * the control center window is built.
 *
 * <p>All five register in every desktop mode, so the control center shows the
 * same categories on the 3D desktop and on the conventional Swing (2D) desktop.
 * The Appearance and Desktop panels drive whichever desktop is running: on the
 * 3D desktop they post events through lg3d's connector (wallpaper textures,
 * taskbar re-layout), and on the 2D desktop they call into
 * {@link org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D} instead, so the
 * same settings take live effect there. Each panel is still built defensively -
 * one that cannot be constructed in this JVM (a missing Java 3D runtime, say)
 * is skipped rather than taking the whole control center with it.</p>
 */
public final class ControlPanelRegistry {

    private static final Logger logger = Logger.getLogger("lg.apps.controlcenter");

    private static final List<ControlPanel> PANELS = new ArrayList<>();
    private static boolean defaultsAdded;

    private ControlPanelRegistry() {
        // no instances
    }

    /** Registers an additional category panel. */
    public static synchronized void register(ControlPanel panel) {
        if (panel != null && !PANELS.contains(panel)) {
            PANELS.add(panel);
        }
    }

    /** The registered panels, in display order (defaults included). */
    public static synchronized List<ControlPanel> panels() {
        if (!defaultsAdded) {
            defaultsAdded = true;
            addDefault(DisplayPanel::new, "Display");
            addDefault(UsersPanel::new, "Users");
            addDefault(SystemInfoPanel::new, "System");
            addDefault(AppearancePanel::new, "Appearance");
            addDefault(DesktopPanel::new, "Desktop");
        }
        return new ArrayList<>(PANELS);
    }

    /**
     * Adds one built-in panel, skipping it if it cannot be constructed in this
     * JVM (a missing Java 3D runtime, say) rather than losing the whole control
     * center to one category.
     */
    private static void addDefault(Supplier<ControlPanel> factory, String name) {
        try {
            PANELS.add(factory.get());
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Skipping control panel " + name, t);
        }
    }
}
