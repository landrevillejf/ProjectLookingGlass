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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the VPN value types: {@link ConnectionType} (tolerant type-string
 * mapping), {@link VpnProfile} (a normalising Jackson bean that stores no
 * secret), and {@link VpnStatus} (an immutable status that clears identity when
 * disconnected). All are pure data, so this runs headless.
 */
class VpnModelTest {

    @Test
    @DisplayName("fromText maps every spelling onto the right type")
    void connectionTypeFromText() {
        assertEquals(ConnectionType.UNKNOWN, ConnectionType.fromText(null));
        assertEquals(ConnectionType.UNKNOWN, ConnectionType.fromText("   "));
        assertEquals(ConnectionType.OPENVPN, ConnectionType.fromText("openvpn"));
        assertEquals(ConnectionType.OPENVPN, ConnectionType.fromText("OpenVPN"));
        assertEquals(ConnectionType.OPENVPN, ConnectionType.fromText("client.ovpn"));
        assertEquals(ConnectionType.WIREGUARD, ConnectionType.fromText("wireguard"));
        assertEquals(ConnectionType.WIREGUARD, ConnectionType.fromText("wg-quick"));
        assertEquals(ConnectionType.WIREGUARD, ConnectionType.fromText("wg0.conf"));
        assertEquals(ConnectionType.PPTP, ConnectionType.fromText("pptp"));
        assertEquals(ConnectionType.L2TP, ConnectionType.fromText("l2tp"));
        assertEquals(ConnectionType.IKEV2, ConnectionType.fromText("ikev2"));
        assertEquals(ConnectionType.IKEV2, ConnectionType.fromText("strongswan"));
        assertEquals(ConnectionType.ANYCONNECT, ConnectionType.fromText("openconnect"));
        assertEquals(ConnectionType.ANYCONNECT, ConnectionType.fromText("cisco"));
        assertEquals(ConnectionType.GENERIC, ConnectionType.fromText("vpn"));
        assertEquals(ConnectionType.GENERIC, ConnectionType.fromText("some-vpn"));
        assertEquals(ConnectionType.UNKNOWN, ConnectionType.fromText("802-3-ethernet"));
    }

    @Test
    @DisplayName("isVpn is true for everything but UNKNOWN; describe labels each")
    void connectionTypeIsVpnAndDescribe() {
        assertFalse(ConnectionType.UNKNOWN.isVpn());
        assertTrue(ConnectionType.GENERIC.isVpn());
        assertTrue(ConnectionType.OPENVPN.isVpn());
        assertEquals("OpenVPN", ConnectionType.OPENVPN.describe());
        assertEquals("WireGuard", ConnectionType.WIREGUARD.describe());
        assertEquals("PPTP", ConnectionType.PPTP.describe());
        assertEquals("L2TP/IPsec", ConnectionType.L2TP.describe());
        assertEquals("IKEv2", ConnectionType.IKEV2.describe());
        assertEquals("AnyConnect", ConnectionType.ANYCONNECT.describe());
        assertEquals("VPN", ConnectionType.GENERIC.describe());
        assertEquals("Unknown", ConnectionType.UNKNOWN.describe());
    }

    @Test
    @DisplayName("a profile defaults to UNKNOWN and normalises every setter")
    void profileNormalization() {
        VpnProfile p = new VpnProfile();
        assertEquals(ConnectionType.UNKNOWN, p.getType());
        assertEquals("", p.getName());
        assertFalse(p.isAutoConnect());

        p.setName(null);
        assertEquals("", p.getName());
        p.setName("  Office  ");
        assertEquals("Office", p.getName());
        p.setUuid(null);
        assertEquals("", p.getUuid());
        p.setType(null);
        assertEquals(ConnectionType.UNKNOWN, p.getType());
        p.setHost(null);
        assertEquals("", p.getHost());
        p.setUsername(null);
        assertEquals("", p.getUsername());
        p.setConfigPath(null);
        assertEquals("", p.getConfigPath());
        p.setBackend(null);
        assertEquals("", p.getBackend());
    }

    @Test
    @DisplayName("a profile's kill switch defaults off and toggles")
    void profileKillSwitch() {
        VpnProfile p = new VpnProfile();
        assertFalse(p.isKillSwitch(), "the migration-safe default is off");
        p.setKillSwitch(true);
        assertTrue(p.isKillSwitch());
        p.setKillSwitch(false);
        assertFalse(p.isKillSwitch());
    }

    @Test
    @DisplayName("the convenience constructor carries name / uuid / type")
    void profileConstructor() {
        VpnProfile p = new VpnProfile("Office", "u1", ConnectionType.OPENVPN);
        assertEquals("Office", p.getName());
        assertEquals("u1", p.getUuid());
        assertEquals(ConnectionType.OPENVPN, p.getType());
    }

    @Test
    @DisplayName("isNetworkManagerManaged needs a uuid and no config path")
    void profileIsNetworkManagerManaged() {
        VpnProfile nm = new VpnProfile("Office", "u1", ConnectionType.GENERIC);
        assertTrue(nm.isNetworkManagerManaged());

        VpnProfile noUuid = new VpnProfile("Office", "", ConnectionType.GENERIC);
        assertFalse(noUuid.isNetworkManagerManaged());

        VpnProfile imported = new VpnProfile("Office", "u1", ConnectionType.OPENVPN);
        imported.setConfigPath("/home/u/x.ovpn");
        assertFalse(imported.isNetworkManagerManaged(), "a config path means file-driven");
    }

    @Test
    @DisplayName("connectTarget prefers the uuid, else the config path")
    void profileConnectTarget() {
        assertEquals("u1", new VpnProfile("Office", "u1", ConnectionType.GENERIC).connectTarget());
        VpnProfile imported = new VpnProfile();
        imported.setConfigPath("/home/u/x.ovpn");
        assertEquals("/home/u/x.ovpn", imported.connectTarget());
        assertEquals("", new VpnProfile().connectTarget());
    }

    @Test
    @DisplayName("toString labels a blank name and shows the type")
    void profileToString() {
        assertEquals("Office  [OpenVPN]",
                new VpnProfile("Office", "u1", ConnectionType.OPENVPN).toString());
        assertEquals("(unnamed)  [Unknown]", new VpnProfile().toString());
    }

    @Test
    @DisplayName("a disconnected status clears its identity; nulls normalise")
    void statusNormalization() {
        VpnStatus s = new VpnStatus(false, "  Name  ", " tun0 ", "  detail  ");
        assertFalse(s.connected());
        assertEquals("", s.profileName(), "name cleared when disconnected");
        assertEquals("", s.device(), "device cleared when disconnected");
        assertEquals("detail", s.detail());

        VpnStatus n = new VpnStatus(true, null, null, null);
        assertEquals("", n.profileName());
        assertEquals("", n.device());
        assertEquals("", n.detail());
    }

    @Test
    @DisplayName("the status factories build the documented detail lines")
    void statusFactories() {
        VpnStatus up = VpnStatus.connected("Office", "tun0");
        assertTrue(up.connected());
        assertEquals("Connected to Office via tun0.", up.detail());
        assertEquals("Connected to Office via tun0.", up.summary());

        VpnStatus noDev = VpnStatus.connected("Office", "");
        assertEquals("Connected to Office.", noDev.detail());

        VpnStatus down = VpnStatus.disconnected("No active VPN connection.");
        assertFalse(down.connected());
        assertEquals("No active VPN connection.", down.summary());

        VpnStatus unknown = VpnStatus.unknown();
        assertFalse(unknown.connected());
        assertEquals("Not connected.", unknown.detail());
    }

    @Test
    @DisplayName("summary falls back to a generic phrase when detail is blank")
    void statusSummaryFallback() {
        assertEquals("Connected.", new VpnStatus(true, "x", "y", "").summary());
        assertEquals("Not connected.", new VpnStatus(false, "", "", "").summary());
    }
}
