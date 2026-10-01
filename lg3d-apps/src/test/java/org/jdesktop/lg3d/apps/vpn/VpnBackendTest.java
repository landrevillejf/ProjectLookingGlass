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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link VpnBackend}: the AWT-free, side-effect-free seam that resolves
 * which tunnel tool to drive, builds the precise {@code nmcli} / {@code openvpn} /
 * {@code wg-quick} command lines, and parses their terse output. No process is
 * started here, so the whole command / parser table is asserted headless against
 * recorded {@code nmcli} output.
 */
class VpnBackendTest {

    /** A probe that reports exactly the given executables present. */
    private static Predicate<String> only(String... present) {
        return exe -> {
            for (String p : present) {
                if (p.equals(exe)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static VpnProfile nmProfile(String name, String uuid) {
        return new VpnProfile(name, uuid, ConnectionType.GENERIC);
    }

    private static VpnProfile fileProfile(String name, String configPath, ConnectionType type) {
        VpnProfile p = new VpnProfile();
        p.setName(name);
        p.setConfigPath(configPath);
        p.setType(type);
        return p;
    }

    // ------------------------------------------------------------------
    // Backend resolution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("firstAvailableBackend honours preference order and null")
    void firstAvailableBackend() {
        assertEquals(Optional.empty(), VpnBackend.firstAvailableBackend(null));
        assertEquals(Optional.empty(), VpnBackend.firstAvailableBackend(only()));
        assertEquals(Optional.of("nmcli"),
                VpnBackend.firstAvailableBackend(only("nmcli", "openvpn")));
        assertEquals(Optional.of("openvpn"),
                VpnBackend.firstAvailableBackend(only("openvpn", "wg-quick")),
                "openvpn precedes wg-quick in KNOWN_BACKENDS");
        assertEquals(Optional.of("wg-quick"),
                VpnBackend.firstAvailableBackend(only("wg-quick")));
    }

    @Test
    @DisplayName("resolveBackend prefers an available choice, else auto-detects")
    void resolveBackend() {
        assertEquals(Optional.of("openvpn"),
                VpnBackend.resolveBackend("openvpn", only("nmcli", "openvpn")));
        assertEquals(Optional.of("nmcli"),
                VpnBackend.resolveBackend("openvpn", only("nmcli")),
                "an unavailable preference falls back to the first available");
        assertEquals(Optional.of("nmcli"),
                VpnBackend.resolveBackend("  nmcli  ", only("nmcli")),
                "the preference is trimmed");
        assertEquals(Optional.of("nmcli"),
                VpnBackend.resolveBackend(null, only("nmcli")));
        assertEquals(Optional.of("nmcli"),
                VpnBackend.resolveBackend("   ", only("nmcli")));
        assertEquals(Optional.empty(), VpnBackend.resolveBackend("nmcli", null));
    }

    // ------------------------------------------------------------------
    // Command builders
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the discovery commands are the exact terse nmcli invocations")
    void discoveryCommands() {
        assertEquals(List.of("nmcli", "-t", "-f", "NAME,UUID,TYPE", "connection", "show"),
                VpnBackend.listConnectionsCommand());
        assertEquals(List.of("nmcli", "-t", "-f", "NAME,TYPE,DEVICE",
                        "connection", "show", "--active"),
                VpnBackend.activeConnectionsCommand());
    }

    @Test
    @DisplayName("nmcli connect targets the uuid when present, else the name")
    void connectCommandNmcli() {
        assertEquals(List.of("nmcli", "connection", "up", "uuid", "u1"),
                VpnBackend.connectCommand("nmcli", nmProfile("Office", "u1")));
        assertEquals(List.of("nmcli", "connection", "up", "Office"),
                VpnBackend.connectCommand("nmcli", nmProfile("Office", "")));
        assertTrue(VpnBackend.connectCommand("nmcli", nmProfile("", "")).isEmpty(),
                "no uuid and no name is not connectable");
    }

    @Test
    @DisplayName("openvpn / wg-quick connect from the imported config path")
    void connectCommandFileBackends() {
        assertEquals(List.of("openvpn", "--config", "/home/u/x.ovpn", "--daemon"),
                VpnBackend.connectCommand("openvpn",
                        fileProfile("x", "/home/u/x.ovpn", ConnectionType.OPENVPN)));
        assertTrue(VpnBackend.connectCommand("openvpn",
                fileProfile("x", "", ConnectionType.OPENVPN)).isEmpty());
        assertEquals(List.of("wg-quick", "up", "wg0"),
                VpnBackend.connectCommand("wg-quick",
                        fileProfile("wg0", "/etc/wireguard/wg0.conf", ConnectionType.WIREGUARD)));
    }

    @Test
    @DisplayName("connect guards null / unknown backends")
    void connectCommandGuards() {
        assertTrue(VpnBackend.connectCommand(null, nmProfile("a", "u")).isEmpty());
        assertTrue(VpnBackend.connectCommand("nmcli", null).isEmpty());
        assertTrue(VpnBackend.connectCommand("ssh", nmProfile("a", "u")).isEmpty());
    }

    @Test
    @DisplayName("nmcli / wg-quick tear down; openvpn teardown is empty (kill process)")
    void disconnectCommand() {
        assertEquals(List.of("nmcli", "connection", "down", "uuid", "u1"),
                VpnBackend.disconnectCommand("nmcli", nmProfile("Office", "u1")));
        assertEquals(List.of("nmcli", "connection", "down", "Office"),
                VpnBackend.disconnectCommand("nmcli", nmProfile("Office", "")));
        assertEquals(List.of("wg-quick", "down", "wg0"),
                VpnBackend.disconnectCommand("wg-quick",
                        fileProfile("wg0", "/etc/wireguard/wg0.conf", ConnectionType.WIREGUARD)));
        assertTrue(VpnBackend.disconnectCommand("openvpn",
                fileProfile("x", "/home/u/x.ovpn", ConnectionType.OPENVPN)).isEmpty());
        assertTrue(VpnBackend.disconnectCommand("ssh", nmProfile("a", "u")).isEmpty());
        assertTrue(VpnBackend.disconnectCommand(null, nmProfile("a", "u")).isEmpty());
        assertTrue(VpnBackend.disconnectCommand("nmcli", null).isEmpty());
    }

    @Test
    @DisplayName("interfaceName strips the config path, else falls back to the name")
    void interfaceName() {
        assertEquals("", VpnBackend.interfaceName(null));
        assertEquals("wg0", VpnBackend.interfaceName(
                fileProfile("wg0", "/etc/wireguard/wg0.conf", ConnectionType.WIREGUARD)));
        assertEquals("x", VpnBackend.interfaceName(
                fileProfile("x", "C:\\vpn\\x.ovpn", ConnectionType.OPENVPN)));
        assertEquals("Office", VpnBackend.interfaceName(nmProfile("Office", "")));
    }

    // ------------------------------------------------------------------
    // Parsers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("parseConnections keeps only VPN rows and tags them nmcli")
    void parseConnections() {
        List<String> terse = List.of(
                "Office VPN:11111111-2222-3333-4444-555555555555:vpn",
                "Wired connection 1:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee:802-3-ethernet",
                "Home Wi-Fi:ffffffff-0000-1111-2222-333333333333:802-11-wireless",
                "",
                "short:row");
        List<VpnProfile> profiles = VpnBackend.parseConnections(terse);
        assertEquals(1, profiles.size());
        VpnProfile p = profiles.get(0);
        assertEquals("Office VPN", p.getName());
        assertEquals("11111111-2222-3333-4444-555555555555", p.getUuid());
        assertEquals(ConnectionType.GENERIC, p.getType());
        assertEquals("nmcli", p.getBackend());
        assertTrue(VpnBackend.parseConnections(null).isEmpty());
    }

    @Test
    @DisplayName("parseConnections unescapes a literal colon in a name")
    void parseConnectionsEscapedColon() {
        List<VpnProfile> profiles = VpnBackend.parseConnections(
                List.of("Work\\:VPN:uuid-1:vpn"));
        assertEquals(1, profiles.size());
        assertEquals("Work:VPN", profiles.get(0).getName());
        assertEquals("uuid-1", profiles.get(0).getUuid());
    }

    @Test
    @DisplayName("parseStatus reports the first active VPN, else disconnected")
    void parseStatus() {
        VpnStatus up = VpnBackend.parseStatus(List.of(
                "Wired connection 1:802-3-ethernet:enp3s0",
                "Office VPN:vpn:tun0"));
        assertTrue(up.connected());
        assertEquals("Office VPN", up.profileName());
        assertEquals("tun0", up.device());

        VpnStatus down = VpnBackend.parseStatus(List.of(
                "Wired connection 1:802-3-ethernet:enp3s0"));
        assertFalse(down.connected());
        assertEquals("No active VPN connection.", down.detail());

        assertFalse(VpnBackend.parseStatus(List.of()).connected());
        assertEquals("Could not read the connection state.",
                VpnBackend.parseStatus(null).detail());
    }

    @Test
    @DisplayName("isVpnType recognises VPN types and rejects LAN / wireless")
    void isVpnType() {
        assertTrue(VpnBackend.isVpnType("vpn"));
        assertTrue(VpnBackend.isVpnType("openvpn"));
        assertTrue(VpnBackend.isVpnType("wireguard"));
        assertFalse(VpnBackend.isVpnType("802-3-ethernet"));
        assertFalse(VpnBackend.isVpnType("802-11-wireless"));
        assertFalse(VpnBackend.isVpnType(null));
    }

    @Test
    @DisplayName("splitTerse honours backslash escapes and null")
    void splitTerse() {
        assertArrayEquals(new String[0], VpnBackend.splitTerse(null));
        assertArrayEquals(new String[] {"a", "b", "c"}, VpnBackend.splitTerse("a:b:c"));
        assertArrayEquals(new String[] {"a:b", "c"}, VpnBackend.splitTerse("a\\:b:c"));
        assertArrayEquals(new String[] {"a\\b"}, VpnBackend.splitTerse("a\\\\b"));
        assertArrayEquals(new String[] {""}, VpnBackend.splitTerse(""));
    }

    @Test
    @DisplayName("parseOvpnRemote finds the first remote host")
    void parseOvpnRemote() {
        assertEquals(Optional.of("vpn.example.com"),
                VpnBackend.parseOvpnRemote("client\nremote vpn.example.com 1194\n"));
        assertEquals(Optional.of("gw.internal"),
                VpnBackend.parseOvpnRemote("# remote skipped\n  REMOTE gw.internal\n"));
        assertEquals(Optional.empty(), VpnBackend.parseOvpnRemote("client\ndev tun\n"));
        assertEquals(Optional.empty(), VpnBackend.parseOvpnRemote("remote\n"));
        assertEquals(Optional.empty(), VpnBackend.parseOvpnRemote(null));
        assertEquals(Optional.empty(), VpnBackend.parseOvpnRemote("   "));
    }

    // ------------------------------------------------------------------
    // Human summaries
    // ------------------------------------------------------------------

    @Test
    @DisplayName("describeResult prefers a recognisable line, else a generic phrase")
    void describeResult() {
        assertEquals("Connected.", VpnBackend.describeResult(List.of(), 0, true));
        assertEquals("Disconnected.", VpnBackend.describeResult(null, 0, false));
        assertEquals("Connection successfully activated (D-Bus active path: /x)",
                VpnBackend.describeResult(
                        List.of("Connection successfully activated (D-Bus active path: /x)"),
                        0, true));
    }

    @Test
    @DisplayName("describeResult reports failure with an admin hint on connect")
    void describeResultFailure() {
        String connect = VpnBackend.describeResult(List.of(), 7, true);
        assertEquals("Could not connect (exit 7)."
                + " Bringing a tunnel up often needs administrator rights.", connect);

        String errorLine = VpnBackend.describeResult(
                List.of("Error: Connection activation failed."), 1, true);
        assertTrue(errorLine.startsWith("Could not connect (Error: Connection activation failed.)"),
                errorLine);
        assertTrue(errorLine.endsWith("administrator rights."), errorLine);

        String disconnect = VpnBackend.describeResult(List.of(), 3, false);
        assertEquals("Could not disconnect (exit 3).", disconnect);
        assertFalse(disconnect.contains("administrator"), "no hint on disconnect");
    }

    @Test
    @DisplayName("describeBackend labels each tool and defaults to Auto-detect")
    void describeBackend() {
        assertEquals("NetworkManager (nmcli)", VpnBackend.describeBackend("nmcli"));
        assertEquals("OpenVPN", VpnBackend.describeBackend("openvpn"));
        assertEquals("WireGuard (wg-quick)", VpnBackend.describeBackend("wg-quick"));
        assertEquals("Auto-detect", VpnBackend.describeBackend(null));
        assertEquals("Auto-detect", VpnBackend.describeBackend("   "));
        assertEquals("custom", VpnBackend.describeBackend("  custom  "));
    }
}
