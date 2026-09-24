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
package org.jdesktop.lg3d.apps.dbmanager;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import javax.swing.JPanel;
import org.jdesktop.lg3d.dbmanager.model.ProfileStore;
import org.jdesktop.lg3d.dbmanager.ui.DbManagerMainPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless construction test for the Database Manager panel.
 *
 * <p>Building {@link DbManagerPanel} embeds the {@code db-manager} module's
 * {@link DbManagerMainPanel}, which in turn builds its {@code ConnectionManager}
 * and reads any persisted profiles/settings. The panel must never throw out of
 * its constructor: if the client cannot be created it degrades to a readable
 * message instead. The config directory is pointed at a temp folder so the test
 * neither reads nor writes the developer's real {@code ~/.lg3d/dbmanager}, and
 * {@code java.awt.headless=true} (set by the module's test task) keeps it
 * CI-safe with no display and no database connection.</p>
 */
class DbManagerPanelTest {

    private String previousDir;

    @BeforeEach
    void isolateConfigDir(@TempDir Path dir) {
        previousDir = System.getProperty(ProfileStore.DIR_PROPERTY);
        System.setProperty(ProfileStore.DIR_PROPERTY, dir.resolve("dbmanager").toString());
    }

    @AfterEach
    void restoreConfigDir() {
        if (previousDir == null) {
            System.clearProperty(ProfileStore.DIR_PROPERTY);
        } else {
            System.setProperty(ProfileStore.DIR_PROPERTY, previousDir);
        }
    }

    @Test
    @DisplayName("the panel constructs headless without throwing and embeds the client")
    void panelConstructsHeadless() {
        DbManagerPanel panel = assertDoesNotThrow(DbManagerPanel::new);
        assertNotNull(panel);
        assertNotNull(panel.getLayout());
        // db-manager is on this module's compile classpath, so the real client
        // must be embedded rather than the unavailable-fallback pane.
        assertNotNull(panel.getMainPanel(), "the db-manager client should be embedded");
        assertDoesNotThrow(panel::dispose);
    }

    @Test
    @DisplayName("the panel is a Swing panel with the advertised host size")
    void panelReportsHostDimensions() {
        DbManagerPanel panel = new DbManagerPanel();
        assertTrue(panel instanceof JPanel);
        // The wrapper hands these to TitledSwingWindow; the 2D desktop packs the
        // panel into an MDI internal frame from its preferred size.
        assertEquals(DbManagerMainPanel.WIDTH_PX, panel.getPreferredSize().width);
        assertEquals(DbManagerMainPanel.HEIGHT_PX, panel.getPreferredSize().height);
        assertEquals(DbManagerMainPanel.WIDTH_PX, DbManagerPanel.WIDTH_PX);
        assertEquals(DbManagerMainPanel.HEIGHT_PX, DbManagerPanel.HEIGHT_PX);
    }
}
