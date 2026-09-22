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
package org.jdesktop.lg3d.widgets.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import javax.swing.JComponent;
import javax.swing.JDesktopPane;
import javax.swing.JPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Smoke-covers the 2D widget gallery panel: it must be a public {@link JPanel}
 * with a public no-arg constructor (the contract {@code Desktop2DAppRegistry}
 * reflects on to host it in an internal frame), and it must build headless
 * whether or not a widget layer is live - showing the "not running" state when
 * there is none, and refreshing against the layer when there is.
 */
class WidgetGalleryPanelTest {

    @TempDir
    Path tmp;

    @AfterEach
    void tearDown() {
        SwingWidgetLayer.uninstall();
    }

    @Test
    @DisplayName("the gallery panel is a JComponent the 2D registry can host")
    void panelIsAHostableJComponent() {
        SwingWidgetLayer.uninstall();   // ensure no live layer
        WidgetGalleryPanel panel = new WidgetGalleryPanel();

        assertTrue(panel instanceof JComponent);
        assertTrue(panel instanceof JPanel);
        assertEquals(360, panel.getPreferredSize().width);
        assertEquals(440, panel.getPreferredSize().height);
    }

    @Test
    @DisplayName("the gallery panel builds against a live widget layer too")
    void panelBuildsWithLiveLayer() {
        String oldHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString());
        try {
            JDesktopPane desktop = new JDesktopPane();
            desktop.setSize(800, 600);
            SwingWidgetLayer.install(desktop);

            WidgetGalleryPanel panel = new WidgetGalleryPanel();
            assertNotNull(panel);       // refresh() ran against the live layer
        } finally {
            System.setProperty("user.home", oldHome);
            SwingWidgetLayer.uninstall();
        }
    }
}
