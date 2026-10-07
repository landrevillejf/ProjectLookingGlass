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
package org.jdesktop.lg3d.utils.system;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jdesktop.lg3d.utils.system.NetworkService.Backend;
import org.jdesktop.lg3d.utils.system.NetworkService.NetInterface;
import org.jdesktop.lg3d.utils.system.NetworkService.Operation;
import org.jdesktop.lg3d.utils.system.NetworkService.Probes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link NetworkService}: the §4.4 backend-detection matrix,
 * the operation support/target/privilege predicates, the exact per-backend
 * argument vectors (no shell interpolation) and the pure parsers for
 * {@code nmcli -t}, {@code ip -o link}, {@code networkctl list} and
 * {@code /etc/dhcpcd.conf}. All pure - no process is spawned and no network
 * manager binary is needed (the escaped-colon inputs are built with char
 * concatenation, never a backslash-colon string literal).
 */
class NetworkServiceTest {

    // ------------------------------------------------------------------
    // Backend detection (§4.4 order: NetworkManager → dhcpcd → networkd → ip).

    @Test
    @DisplayName("nmcli alone selects NetworkManager")
    void detectNetworkManagerViaNmcli() {
        assertEquals(Backend.NETWORKMANAGER,
                NetworkService.detect(new Probes(true, false, false, false, false)));
    }

    @Test
    @DisplayName("the NetworkManager daemon binary alone also selects NetworkManager")
    void detectNetworkManagerViaDaemonBinary() {
        assertEquals(Backend.NETWORKMANAGER,
                NetworkService.detect(new Probes(false, true, false, false, false)));
    }

    @Test
    @DisplayName("NetworkManager wins over dhcpcd, networkd and ip")
    void detectPrecedence() {
        assertEquals(Backend.NETWORKMANAGER,
                NetworkService.detect(new Probes(true, false, true, true, true)));
    }

    @Test
    @DisplayName("dhcpcd wins over networkd when NetworkManager is absent")
    void detectDhcpcd() {
        assertEquals(Backend.DHCPCD,
                NetworkService.detect(new Probes(false, false, true, true, true)));
        assertEquals(Backend.DHCPCD,
                NetworkService.detect(new Probes(false, false, true, false, true)));
    }

    @Test
    @DisplayName("systemd-networkd is chosen when only networkctl is present")
    void detectNetworkd() {
        assertEquals(Backend.NETWORKD,
                NetworkService.detect(new Probes(false, false, false, true, true)));
    }

    @Test
    @DisplayName("ip is the read-only fallback when no manager is present")
    void detectIp() {
        assertEquals(Backend.IP,
                NetworkService.detect(new Probes(false, false, false, false, true)));
    }

    @Test
    @DisplayName("nothing usable detects NONE, and null probes are NONE")
    void detectNone() {
        assertEquals(Backend.NONE,
                NetworkService.detect(new Probes(false, false, false, false, false)));
        assertEquals(Backend.NONE, NetworkService.detect((Probes) null));
    }

    @Test
    @DisplayName("live detect() degrades to a Backend without throwing")
    void detectLiveIsSafe() {
        Backend live = assertDoesNotThrow(() -> NetworkService.detect());
        assertNotNull(live);
    }

    // ------------------------------------------------------------------
    // Operation classification predicates.

    @Test
    @DisplayName("only UP/DOWN mutate")
    void isMutating() {
        assertTrue(NetworkService.isMutating(Operation.UP));
        assertTrue(NetworkService.isMutating(Operation.DOWN));
        assertFalse(NetworkService.isMutating(Operation.LIST));
        assertFalse(NetworkService.isMutating(Operation.STATUS));
        assertFalse(NetworkService.isMutating(null));
    }

    @Test
    @DisplayName("NONE supports nothing and the ip fallback is read-only")
    void isSupported() {
        assertFalse(NetworkService.isSupported(Backend.NONE, Operation.LIST));
        assertFalse(NetworkService.isSupported(Backend.IP, Operation.UP));
        assertFalse(NetworkService.isSupported(Backend.IP, Operation.DOWN));
        assertTrue(NetworkService.isSupported(Backend.IP, Operation.LIST));
        assertTrue(NetworkService.isSupported(Backend.IP, Operation.STATUS));
        assertTrue(NetworkService.isSupported(Backend.NETWORKMANAGER, Operation.UP));
        assertTrue(NetworkService.isSupported(Backend.DHCPCD, Operation.DOWN));
        assertFalse(NetworkService.isSupported(null, Operation.LIST));
        assertFalse(NetworkService.isSupported(Backend.NETWORKD, null));
    }

    @Test
    @DisplayName("LIST never needs a target; STATUS does except on nmcli; UP/DOWN always")
    void requiresTarget() {
        assertFalse(NetworkService.requiresTarget(Backend.NETWORKMANAGER, Operation.LIST));
        assertFalse(NetworkService.requiresTarget(Backend.DHCPCD, Operation.LIST));
        assertFalse(NetworkService.requiresTarget(Backend.NETWORKMANAGER, Operation.STATUS),
                "nmcli device status reports every device at once");
        assertTrue(NetworkService.requiresTarget(Backend.DHCPCD, Operation.STATUS));
        assertTrue(NetworkService.requiresTarget(Backend.NETWORKD, Operation.STATUS));
        assertTrue(NetworkService.requiresTarget(Backend.IP, Operation.STATUS));
        assertTrue(NetworkService.requiresTarget(Backend.NETWORKMANAGER, Operation.UP));
        assertTrue(NetworkService.requiresTarget(Backend.NETWORKD, Operation.DOWN));
        assertFalse(NetworkService.requiresTarget(null, Operation.UP));
    }

    @Test
    @DisplayName("only dhcpcd/networkd mutations are escalated here; nmcli self-mediates")
    void needsPrivileges() {
        assertFalse(NetworkService.needsPrivileges(Backend.NETWORKMANAGER, Operation.UP));
        assertFalse(NetworkService.needsPrivileges(Backend.NETWORKMANAGER, Operation.DOWN));
        assertTrue(NetworkService.needsPrivileges(Backend.DHCPCD, Operation.UP));
        assertTrue(NetworkService.needsPrivileges(Backend.DHCPCD, Operation.DOWN));
        assertTrue(NetworkService.needsPrivileges(Backend.NETWORKD, Operation.UP));
        assertTrue(NetworkService.needsPrivileges(Backend.NETWORKD, Operation.DOWN));
        assertFalse(NetworkService.needsPrivileges(Backend.IP, Operation.UP));
        assertFalse(NetworkService.needsPrivileges(Backend.DHCPCD, Operation.LIST));
        assertFalse(NetworkService.needsPrivileges(Backend.NETWORKD, Operation.STATUS));
    }

    // ------------------------------------------------------------------
    // Argument vectors (contract §4.4, verbatim; no shell metacharacters).

    @Test
    @DisplayName("the NetworkManager vectors match §4.4")
    void networkManagerVectors() {
        assertEquals(List.of("nmcli", "-t", "-f", "NAME,TYPE,DEVICE", "connection", "show"),
                NetworkService.buildCommand(Backend.NETWORKMANAGER, Operation.LIST, null));
        assertEquals(List.of("nmcli", "device", "status"),
                NetworkService.buildCommand(Backend.NETWORKMANAGER, Operation.STATUS, null));
        assertEquals(List.of("nmcli", "connection", "up", "id", "HomeWifi"),
                NetworkService.buildCommand(Backend.NETWORKMANAGER, Operation.UP, "HomeWifi"));
        assertEquals(List.of("nmcli", "connection", "down", "id", "HomeWifi"),
                NetworkService.buildCommand(Backend.NETWORKMANAGER, Operation.DOWN, "HomeWifi"));
    }

    @Test
    @DisplayName("nmcli status ignores a target and up/down trim theirs")
    void networkManagerTargetHandling() {
        assertEquals(List.of("nmcli", "device", "status"),
                NetworkService.buildCommand(Backend.NETWORKMANAGER, Operation.STATUS, "eth0"),
                "a target is ignored where the verb needs none");
        assertEquals(List.of("nmcli", "connection", "up", "id", "HomeWifi"),
                NetworkService.buildCommand(Backend.NETWORKMANAGER, Operation.UP, "  HomeWifi  "));
        assertTrue(NetworkService.buildCommand(Backend.NETWORKMANAGER, Operation.UP, null).isEmpty(),
                "up without a connection yields no vector");
        assertTrue(NetworkService.buildCommand(Backend.NETWORKMANAGER, Operation.DOWN, "  ").isEmpty());
    }

    @Test
    @DisplayName("the dhcpcd vectors match §4.4 and LIST is file-based (no vector)")
    void dhcpcdVectors() {
        assertTrue(NetworkService.buildCommand(Backend.DHCPCD, Operation.LIST, null).isEmpty(),
                "the dhcpcd interface list comes from /etc/dhcpcd.conf, not a command");
        assertEquals(List.of("ip", "addr", "show", "eth0"),
                NetworkService.buildCommand(Backend.DHCPCD, Operation.STATUS, "eth0"));
        assertEquals(List.of("dhcpcd", "eth0"),
                NetworkService.buildCommand(Backend.DHCPCD, Operation.UP, "eth0"));
        assertEquals(List.of("dhcpcdctl", "-k", "eth0"),
                NetworkService.buildCommand(Backend.DHCPCD, Operation.DOWN, "eth0"));
    }

    @Test
    @DisplayName("the systemd-networkd vectors match §4.4")
    void networkdVectors() {
        assertEquals(List.of("networkctl", "list"),
                NetworkService.buildCommand(Backend.NETWORKD, Operation.LIST, null));
        assertEquals(List.of("networkctl", "status", "enp0s3"),
                NetworkService.buildCommand(Backend.NETWORKD, Operation.STATUS, "enp0s3"));
        assertEquals(List.of("networkctl", "up", "enp0s3"),
                NetworkService.buildCommand(Backend.NETWORKD, Operation.UP, "enp0s3"));
        assertEquals(List.of("networkctl", "down", "enp0s3"),
                NetworkService.buildCommand(Backend.NETWORKD, Operation.DOWN, "enp0s3"));
    }

    @Test
    @DisplayName("the ip fallback is read-only: list/status only, never up/down")
    void ipVectors() {
        assertEquals(List.of("ip", "-o", "link", "show"),
                NetworkService.buildCommand(Backend.IP, Operation.LIST, null));
        assertEquals(List.of("ip", "addr", "show", "lo"),
                NetworkService.buildCommand(Backend.IP, Operation.STATUS, "lo"));
        assertTrue(NetworkService.buildCommand(Backend.IP, Operation.UP, "lo").isEmpty());
        assertTrue(NetworkService.buildCommand(Backend.IP, Operation.DOWN, "lo").isEmpty());
    }

    @Test
    @DisplayName("NONE and null arguments yield no vector rather than throwing")
    void unsupportedAndNullVectors() {
        assertTrue(NetworkService.buildCommand(Backend.NONE, Operation.LIST, null).isEmpty());
        assertTrue(NetworkService.buildCommand(null, Operation.LIST, null).isEmpty());
        assertTrue(NetworkService.buildCommand(Backend.NETWORKMANAGER, null, null).isEmpty());
        assertTrue(NetworkService.buildCommand(Backend.NETWORKD, Operation.STATUS, null).isEmpty(),
                "a target-requiring verb with no target yields no vector");
    }

    // ------------------------------------------------------------------
    // Pure parsers.

    @Test
    @DisplayName("nmcli -t lines parse into connections; empty device means inactive")
    void parseNmcli() {
        List<NetInterface> r = NetworkService.parseNmcli(
                "HomeWifi:802-11-wireless:wlp2s0\n"
                        + "Wired 1:802-3-ethernet:\n");
        assertEquals(2, r.size());
        assertEquals("HomeWifi", r.get(0).getName());
        assertEquals("802-11-wireless", r.get(0).getType());
        assertEquals("wlp2s0", r.get(0).getState());
        assertTrue(r.get(0).isActive());
        assertEquals("Wired 1", r.get(1).getName());
        assertEquals("inactive", r.get(1).getState());
        assertFalse(r.get(1).isActive());
    }

    @Test
    @DisplayName("an escaped colon inside a connection name is not a field separator")
    void parseNmcliEscapedColon() {
        // "Home\:Wifi:802-11-wireless:wlp2s0" built without a backslash-colon literal.
        String line = "Home" + '\\' + ':' + "Wifi:802-11-wireless:wlp2s0";
        List<NetInterface> r = NetworkService.parseNmcli(line);
        assertEquals(1, r.size());
        assertEquals("Home:Wifi", r.get(0).getName());
        assertEquals("802-11-wireless", r.get(0).getType());
        assertEquals("wlp2s0", r.get(0).getState());

        List<String> fields = NetworkService.splitFields("a:b" + '\\' + ":c:d");
        assertEquals(List.of("a", "b:c", "d"), fields);
    }

    @Test
    @DisplayName("blank, short and name-less nmcli lines are dropped; null is empty")
    void parseNmcliJunk() {
        assertTrue(NetworkService.parseNmcli(null).isEmpty());
        assertTrue(NetworkService.parseNmcli("").isEmpty());
        assertTrue(NetworkService.parseNmcli("onlyonefield\n").isEmpty(),
                "fewer than three fields is malformed");
        assertTrue(NetworkService.parseNmcli(":802-3-ethernet:eth0\n").isEmpty(),
                "an empty NAME is skipped");
    }

    @Test
    @DisplayName("ip -o link records parse; LOWER_UP marks a link active")
    void parseIpLink() {
        List<NetInterface> r = NetworkService.parseIpLink(
                "1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN mode DEFAULT\n"
                        + "2: enp0s3@if0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP mode DEFAULT\n"
                        + "3: wlp2s0: <BROADCAST,MULTICAST> mtu 1500 qdisc noop state DOWN mode DEFAULT\n");
        assertEquals(3, r.size());
        assertEquals("lo", r.get(0).getName());
        assertEquals("UNKNOWN", r.get(0).getState());
        assertTrue(r.get(0).isActive());
        assertEquals("enp0s3", r.get(1).getName(), "the @ifN suffix is stripped");
        assertEquals("UP", r.get(1).getState());
        assertTrue(r.get(1).isActive());
        assertEquals("wlp2s0", r.get(2).getName());
        assertEquals("DOWN", r.get(2).getState());
        assertFalse(r.get(2).isActive());
    }

    @Test
    @DisplayName("ip link records without a state token default from the flags; junk is dropped")
    void parseIpLinkDefaultsAndJunk() {
        List<NetInterface> up = NetworkService.parseIpLink(
                "1: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500\n");
        assertEquals("up", up.get(0).getState());
        assertTrue(up.get(0).isActive());

        List<NetInterface> down = NetworkService.parseIpLink("1: eth1: <BROADCAST,MULTICAST> mtu 1500\n");
        assertEquals("down", down.get(0).getState());
        assertFalse(down.get(0).isActive());

        assertTrue(NetworkService.parseIpLink(null).isEmpty());
        assertTrue(NetworkService.parseIpLink("not a link line\n").isEmpty());
    }

    @Test
    @DisplayName("networkctl list skips its header and footer and reads the columns")
    void parseNetworkctlList() {
        List<NetInterface> r = NetworkService.parseNetworkctlList(
                "IDX LINK             TYPE     OPERATIONAL SETUP\n"
                        + "  1 lo               loopback carrier     unmanaged\n"
                        + "  2 enp0s3           ether    routable    configured\n"
                        + "  3 wlp2s0           wlan     no-carrier  configuring\n"
                        + "\n"
                        + "3 links listed.\n");
        assertEquals(3, r.size(), "the header, blank line and footer are skipped");
        assertEquals("lo", r.get(0).getName());
        assertEquals("loopback", r.get(0).getType());
        assertEquals("carrier unmanaged", r.get(0).getState());
        assertFalse(r.get(0).isActive());
        assertEquals("enp0s3", r.get(1).getName());
        assertEquals("routable configured", r.get(1).getState());
        assertTrue(r.get(1).isActive());
        assertEquals("wlp2s0", r.get(2).getName());
        assertFalse(r.get(2).isActive());
        assertTrue(NetworkService.parseNetworkctlList(null).isEmpty());
    }

    @Test
    @DisplayName("a single-link networkctl footer is also skipped")
    void parseNetworkctlSingularFooter() {
        List<NetInterface> r = NetworkService.parseNetworkctlList(
                "IDX LINK TYPE OPERATIONAL SETUP\n"
                        + "  1 lo loopback carrier unmanaged\n"
                        + "1 link listed.\n");
        assertEquals(1, r.size());
        assertEquals("lo", r.get(0).getName());
    }

    @Test
    @DisplayName("dhcpcd.conf interface/allowinterfaces names are extracted, comments stripped, deduped")
    void parseDhcpcdConf() {
        List<NetInterface> r = NetworkService.parseDhcpcdConf(
                "# a comment\n"
                        + "allowinterfaces eth0 wlan0\n"
                        + "interface eth1\n"
                        + "# interface eth2\n"
                        + "duid\n"
                        + "persistent\n"
                        + "interface eth0\n");
        assertEquals(3, r.size(), "eth2 is commented out and eth0 is not duplicated");
        assertEquals("eth0", r.get(0).getName());
        assertEquals("wlan0", r.get(1).getName());
        assertEquals("eth1", r.get(2).getName());
        assertEquals("dhcpcd", r.get(0).getType());
        assertFalse(r.get(0).isActive());
        assertTrue(NetworkService.parseDhcpcdConf(null).isEmpty());
        assertTrue(NetworkService.parseDhcpcdConf("duid\npersistent\n").isEmpty());
    }

    @Test
    @DisplayName("parseInterfaces dispatches to the right parser per backend")
    void parseInterfacesDispatch() {
        assertEquals(1, NetworkService.parseInterfaces(
                Backend.NETWORKMANAGER, "HomeWifi:802-11-wireless:wlp2s0").size());
        assertEquals(1, NetworkService.parseInterfaces(
                Backend.DHCPCD, "interface eth0").size());
        assertEquals(1, NetworkService.parseInterfaces(
                Backend.NETWORKD, "  2 enp0s3 ether routable configured").size());
        assertEquals(1, NetworkService.parseInterfaces(
                Backend.IP, "1: lo: <LOOPBACK,UP,LOWER_UP> state UNKNOWN").size());
        assertTrue(NetworkService.parseInterfaces(Backend.NONE, "anything").isEmpty());
        assertTrue(NetworkService.parseInterfaces(null, "anything").isEmpty());
    }

    // ------------------------------------------------------------------
    // Value type + the non-spawning I/O guards.

    @Test
    @DisplayName("NetInterface is null-safe and its label carries name/type/state")
    void netInterfaceLabel() {
        NetInterface n = new NetInterface("eth0", "ether", "up", true);
        assertEquals("eth0", n.getName());
        assertEquals("ether", n.getType());
        assertEquals("up", n.getState());
        assertTrue(n.isActive());
        assertEquals("eth0  (ether, up)", n.toString());

        assertEquals("x  (t, active)", new NetInterface("x", "t", "", true).toString());
        assertEquals("x  (t, inactive)", new NetInterface("x", "t", "", false).toString());

        NetInterface empty = new NetInterface(null, null, null, false);
        assertEquals("", empty.getName());
        assertEquals("", empty.getType());
        assertEquals("", empty.getState());
        assertFalse(empty.isActive());
    }

    @Test
    @DisplayName("listing on NONE/null spawns nothing and returns empty")
    void listInterfacesNoBackend() {
        assertTrue(NetworkService.listInterfaces(Backend.NONE).isEmpty());
        assertTrue(NetworkService.listInterfaces(null).isEmpty());
    }

    @Test
    @DisplayName("readDhcpcdConf never throws and returns non-null text")
    void readDhcpcdConfIsSafe() {
        assertNotNull(assertDoesNotThrow(NetworkService::readDhcpcdConf));
    }
}
