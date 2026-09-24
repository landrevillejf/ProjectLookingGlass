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
package org.jdesktop.lg3d.apps.update;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless construction test for the Software Update panel.
 *
 * <p>Building {@link UpdateManagerPanel} wires the {@code update-manager}
 * module's {@code UpdateService} and {@code UpdatePresenter} and embeds its
 * settings form. The panel must never throw out of its constructor: if the
 * service cannot be created (a missing runtime dependency, an unreadable user
 * configuration) it degrades to a readable message instead. It also gates the
 * periodic network check ({@code service.initialize()}) behind a real display,
 * so constructing it with {@code java.awt.headless=true} (set by the module's
 * test task) touches neither the network nor a screen and is CI-safe.</p>
 */
class UpdateManagerPanelTest {

    @Test
    @DisplayName("the panel constructs headless without throwing")
    void panelConstructsHeadless() {
        // Exercises the service wiring and the graceful-fallback path with no
        // display and no update server; a regression that lets an exception
        // escape the constructor would surface here rather than only when a user
        // opens Software Update on the desktop.
        UpdateManagerPanel panel =
                assertDoesNotThrow(UpdateManagerPanel::new);
        assertNotNull(panel);
        assertNotNull(panel.getLayout());
    }

    @Test
    @DisplayName("the panel is a Swing panel with the advertised host size")
    void panelReportsHostDimensions() {
        UpdateManagerPanel panel = new UpdateManagerPanel();
        // The 3D wrapper hands these to TitledSwingWindow; the 2D desktop packs
        // the panel into an MDI internal frame from its preferred size.
        assertNotNull(panel, "the panel must be a usable Swing component");
        assertEquals(true, panel instanceof JPanel);
        assertEquals(UpdateManagerPanel.WIDTH_PX,
                panel.getPreferredSize().width);
        assertEquals(UpdateManagerPanel.HEIGHT_PX,
                panel.getPreferredSize().height);
    }
}
