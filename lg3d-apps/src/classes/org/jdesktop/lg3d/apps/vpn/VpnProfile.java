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
package org.jdesktop.lg3d.apps.vpn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One VPN connection the app can bring up. A profile is either
 * <em>NetworkManager-managed</em> (discovered from {@code nmcli connection show},
 * carrying a {@code uuid} and driven by {@code nmcli connection up/down}) or
 * <em>file-imported</em> (an {@code .ovpn} or WireGuard {@code .conf} the user
 * added, carrying a {@code configPath} and driven by {@code openvpn} /
 * {@code wg-quick}). The {@code backend} field records which tool drives it so the
 * panel builds the right command and never guesses.
 *
 * <p>A profile is a plain Jackson bean persisted by {@link VpnStore}. It stores no
 * secret: an OpenVPN/WireGuard config file holds its own keys and stays where the
 * user put it (only the path is remembered), and NetworkManager keeps its own
 * credentials. Every setter normalises null to empty and trims.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VpnProfile {

    private String name = "";
    private String uuid = "";
    private ConnectionType type = ConnectionType.UNKNOWN;
    private String host = "";
    private String username = "";
    private String configPath = "";
    private String backend = "";
    private boolean autoConnect = false;

    /** No-arg constructor for Jackson. */
    public VpnProfile() {
    }

    /**
     * Convenience constructor for a NetworkManager-managed profile.
     *
     * @param name the connection name
     * @param uuid the NetworkManager UUID
     * @param type the connection type
     */
    public VpnProfile(String name, String uuid, ConnectionType type) {
        setName(name);
        setUuid(uuid);
        setType(type);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = (name == null) ? "" : name.trim();
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = (uuid == null) ? "" : uuid.trim();
    }

    public ConnectionType getType() {
        return type;
    }

    public void setType(ConnectionType type) {
        this.type = (type == null) ? ConnectionType.UNKNOWN : type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = (host == null) ? "" : host.trim();
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = (username == null) ? "" : username.trim();
    }

    public String getConfigPath() {
        return configPath;
    }

    public void setConfigPath(String configPath) {
        this.configPath = (configPath == null) ? "" : configPath.trim();
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = (backend == null) ? "" : backend.trim();
    }

    public boolean isAutoConnect() {
        return autoConnect;
    }

    public void setAutoConnect(boolean autoConnect) {
        this.autoConnect = autoConnect;
    }

    /**
     * True when this profile is driven by NetworkManager (has a UUID and no
     * imported config file), so connect/disconnect go through {@code nmcli}.
     *
     * @return true when nmcli manages this profile
     */
    public boolean isNetworkManagerManaged() {
        return !uuid.isBlank() && configPath.isBlank();
    }

    /**
     * The token used to bring the tunnel up: the NetworkManager UUID when present,
     * otherwise the imported config path. Never null.
     *
     * @return the connect target
     */
    public String connectTarget() {
        if (!uuid.isBlank()) {
            return uuid;
        }
        return configPath;
    }

    @Override
    public String toString() {
        String label = name.isBlank() ? "(unnamed)" : name;
        return label + "  [" + type.describe() + "]";
    }
}
