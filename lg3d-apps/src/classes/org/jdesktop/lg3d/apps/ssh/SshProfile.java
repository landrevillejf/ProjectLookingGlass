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
package org.jdesktop.lg3d.apps.ssh;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * A saved SSH connection profile: everything needed to reach one remote host.
 *
 * <p>Plain Jackson-serializable bean (no-arg constructor + getters/setters)
 * persisted by {@link SshProfileStore} as JSON. Carries no live connection;
 * opening one is the job of {@link SshSession}.</p>
 *
 * <p>The password is never persisted by default. When {@link #isSavePassword()}
 * is true, the store writes an obfuscated (not encrypted) form. Private-key
 * paths are stored but passphrases are not.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SshProfile {

    /** Authentication method. */
    public enum AuthMethod {
        PASSWORD("Password"),
        PUBLIC_KEY("Public Key"),
        KEYBOARD_INTERACTIVE("Keyboard Interactive");

        private final String label;

        AuthMethod(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private String id;
    private String name = "";
    private String host = "";
    private int port = 22;
    private String username = "";
    private AuthMethod authMethod = AuthMethod.PASSWORD;
    private String password;
    private boolean savePassword;
    private String privateKeyPath = "";
    private String passphrase;
    private String terminalType = "xterm-256color";
    private int columns = 80;
    private int rows = 24;
    private int keepAliveSeconds = 30;
    private int connectTimeoutSeconds = 15;
    private String startupCommand = "";
    private boolean agentForwarding;
    private boolean x11Forwarding;
    private boolean compression;
    // Port forwarding: "L localPort:remoteHost:remotePort" entries
    private String localForwards = "";
    private String remoteForwards = "";
    private String dynamicForward = "";

    public SshProfile() {
        this.id = UUID.randomUUID().toString();
    }

    public SshProfile(String name, String host, int port, String username) {
        this();
        this.name = name;
        this.host = host;
        this.port = port;
        this.username = username;
    }

    // ------------------------------------------------------------------
    // Getters / Setters (Jackson bean contract)
    // ------------------------------------------------------------------

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public AuthMethod getAuthMethod() { return authMethod; }
    public void setAuthMethod(AuthMethod authMethod) { this.authMethod = authMethod; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isSavePassword() { return savePassword; }
    public void setSavePassword(boolean savePassword) { this.savePassword = savePassword; }

    public String getPrivateKeyPath() { return privateKeyPath; }
    public void setPrivateKeyPath(String privateKeyPath) { this.privateKeyPath = privateKeyPath; }

    public String getPassphrase() { return passphrase; }
    public void setPassphrase(String passphrase) { this.passphrase = passphrase; }

    public String getTerminalType() { return terminalType; }
    public void setTerminalType(String terminalType) { this.terminalType = terminalType; }

    public int getColumns() { return columns; }
    public void setColumns(int columns) { this.columns = columns; }

    public int getRows() { return rows; }
    public void setRows(int rows) { this.rows = rows; }

    public int getKeepAliveSeconds() { return keepAliveSeconds; }
    public void setKeepAliveSeconds(int keepAliveSeconds) { this.keepAliveSeconds = keepAliveSeconds; }

    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }

    public String getStartupCommand() { return startupCommand; }
    public void setStartupCommand(String startupCommand) { this.startupCommand = startupCommand; }

    public boolean isAgentForwarding() { return agentForwarding; }
    public void setAgentForwarding(boolean agentForwarding) { this.agentForwarding = agentForwarding; }

    public boolean isX11Forwarding() { return x11Forwarding; }
    public void setX11Forwarding(boolean x11Forwarding) { this.x11Forwarding = x11Forwarding; }

    public boolean isCompression() { return compression; }
    public void setCompression(boolean compression) { this.compression = compression; }

    public String getLocalForwards() { return localForwards; }
    public void setLocalForwards(String localForwards) { this.localForwards = localForwards; }

    public String getRemoteForwards() { return remoteForwards; }
    public void setRemoteForwards(String remoteForwards) { this.remoteForwards = remoteForwards; }

    public String getDynamicForward() { return dynamicForward; }
    public void setDynamicForward(String dynamicForward) { this.dynamicForward = dynamicForward; }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** @return a display label for tab titles / profile lists. */
    public String getDisplayLabel() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        String u = (username != null && !username.isBlank()) ? username + "@" : "";
        String p = (port != 22) ? ":" + port : "";
        return u + host + p;
    }

    /** @return a deep copy of this profile. */
    public SshProfile copy() {
        SshProfile c = new SshProfile();
        c.id = this.id;
        c.name = this.name;
        c.host = this.host;
        c.port = this.port;
        c.username = this.username;
        c.authMethod = this.authMethod;
        c.password = this.password;
        c.savePassword = this.savePassword;
        c.privateKeyPath = this.privateKeyPath;
        c.passphrase = this.passphrase;
        c.terminalType = this.terminalType;
        c.columns = this.columns;
        c.rows = this.rows;
        c.keepAliveSeconds = this.keepAliveSeconds;
        c.connectTimeoutSeconds = this.connectTimeoutSeconds;
        c.startupCommand = this.startupCommand;
        c.agentForwarding = this.agentForwarding;
        c.x11Forwarding = this.x11Forwarding;
        c.compression = this.compression;
        c.localForwards = this.localForwards;
        c.remoteForwards = this.remoteForwards;
        c.dynamicForward = this.dynamicForward;
        return c;
    }

    @Override
    public String toString() {
        return getDisplayLabel();
    }
}
