/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved
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

import static org.junit.jupiter.api.Assertions.assertNull;

import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link Desktop2D#openHostedPanel(String, javax.swing.Icon,
 * javax.swing.JComponent)} - the reusable runtime entry point an application
 * uses to open a secondary window <em>inside</em> the 2D desktop (e.g. the
 * Remote Viewer hosting its live-viewing panel so it is captured by the desktop
 * screenshot rather than escaping as a stray top-level frame).
 *
 * <p>Its contract is to return null when no 2D desktop is running, so a caller
 * can fall back to another host (the 3D {@code Frame3D} or a standalone
 * {@code JFrame}). No {@code Desktop2D} is ever constructed in a headless test
 * (its shell needs a display), so the singleton is null and the method must
 * return null without touching the toolkit - which is exactly what makes it
 * headless- and unit-test-safe.</p>
 */
class Desktop2DHostedPanelTest {

    @Test
    @DisplayName("openHostedPanel returns null when no 2D desktop is running")
    void returnsNullWithoutDesktop() {
        assertNull(Desktop2D.openHostedPanel("Remote Viewer", null, new JPanel()),
                "with no 2D desktop instance the host must decline (null)");
    }
}
