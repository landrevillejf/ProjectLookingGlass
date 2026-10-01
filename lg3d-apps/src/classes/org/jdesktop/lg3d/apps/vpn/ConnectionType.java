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

import java.util.Locale;

/**
 * The kind of VPN tunnel a {@link VpnProfile} describes. The desktop ships no VPN
 * stack of its own, so these names mirror what the delegated tools report:
 * NetworkManager ({@code nmcli}) classifies a connection by its plugin type, and
 * an imported config file implies its own type ({@code .ovpn} → OpenVPN,
 * {@code .conf} under {@code wg-quick} → WireGuard).
 */
public enum ConnectionType {
    /** OpenVPN ({@code openvpn} / the NetworkManager openvpn plugin). */
    OPENVPN,
    /** WireGuard ({@code wg-quick} / the NetworkManager wireguard plugin). */
    WIREGUARD,
    /** PPTP (legacy, via NetworkManager). */
    PPTP,
    /** L2TP/IPsec (via NetworkManager). */
    L2TP,
    /** IKEv2 / strongSwan (via NetworkManager). */
    IKEV2,
    /** Cisco AnyConnect / OpenConnect (via NetworkManager). */
    ANYCONNECT,
    /** A generic {@code vpn} connection whose specific plugin is unknown. */
    GENERIC,
    /** Not recognised as a VPN type. */
    UNKNOWN;

    /**
     * Maps a tool-reported type string (an {@code nmcli} TYPE field, or a file
     * extension) onto a {@link ConnectionType}. Matching is case-insensitive and
     * tolerant of the several spellings NetworkManager and the standalone tools
     * use.
     *
     * @param text the raw type text (may be null)
     * @return the matching type, {@link #GENERIC} for a bare {@code vpn}, or
     *         {@link #UNKNOWN} when unrecognised
     */
    public static ConnectionType fromText(String text) {
        if (text == null || text.isBlank()) {
            return UNKNOWN;
        }
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.contains("openvpn") || t.endsWith(".ovpn")) {
            return OPENVPN;
        }
        if (t.contains("wireguard") || t.contains("wg-quick") || t.endsWith(".conf")) {
            return WIREGUARD;
        }
        if (t.contains("pptp")) {
            return PPTP;
        }
        if (t.contains("l2tp")) {
            return L2TP;
        }
        if (t.contains("ikev2") || t.contains("strongswan") || t.contains("libreswan")) {
            return IKEV2;
        }
        if (t.contains("anyconnect") || t.contains("openconnect") || t.contains("cisco")) {
            return ANYCONNECT;
        }
        if (t.equals("vpn") || t.contains("vpn")) {
            return GENERIC;
        }
        return UNKNOWN;
    }

    /** True when this type represents some kind of VPN (not {@link #UNKNOWN}). */
    public boolean isVpn() {
        return this != UNKNOWN;
    }

    /** A short human label for the profile list. */
    public String describe() {
        return switch (this) {
            case OPENVPN -> "OpenVPN";
            case WIREGUARD -> "WireGuard";
            case PPTP -> "PPTP";
            case L2TP -> "L2TP/IPsec";
            case IKEV2 -> "IKEv2";
            case ANYCONNECT -> "AnyConnect";
            case GENERIC -> "VPN";
            case UNKNOWN -> "Unknown";
        };
    }
}
