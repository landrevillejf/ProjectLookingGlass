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
package org.jdesktop.lg3d.ftpclient.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.file.Path;
import org.jdesktop.lg3d.ftpclient.model.ProfileStore;
import org.jdesktop.lg3d.ftpclient.session.ConnectionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link FtpClientMainPanel}'s headless contract: it builds without
 * opening a window, advertises its native dimensions, tolerates a {@code null}
 * log message, and its {@code dispose()} shuts the session down. Both the
 * explicit-manager and the no-argument constructors are exercised, the latter
 * with {@link ProfileStore#DIR_PROPERTY} pointed at a temp directory so the test
 * never touches the real {@code ~/.lg3d} state.
 */
class FtpClientMainPanelTest {

    @Test
    @DisplayName("the advertised dimensions are 1000x680 native pixels")
    void advertisedSize() {
        assertThat(FtpClientMainPanel.WIDTH_PX).isEqualTo(1000);
        assertThat(FtpClientMainPanel.HEIGHT_PX).isEqualTo(680);
    }

    @Test
    @DisplayName("construction is headless, opens no window and lays out three regions")
    void headlessConstruction(@TempDir Path dir) {
        ConnectionManager manager = new ConnectionManager(new ProfileStore(dir));
        FtpClientMainPanel panel = new FtpClientMainPanel(manager);

        assertThat(panel).isNotNull();
        assertThat(panel.getLayout()).isInstanceOf(BorderLayout.class);
        assertThat(panel.getPreferredSize()).isEqualTo(new Dimension(1000, 680));
        // Toolbar (NORTH), split pane (CENTER) and queue/log (SOUTH).
        assertThat(panel.getComponentCount()).isGreaterThanOrEqualTo(3);
        // Construction must never realize a top-level window.
        assertThat(panel.getTopLevelAncestor()).isNull();
        assertThat(manager.isConnected()).isFalse();
    }

    @Test
    @DisplayName("log tolerates null and ordinary messages without throwing")
    void logIsSafe(@TempDir Path dir) {
        FtpClientMainPanel panel = new FtpClientMainPanel(new ConnectionManager(new ProfileStore(dir)));
        assertThatCode(() -> panel.log(null)).doesNotThrowAnyException();
        assertThatCode(() -> panel.log("hello")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("dispose shuts the session down")
    void dispose(@TempDir Path dir) {
        ConnectionManager manager = new ConnectionManager(new ProfileStore(dir));
        FtpClientMainPanel panel = new FtpClientMainPanel(manager);
        assertThatCode(panel::dispose).doesNotThrowAnyException();
        assertThat(manager.isConnected()).isFalse();
    }

    @Test
    @DisplayName("the no-argument constructor builds against the override directory")
    void noArgConstructor(@TempDir Path dir) {
        String previous = System.getProperty(ProfileStore.DIR_PROPERTY);
        try {
            System.setProperty(ProfileStore.DIR_PROPERTY, dir.toString());
            FtpClientMainPanel panel = new FtpClientMainPanel();
            assertThat(panel).isNotNull();
            assertThat(panel.getPreferredSize()).isEqualTo(new Dimension(1000, 680));
            assertThat(panel.getTopLevelAncestor()).isNull();
            assertThatCode(panel::dispose).doesNotThrowAnyException();
        } finally {
            if (previous == null) {
                System.clearProperty(ProfileStore.DIR_PROPERTY);
            } else {
                System.setProperty(ProfileStore.DIR_PROPERTY, previous);
            }
        }
    }
}
