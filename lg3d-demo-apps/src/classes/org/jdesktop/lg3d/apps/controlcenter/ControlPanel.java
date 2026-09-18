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

import org.jdesktop.lg3d.wg.Component3D;

/**
 * One category page of the control center: a 100% lg3d-native
 * {@link Component3D} subtree (no Swing). Implementations are discovered
 * through {@link ControlPanelRegistry}, so new categories can be added
 * without touching the control center shell.
 *
 * <p>The shell builds each page once with the page-area size and lays the
 * returned component out centered on that area; pages position their children
 * relative to their own origin.</p>
 */
public interface ControlPanel {

    /** The category name shown in the navigation column. */
    String displayName();

    /**
     * Builds the page's 3D component for a page area of {@code width} x
     * {@code height} (called once; the component is reused across shows).
     */
    Component3D component(float width, float height);

    /** Called when the page becomes the visible category (start timers). */
    default void onShow() {
    }

    /** Called when the page is hidden in favour of another category. */
    default void onHide() {
    }
}
