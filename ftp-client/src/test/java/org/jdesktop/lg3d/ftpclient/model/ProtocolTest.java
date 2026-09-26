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
package org.jdesktop.lg3d.ftpclient.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Covers the {@link Protocol} enum's metadata and forgiving name resolution. */
class ProtocolTest {

    @Test
    @DisplayName("each protocol reports its display name, default port and security")
    void metadata() {
        assertThat(Protocol.FTP.getDefaultPort()).isEqualTo(21);
        assertThat(Protocol.FTP.isSecure()).isFalse();
        assertThat(Protocol.FTPS.getDefaultPort()).isEqualTo(21);
        assertThat(Protocol.FTPS.isSecure()).isTrue();
        assertThat(Protocol.SFTP.getDefaultPort()).isEqualTo(22);
        assertThat(Protocol.SFTP.isSecure()).isTrue();
        assertThat(Protocol.FTP.getDisplayName()).isEqualTo("FTP");
        assertThat(Protocol.SFTP.toString()).contains("SSH");
    }

    @Test
    @DisplayName("fromName matches name or display label, case-insensitively")
    void fromName() {
        assertThat(Protocol.fromName("ftp")).isEqualTo(Protocol.FTP);
        assertThat(Protocol.fromName("FTPS")).isEqualTo(Protocol.FTPS);
        assertThat(Protocol.fromName(" sftp ")).isEqualTo(Protocol.SFTP);
        assertThat(Protocol.fromName("SFTP (SSH File Transfer)")).isEqualTo(Protocol.SFTP);
        assertThat(Protocol.fromName("nope")).isNull();
        assertThat(Protocol.fromName(null)).isNull();
    }
}
