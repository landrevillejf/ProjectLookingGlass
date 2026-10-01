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

/**
 * The live tunnel state the panel renders: whether a VPN is up, which profile and
 * network device carry it, and a short human detail line. A status is an immutable
 * value produced by {@link VpnBackend#parseStatus} (from {@code nmcli ...
 * connection show --active}) or synthesised by the panel after a connect /
 * disconnect; it never holds a secret.
 *
 * @param connected    true when a VPN tunnel is currently active
 * @param profileName  the active connection's name (empty when disconnected)
 * @param device       the tunnel device (e.g. {@code tun0}), empty when unknown
 * @param detail       a short human-readable explanation, never null
 */
public record VpnStatus(boolean connected, String profileName, String device, String detail) {

    /** Normalises nulls so a status is always safe to render. */
    public VpnStatus {
        profileName = (profileName == null) ? "" : profileName.trim();
        device = (device == null) ? "" : device.trim();
        detail = (detail == null) ? "" : detail.trim();
        if (!connected) {
            profileName = "";
            device = "";
        }
    }

    /** A disconnected status carrying an explanatory detail line. */
    public static VpnStatus disconnected(String detail) {
        return new VpnStatus(false, "", "", detail);
    }

    /** A connected status for the given profile and device. */
    public static VpnStatus connected(String profileName, String device) {
        return new VpnStatus(true, profileName, device,
                "Connected to " + profileName
                        + (device.isBlank() ? "." : " via " + device + "."));
    }

    /** A disconnected status with no detail (the initial state). */
    public static VpnStatus unknown() {
        return new VpnStatus(false, "", "", "Not connected.");
    }

    /**
     * A one-line summary for the status bar.
     *
     * @return the detail line when present, else a generic up/down phrase
     */
    public String summary() {
        if (!detail.isBlank()) {
            return detail;
        }
        return connected ? "Connected." : "Not connected.";
    }
}
