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

import javax.swing.Icon;
import javax.swing.JComponent;

/**
 * One category page of the control center. Implementations supply their own
 * Swing component and are discovered through {@link ControlPanelRegistry}, so
 * new categories can be added without touching the control center shell.
 */
public interface ControlPanel {

    /** The category name shown in the navigation list. */
    String displayName();

    /** An icon for the navigation list, or null for none. */
    Icon icon();

    /** The panel's Swing component (created once, reused across shows). */
    JComponent component();

    /** Called when the panel becomes the visible category. */
    default void onShow() {
    }

    /** Called when the panel is hidden in favour of another category. */
    default void onHide() {
    }
}
