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
 * The VPN client's persisted preferences: which tunnel tool to prefer, whether to
 * connect the last profile automatically on open, which profile that was, and
 * whether to re-read the live connection state when the panel opens. A plain
 * Jackson bean, so {@link VpnStore} round-trips it as JSON; every setter
 * normalises its input so a hand-edited file can never produce an invalid
 * {@code nmcli} line.
 *
 * <p>No secret is stored here - NetworkManager keeps its own VPN credentials and
 * an imported config file keeps its own keys where the user left them.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VpnSettings {

    private String preferredBackend = "";
    private boolean autoConnect = false;
    private String lastProfile = "";
    private boolean refreshOnOpen = true;

    /** A user-chosen tunnel tool; blank means auto-detect. */
    public String getPreferredBackend() {
        return preferredBackend;
    }

    public void setPreferredBackend(String preferredBackend) {
        this.preferredBackend = (preferredBackend == null) ? "" : preferredBackend.trim();
    }

    /** True to connect {@link #getLastProfile()} when the panel opens. */
    public boolean isAutoConnect() {
        return autoConnect;
    }

    public void setAutoConnect(boolean autoConnect) {
        this.autoConnect = autoConnect;
    }

    /** The name of the last profile connected; blank means none. */
    public String getLastProfile() {
        return lastProfile;
    }

    public void setLastProfile(String lastProfile) {
        this.lastProfile = (lastProfile == null) ? "" : lastProfile.trim();
    }

    /** True to probe the live connection state when the panel first opens. */
    public boolean isRefreshOnOpen() {
        return refreshOnOpen;
    }

    public void setRefreshOnOpen(boolean refreshOnOpen) {
        this.refreshOnOpen = refreshOnOpen;
    }
}
