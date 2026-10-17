/*
 * Pins WebServerProperties: safe defaults so an unconfigured server boots, and the
 * null/blank guards that keep the management token and public host well-formed no
 * matter what configuration binding hands them.
 */
package org.jdesktop.lg3d.webserver.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebServerPropertiesTest {

    @Test
    void defaultsAreSafe() {
        WebServerProperties properties = new WebServerProperties();
        assertThat(properties.getManagementToken()).isEmpty();
        assertThat(properties.getDeployDir()).isEmpty();
        assertThat(properties.isDockerEnabled()).isTrue();
        assertThat(properties.isProxyEnabled()).isTrue();
        assertThat(properties.getPublicHost()).isEqualTo("localhost");
        assertThat(properties.getPortRange().getStart()).isEqualTo(9001);
        assertThat(properties.getPortRange().getEnd()).isEqualTo(9099);
    }

    @Test
    void nullTokenBecomesEmpty() {
        WebServerProperties properties = new WebServerProperties();
        properties.setManagementToken(null);
        assertThat(properties.getManagementToken()).isEmpty();
    }

    @Test
    void nullDeployDirBecomesEmpty() {
        WebServerProperties properties = new WebServerProperties();
        properties.setDeployDir(null);
        assertThat(properties.getDeployDir()).isEmpty();
    }

    @Test
    void blankPublicHostFallsBackToLocalhost() {
        WebServerProperties properties = new WebServerProperties();
        properties.setPublicHost("   ");
        assertThat(properties.getPublicHost()).isEqualTo("localhost");
        properties.setPublicHost(null);
        assertThat(properties.getPublicHost()).isEqualTo("localhost");
        properties.setPublicHost("desktop.local");
        assertThat(properties.getPublicHost()).isEqualTo("desktop.local");
    }

    @Test
    void togglesAreSettable() {
        WebServerProperties properties = new WebServerProperties();
        properties.setDockerEnabled(false);
        properties.setProxyEnabled(false);
        assertThat(properties.isDockerEnabled()).isFalse();
        assertThat(properties.isProxyEnabled()).isFalse();
    }

    @Test
    void portRangeIsCopiedAndNullSafe() {
        WebServerProperties properties = new WebServerProperties();
        WebServerProperties.PortRange range = new WebServerProperties.PortRange();
        range.setStart(9100);
        range.setEnd(9199);
        properties.setPortRange(range);
        assertThat(properties.getPortRange().getStart()).isEqualTo(9100);
        assertThat(properties.getPortRange().getEnd()).isEqualTo(9199);

        properties.setPortRange(null);
        assertThat(properties.getPortRange().getStart()).isEqualTo(9100);
    }
}
