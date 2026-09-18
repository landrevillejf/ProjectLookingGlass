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
package org.jdesktop.lg3d.apps.controlcenter;

import java.util.ArrayList;
import java.util.List;

/**
 * Discovers the control center's category pages. The four built-in pages
 * (Display, Users, System, Appearance) are registered on first access; extra
 * pages can be contributed with {@link #register(ControlPanel)} before the
 * control center window is built.
 */
public final class ControlPanelRegistry {

    private static final List<ControlPanel> PANELS = new ArrayList<>();
    private static boolean defaultsAdded;

    private ControlPanelRegistry() {
        // no instances
    }

    /** Registers an additional category page. */
    public static synchronized void register(ControlPanel panel) {
        if (panel != null && !PANELS.contains(panel)) {
            PANELS.add(panel);
        }
    }

    /** The registered pages, in display order (defaults included). */
    public static synchronized List<ControlPanel> panels() {
        if (!defaultsAdded) {
            defaultsAdded = true;
            PANELS.add(new DisplayPage3D());
            PANELS.add(new UsersPage3D());
            PANELS.add(new SystemPage3D());
            PANELS.add(new AppearancePage3D());
        }
        return new ArrayList<>(PANELS);
    }
}
