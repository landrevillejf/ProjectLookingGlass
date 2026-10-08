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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jdesktop.lg3d.utils.system.NetworkCut;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link VpnPanel}'s profile / status handling, driven entirely through its
 * package-private hooks so no tool is run, no process spawned and no EDT pumped.
 * Swing widgets construct headless, and the constructor starts no thread or timer
 * (discovery and connect only happen from a user action), so this runs in CI.
 *
 * <p>{@code statusText()} reads a volatile field set synchronously in
 * {@code setStatus}, so the assertions are deterministic off the EDT.</p>
 */
class VpnPanelTest {

    /** The network cut is a desktop-wide static; start every test from open. */
    @BeforeEach
    void clearCutBefore() {
        NetworkCut.restore();
    }

    /** Never leak a cut into another test. */
    @AfterEach
    void clearCutAfter() {
        NetworkCut.restore();
    }

    @Test
    @DisplayName("a fresh panel is disconnected, empty and shows Ready")
    void defaultsAreReady(@TempDir Path dir) {
        VpnPanel panel = new VpnPanel(new VpnStore(dir));
        assertEquals("Ready", panel.statusText());
        assertFalse(panel.isConnected());
        assertFalse(panel.isConnecting());
        assertEquals(0, panel.profileCount());
        assertEquals("Not connected.", panel.status().detail());
        assertNotNull(panel.settings());
    }

    @Test
    @DisplayName("persisted profiles are loaded on construction")
    void loadsPersistedProfiles(@TempDir Path dir) {
        VpnStore store = new VpnStore(dir);
        store.saveProfiles(List.of(new VpnProfile("Office", "u1", ConnectionType.GENERIC)));
        VpnPanel panel = new VpnPanel(new VpnStore(dir));
        assertEquals(1, panel.profileCount());
        assertEquals("Office", panel.profiles().get(0).getName());
    }

    @Test
    @DisplayName("applyStatus renders a status and ignores null")
    void applyStatus(@TempDir Path dir) {
        VpnPanel panel = new VpnPanel(new VpnStore(dir));
        panel.applyStatus(VpnStatus.connected("Office", "tun0"));
        assertTrue(panel.isConnected());
        assertEquals("Office", panel.status().profileName());
        assertEquals("Connected to Office via tun0.", panel.statusText());

        panel.applyStatus(null);
        assertTrue(panel.isConnected(), "a null status is ignored");
        assertEquals("Office", panel.status().profileName());

        panel.applyStatus(VpnStatus.disconnected("Dropped."));
        assertFalse(panel.isConnected());
        assertEquals("Dropped.", panel.statusText());
    }

    @Test
    @DisplayName("addProfile appends and persists, and ignores null")
    void addProfile(@TempDir Path dir) {
        VpnPanel panel = new VpnPanel(new VpnStore(dir));
        panel.addProfile(new VpnProfile("Office", "u1", ConnectionType.GENERIC));
        assertEquals(1, panel.profileCount());
        panel.addProfile(null);
        assertEquals(1, panel.profileCount());
        assertEquals(1, new VpnStore(dir).loadProfiles().size(), "the add is persisted");
    }

    @Test
    @DisplayName("a null discovery refreshes quietly; a list reports its count")
    void applyDiscoveredProfilesCounts(@TempDir Path dir) {
        VpnPanel panel = new VpnPanel(new VpnStore(dir));
        panel.applyDiscoveredProfiles(null);
        assertEquals("Refreshed.", panel.statusText());
        assertEquals(0, panel.profileCount());

        panel.applyDiscoveredProfiles(List.of(
                new VpnProfile("A", "ua", ConnectionType.GENERIC),
                new VpnProfile("B", "ub", ConnectionType.GENERIC)));
        assertEquals("Found 2 VPN connection(s).", panel.statusText());
        assertEquals(2, panel.profileCount());
    }

    @Test
    @DisplayName("discovery keeps imported file profiles alongside nmcli ones")
    void applyDiscoveredProfilesMergesImported(@TempDir Path dir) {
        VpnPanel panel = new VpnPanel(new VpnStore(dir));
        VpnProfile imported = new VpnProfile();
        imported.setName("home");
        imported.setConfigPath("/home/u/home.ovpn");
        imported.setType(ConnectionType.OPENVPN);
        panel.addProfile(imported);

        panel.applyDiscoveredProfiles(
                List.of(new VpnProfile("Office", "u1", ConnectionType.GENERIC)));
        assertEquals(2, panel.profileCount(), "one discovered + one imported");
        assertTrue(panel.profiles().stream().anyMatch(p -> p.getName().equals("home")),
                "the imported profile survives discovery");
        assertEquals("Found 1 VPN connection(s).", panel.statusText());
    }

    @Test
    @DisplayName("discovery carries the auto-connect flag over by name")
    void applyDiscoveredProfilesCarriesAutoConnect(@TempDir Path dir) {
        VpnPanel panel = new VpnPanel(new VpnStore(dir));
        VpnProfile office = new VpnProfile("Office", "u1", ConnectionType.GENERIC);
        office.setAutoConnect(true);
        panel.addProfile(office);

        panel.applyDiscoveredProfiles(
                List.of(new VpnProfile("Office", "u1", ConnectionType.GENERIC)));
        assertEquals(1, panel.profileCount());
        assertTrue(panel.profiles().get(0).isAutoConnect(),
                "the freshly-discovered profile keeps the prior auto-connect choice");
    }

    @Test
    @DisplayName("profileForConfig derives name, type, backend and gateway")
    void profileForConfig(@TempDir Path dir) throws IOException {
        VpnPanel panel = new VpnPanel(new VpnStore(dir));

        Path ovpn = dir.resolve("office.ovpn");
        Files.writeString(ovpn, "client\ndev tun\nremote vpn.example.com 1194\n");
        VpnProfile p = panel.profileForConfig(ovpn);
        assertEquals("office", p.getName());
        assertEquals(ConnectionType.OPENVPN, p.getType());
        assertEquals("openvpn", p.getBackend());
        assertEquals("vpn.example.com", p.getHost());
        assertEquals(ovpn.toAbsolutePath().toString(), p.getConfigPath());

        Path conf = dir.resolve("wg0.conf");
        Files.writeString(conf, "[Interface]\nPrivateKey = abc\n");
        VpnProfile w = panel.profileForConfig(conf);
        assertEquals("wg0", w.getName());
        assertEquals(ConnectionType.WIREGUARD, w.getType());
        assertEquals("wg-quick", w.getBackend());
    }

    @Test
    @DisplayName("an armed kill switch cuts the network on an unexpected drop, restored on reconnect")
    void killSwitchCutsOnUnexpectedDrop(@TempDir Path dir) {
        VpnPanel panel = new VpnPanel(new VpnStore(dir));
        VpnProfile office = new VpnProfile("Office", "u1", ConnectionType.GENERIC);
        office.setKillSwitch(true);
        panel.addProfile(office);

        panel.applyStatus(VpnStatus.connected("Office", "tun0"));
        assertFalse(panel.isVpnCutActive());
        assertFalse(NetworkCut.isCut());

        panel.applyPolledStatus(VpnStatus.disconnected("Dropped."));
        assertTrue(panel.isVpnCutActive(), "an unexpected drop arms the cut");
        assertTrue(NetworkCut.isCut(), "the desktop network is cut");
        assertFalse(panel.isConnected());

        panel.applyPolledStatus(VpnStatus.connected("Office", "tun0"));
        assertFalse(panel.isVpnCutActive(), "the tunnel coming back lifts the cut");
        assertFalse(NetworkCut.isCut(), "the desktop network is restored");
    }

    @Test
    @DisplayName("a drop with no kill switch and no auto-connect never cuts or reconnects")
    void plainDropIsPassive(@TempDir Path dir) {
        VpnPanel panel = new VpnPanel(new VpnStore(dir));
        panel.addProfile(new VpnProfile("Office", "u1", ConnectionType.GENERIC));
        panel.applyStatus(VpnStatus.connected("Office", "tun0"));

        panel.applyPolledStatus(VpnStatus.disconnected("Dropped."));
        assertFalse(panel.isVpnCutActive());
        assertFalse(NetworkCut.isCut());
        assertFalse(panel.isReconnectScheduled());
    }

    @Test
    @DisplayName("an auto-connect profile schedules a reconnect on drop, cancelled when it returns")
    void autoConnectSchedulesReconnect(@TempDir Path dir) {
        VpnPanel panel = new VpnPanel(new VpnStore(dir));
        VpnProfile office = new VpnProfile("Office", "u1", ConnectionType.GENERIC);
        office.setAutoConnect(true);
        panel.addProfile(office);
        panel.applyStatus(VpnStatus.connected("Office", "tun0"));
        assertEquals(0, panel.reconnectAttempts());

        panel.applyPolledStatus(VpnStatus.disconnected("Dropped."));
        assertTrue(panel.isReconnectScheduled(),
                "a reconnect is scheduled; the 2s backoff cannot fire during the test");
        assertEquals(0, panel.reconnectAttempts());

        panel.applyPolledStatus(VpnStatus.connected("Office", "tun0"));
        assertFalse(panel.isReconnectScheduled(), "coming back up cancels the pending reconnect");
        assertEquals(0, panel.reconnectAttempts());
    }

    @Test
    @DisplayName("verify tunnel needs a connection; a verdict renders and is remembered")
    void verifyTunnelRendersVerdict(@TempDir Path dir) {
        VpnPanel panel = new VpnPanel(new VpnStore(dir));
        panel.verifyTunnel();
        assertEquals("Connect a tunnel first, then verify it.", panel.statusText());
        assertNull(panel.lastVerdict());

        panel.setBaselinePublicIp("1.2.3.4");
        panel.applyVerdict(TunnelVerdict.classify("1.2.3.4", "5.6.7.8"), "5.6.7.8");
        assertEquals(TunnelVerdict.TUNNELED, panel.lastVerdict());
        assertTrue(panel.statusText().contains("verified"));

        panel.applyVerdict(TunnelVerdict.LEAK, "1.2.3.4");
        assertEquals(TunnelVerdict.LEAK, panel.lastVerdict());
        assertTrue(panel.statusText().toLowerCase().contains("leak"));
    }

    @Test
    @DisplayName("saveProfile creates and edits a profile and persists the kill switch")
    void saveProfileCreatesAndEdits(@TempDir Path dir) {
        VpnPanel panel = new VpnPanel(new VpnStore(dir));
        VpnProfile created = panel.saveProfile(null, "  Work  ", ConnectionType.OPENVPN,
                "gw.example.com", true, true);
        assertEquals("Work", created.getName());
        assertEquals(ConnectionType.OPENVPN, created.getType());
        assertEquals("gw.example.com", created.getHost());
        assertTrue(created.isAutoConnect());
        assertTrue(created.isKillSwitch());
        assertEquals(1, panel.profileCount());
        assertTrue(panel.statusText().startsWith("Created"));

        VpnProfile back = new VpnStore(dir).loadProfiles().get(0);
        assertTrue(back.isKillSwitch(), "the kill switch is persisted");
        assertTrue(back.isAutoConnect());

        panel.saveProfile(created, "Work2", ConnectionType.WIREGUARD, "h2", false, false);
        assertEquals(1, panel.profileCount(), "editing does not add a profile");
        assertEquals("Work2", panel.profiles().get(0).getName());
        assertEquals(ConnectionType.WIREGUARD, panel.profiles().get(0).getType());
        assertFalse(panel.profiles().get(0).isKillSwitch());
        assertFalse(new VpnStore(dir).loadProfiles().get(0).isKillSwitch());
        assertTrue(panel.statusText().startsWith("Updated"));

        panel.saveProfile(created, "Keep", null, "", false, false);
        assertEquals(ConnectionType.WIREGUARD, panel.profiles().get(0).getType(),
                "a null type keeps the current one");
    }
}
