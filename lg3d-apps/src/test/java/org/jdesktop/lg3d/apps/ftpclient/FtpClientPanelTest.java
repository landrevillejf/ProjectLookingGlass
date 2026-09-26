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
package org.jdesktop.lg3d.apps.ftpclient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import javax.swing.JPanel;
import org.jdesktop.lg3d.ftpclient.model.ProfileStore;
import org.jdesktop.lg3d.ftpclient.ui.FtpClientMainPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless construction test for the FTP Client panel.
 *
 * <p>Building {@link FtpClientPanel} embeds the {@code ftp-client} module's
 * {@link FtpClientMainPanel}, which in turn builds its {@code ConnectionManager}
 * and reads any persisted profiles/settings. The panel must never throw out of
 * its constructor: if the client cannot be created it degrades to a readable
 * message instead. The config directory is pointed at a temp folder so the test
 * neither reads nor writes the developer's real {@code ~/.lg3d/ftpclient}, and
 * {@code java.awt.headless=true} (set by the module's test task) keeps it
 * CI-safe with no display and no server connection.</p>
 */
class FtpClientPanelTest {

    private String previousDir;

    @BeforeEach
    void isolateConfigDir(@TempDir Path dir) {
        previousDir = System.getProperty(ProfileStore.DIR_PROPERTY);
        System.setProperty(ProfileStore.DIR_PROPERTY, dir.resolve("ftpclient").toString());
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
        FtpClientPanel panel = assertDoesNotThrow(FtpClientPanel::new);
        assertNotNull(panel);
        assertNotNull(panel.getLayout());
        // ftp-client is on this module's compile classpath, so the real client
        // must be embedded rather than the unavailable-fallback pane.
        assertNotNull(panel.getMainPanel(), "the ftp-client client should be embedded");
        assertDoesNotThrow(panel::dispose);
    }

    @Test
    @DisplayName("the panel is a Swing panel with the advertised host size")
    void panelReportsHostDimensions() {
        FtpClientPanel panel = new FtpClientPanel();
        assertTrue(panel instanceof JPanel);
        // The wrapper hands these to TitledSwingWindow; the 2D desktop packs the
        // panel into an MDI internal frame from its preferred size.
        assertEquals(FtpClientMainPanel.WIDTH_PX, panel.getPreferredSize().width);
        assertEquals(FtpClientMainPanel.HEIGHT_PX, panel.getPreferredSize().height);
        assertEquals(FtpClientMainPanel.WIDTH_PX, FtpClientPanel.WIDTH_PX);
        assertEquals(FtpClientMainPanel.HEIGHT_PX, FtpClientPanel.HEIGHT_PX);
    }
}
