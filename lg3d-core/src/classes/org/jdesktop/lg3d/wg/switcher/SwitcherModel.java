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
package org.jdesktop.lg3d.wg.switcher;

import java.util.List;

/**
 * The desktop-specific half of the application switcher: it knows how to
 * enumerate the currently open windows (in most-recently-used order) and how
 * to bring one of them forward. The desktop-independent machinery
 * ({@link SwitcherController}, {@link SwitcherOverlay}) drives this interface,
 * so the same switcher serves the 2D/Swing desktop, the 3D desktop and the
 * compositor's external-application windows.
 *
 * <p>Implementations are pure view-model code and pull in no Java 3D, so they
 * can be unit-tested headless.</p>
 */
public interface SwitcherModel {

    /**
     * The windows to cycle through, most-recently-used first: element 0 is the
     * window that currently has the focus, element 1 the one Alt+Tab would jump
     * to first. An empty list means there is nothing to switch to.
     */
    List<SwitcherItem> items();

    /**
     * Brings {@code item}'s window forward and gives it the focus. A no-op for
     * a null or foreign item.
     */
    void activate(SwitcherItem item);

    /**
     * The {@link javax.swing.KeyStroke} parser string that opens/advances the
     * switcher (for example {@code "control alt TAB"}). Used by the overlay to
     * bind the trigger without hard-coding a key combo.
     */
    String triggerKeySpec();
}
