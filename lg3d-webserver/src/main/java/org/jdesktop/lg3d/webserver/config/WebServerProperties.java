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
package org.jdesktop.lg3d.webserver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code webserver.*} configuration knobs, bound from
 * {@code application.properties} (or {@code --webserver.xxx=} on the command
 * line). Every field carries a safe default so an unconfigured server still
 * boots.
 *
 * <p>The management token is blank by default, which leaves the API open on
 * loopback for development exactly like {@code wonderland-server}'s login gate;
 * set it to a unique per-deployment secret to require a bearer token on every
 * {@code /api/**} call.</p>
 */
@ConfigurationProperties(prefix = "webserver")
public class WebServerProperties {

    /** Bearer token guarding {@code /api/**}; blank (the default) disables the gate. */
    private String managementToken = "";

    /**
     * Where deployed bootJars and the {@code deployments.json} state live. Blank
     * means {@code ~/.lg3d/webserver} (overridable with the
     * {@code lg3d.webserver.dir} system property, which the tests use).
     */
    private String deployDir = "";

    /** Whether the Docker deployment backend is offered at all. */
    private boolean dockerEnabled = true;

    /** Whether the reverse proxy forwards {@code /<contextPath>/**} to apps. */
    private boolean proxyEnabled = true;

    /** Host name used when the API reports an application's public URL. */
    private String publicHost = "localhost";

    /** The inclusive host-port range auto-assigned to deployed applications. */
    private final PortRange portRange = new PortRange();

    public String getManagementToken() {
        return managementToken;
    }

    public void setManagementToken(String managementToken) {
        this.managementToken = managementToken == null ? "" : managementToken;
    }

    public String getDeployDir() {
        return deployDir;
    }

    public void setDeployDir(String deployDir) {
        this.deployDir = deployDir == null ? "" : deployDir;
    }

    public boolean isDockerEnabled() {
        return dockerEnabled;
    }

    public void setDockerEnabled(boolean dockerEnabled) {
        this.dockerEnabled = dockerEnabled;
    }

    public boolean isProxyEnabled() {
        return proxyEnabled;
    }

    public void setProxyEnabled(boolean proxyEnabled) {
        this.proxyEnabled = proxyEnabled;
    }

    public String getPublicHost() {
        return publicHost;
    }

    public void setPublicHost(String publicHost) {
        this.publicHost = (publicHost == null || publicHost.isBlank()) ? "localhost" : publicHost;
    }

    public PortRange getPortRange() {
        return portRange;
    }

    public void setPortRange(PortRange portRange) {
        if (portRange != null) {
            this.portRange.setStart(portRange.getStart());
            this.portRange.setEnd(portRange.getEnd());
        }
    }

    /** An inclusive {@code [start, end]} host-port range. */
    public static class PortRange {
        private int start = 9001;
        private int end = 9099;

        public int getStart() {
            return start;
        }

        public void setStart(int start) {
            this.start = start;
        }

        public int getEnd() {
            return end;
        }

        public void setEnd(int end) {
            this.end = end;
        }
    }
}
