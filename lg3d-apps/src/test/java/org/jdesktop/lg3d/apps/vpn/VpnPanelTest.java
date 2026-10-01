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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
