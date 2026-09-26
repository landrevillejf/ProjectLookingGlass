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
package org.jdesktop.lg3d.ftpclient.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jdesktop.lg3d.ftpclient.model.AppSettings;
import org.jdesktop.lg3d.ftpclient.model.Protocol;
import org.jdesktop.lg3d.ftpclient.model.SiteProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link RemoteClientFactory}: the right transport adapter is built for
 * each protocol, and the returned client is unconnected and reports its protocol.
 */
class RemoteClientFactoryTest {

    private final RemoteClientFactory factory = new RemoteClientFactory();

    @Test
    @DisplayName("an SFTP site builds the JSch adapter")
    void sftp() {
        SiteProfile p = new SiteProfile("s", Protocol.SFTP, "sftp.example.com");
        RemoteClient c = factory.create(p, new AppSettings());
        assertThat(c).isInstanceOf(SftpRemoteClient.class);
        assertThat(c.getProtocol()).isEqualTo(Protocol.SFTP);
        assertThat(c.isConnected()).isFalse();
    }

    @Test
    @DisplayName("a plaintext FTP site builds the Commons Net adapter")
    void ftp() {
        SiteProfile p = new SiteProfile("f", Protocol.FTP, "ftp.example.com");
        RemoteClient c = factory.create(p, new AppSettings());
        assertThat(c).isInstanceOf(FtpRemoteClient.class);
        assertThat(c.getProtocol()).isEqualTo(Protocol.FTP);
    }

    @Test
    @DisplayName("an FTPS site also builds the Commons Net adapter")
    void ftps() {
        SiteProfile p = new SiteProfile("f", Protocol.FTPS, "ftps.example.com");
        RemoteClient c = factory.create(p, new AppSettings());
        assertThat(c).isInstanceOf(FtpRemoteClient.class);
        assertThat(c.getProtocol()).isEqualTo(Protocol.FTPS);
    }

    @Test
    @DisplayName("null settings fall back to defaults rather than throwing")
    void nullSettings() {
        SiteProfile p = new SiteProfile("s", Protocol.SFTP, "sftp.example.com");
        assertThat(factory.create(p, null)).isInstanceOf(SftpRemoteClient.class);
    }

    @Test
    @DisplayName("a null profile is rejected")
    void nullProfile() {
        assertThatThrownBy(() -> factory.create(null, new AppSettings()))
                .isInstanceOf(NullPointerException.class);
    }
}
