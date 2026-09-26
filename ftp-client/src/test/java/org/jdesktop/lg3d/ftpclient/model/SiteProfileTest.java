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

/** Covers the {@link SiteProfile} bean: secure defaults, clamping setters, copy and id-based equality. */
class SiteProfileTest {

    @Test
    @DisplayName("a fresh profile is secure (FTPS), passive, and has a generated id")
    void defaults() {
        SiteProfile p = new SiteProfile();
        assertThat(p.getId()).isNotBlank();
        assertThat(p.getProtocol()).isEqualTo(Protocol.FTPS);
        assertThat(p.isSecure()).isTrue();
        assertThat(p.getTransferMode()).isEqualTo(TransferMode.PASSIVE);
        assertThat(p.getEncoding()).isEqualTo(SiteProfile.DEFAULT_ENCODING);
        assertThat(p.isSavePassword()).isFalse();
        assertThat(p.isConnectable()).isFalse(); // no host yet
    }

    @Test
    @DisplayName("resolvePort falls back to the protocol default until an explicit port is set")
    void resolvePort() {
        SiteProfile p = new SiteProfile("s", Protocol.SFTP, "host");
        assertThat(p.resolvePort()).isEqualTo(22);
        p.setPort(2222);
        assertThat(p.resolvePort()).isEqualTo(2222);
        p.setPort(0);
        assertThat(p.resolvePort()).isEqualTo(22);
    }

    @Test
    @DisplayName("setters clamp/normalize invalid input")
    void clampingSetters() {
        SiteProfile p = new SiteProfile();
        p.setPort(999999);
        assertThat(p.getPort()).isEqualTo(0);
        p.setPort(-5);
        assertThat(p.getPort()).isEqualTo(0);
        p.setProtocol(null);
        assertThat(p.getProtocol()).isEqualTo(Protocol.FTPS);
        p.setTransferMode(null);
        assertThat(p.getTransferMode()).isEqualTo(TransferMode.PASSIVE);
        p.setEncoding("  ");
        assertThat(p.getEncoding()).isEqualTo(SiteProfile.DEFAULT_ENCODING);
        p.setName(null);
        assertThat(p.getName()).isEmpty();
        p.setHost("  example.com  ");
        assertThat(p.getHost()).isEqualTo("example.com");
        assertThat(p.isConnectable()).isTrue();
    }

    @Test
    @DisplayName("setId regenerates a UUID when given a blank value")
    void idRegeneration() {
        SiteProfile p = new SiteProfile();
        String original = p.getId();
        p.setId("");
        assertThat(p.getId()).isNotBlank().isNotEqualTo(original);
        p.setId("fixed-id");
        assertThat(p.getId()).isEqualTo("fixed-id");
    }

    @Test
    @DisplayName("copy duplicates every field but keeps id-based equality")
    void copyAndEquals() {
        SiteProfile p = new SiteProfile("site", Protocol.FTP, "ftp.example.com");
        p.setPort(2121);
        p.setUser("bob");
        p.setPassword("pw");
        p.setSavePassword(true);
        p.setRemoteDir("/pub");
        p.setTransferMode(TransferMode.ACTIVE);
        p.setEncoding("ISO-8859-1");

        SiteProfile c = p.copy();
        assertThat(c).isEqualTo(p).hasSameHashCodeAs(p);
        assertThat(c.getId()).isEqualTo(p.getId());
        assertThat(c.getPort()).isEqualTo(2121);
        assertThat(c.getUser()).isEqualTo("bob");
        assertThat(c.getPassword()).isEqualTo("pw");
        assertThat(c.isSavePassword()).isTrue();
        assertThat(c.getRemoteDir()).isEqualTo("/pub");
        assertThat(c.getTransferMode()).isEqualTo(TransferMode.ACTIVE);
        assertThat(c.getEncoding()).isEqualTo("ISO-8859-1");

        // Equality is by id only, so a renamed copy is still equal.
        c.setName("renamed");
        assertThat(c).isEqualTo(p);
        // A different id is not equal.
        SiteProfile other = new SiteProfile("site", Protocol.FTP, "ftp.example.com");
        assertThat(other).isNotEqualTo(p);
    }

    @Test
    @DisplayName("the three-argument constructor keeps the secure default on a null protocol")
    void ctorNullProtocol() {
        SiteProfile p = new SiteProfile("x", null, "h");
        assertThat(p.getProtocol()).isEqualTo(Protocol.FTPS);
        assertThat(p.toString()).contains("x").contains("FTPS");
    }
}
