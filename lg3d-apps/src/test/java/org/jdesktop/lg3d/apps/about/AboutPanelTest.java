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
package org.jdesktop.lg3d.apps.about;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless construction test for the About panel.
 *
 * <p>Building {@link AboutPanel} reads the {@link AboutInfo} model and loads the
 * product logo from the classpath. The constructor must never throw: the logo is
 * optional (a missing asset is simply omitted), so constructing it with
 * {@code java.awt.headless=true} (set by the module's test task) is CI-safe and
 * needs no display.</p>
 */
class AboutPanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        AboutPanel panel = assertDoesNotThrow(AboutPanel::new);
        assertNotNull(panel);
        assertNotNull(panel.getLayout());
    }

    @Test
    @DisplayName("the panel is a Swing panel with the advertised host size")
    void panelReportsHostDimensions() {
        AboutPanel panel = new AboutPanel();
        // The 3D wrapper hands these to TitledSwingWindow; the 2D desktop packs
        // the panel into an MDI internal frame from its preferred size.
        assertTrue(panel instanceof JPanel);
        assertEquals(AboutPanel.WIDTH_PX, panel.getPreferredSize().width);
        assertEquals(AboutPanel.HEIGHT_PX, panel.getPreferredSize().height);
    }

    @Test
    @DisplayName("the logo resource path matches the runtime-resources layout")
    void logoPathIsOnTheClasspathPrefix() {
        // LG3D resolves artwork through the top-level resources/ classpath
        // prefix assembled by :lg3d-core:runtimeResources; the panel must point
        // at the same prefix or the logo silently never loads.
        assertTrue(AboutPanel.LOGO_PATH.startsWith("resources/images/icon/"),
                "logo must resolve under the resources/ classpath prefix");
    }
}
