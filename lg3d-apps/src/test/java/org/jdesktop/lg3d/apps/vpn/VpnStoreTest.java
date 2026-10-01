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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link VpnStore}'s defensive JSON round-trip for the settings and the
 * imported-profile list, plus {@link VpnSettings}'s normalisation. A missing,
 * corrupt or null file must always yield defaults / an empty list, never throw, so
 * a damaged config can never stop the app from opening.
 */
class VpnStoreTest {

    @Test
    @DisplayName("settings survive a save / load round-trip")
    void settingsRoundTrip(@TempDir Path dir) {
        VpnStore store = new VpnStore(dir);
        VpnSettings s = new VpnSettings();
        s.setPreferredBackend("openvpn");
        s.setAutoConnect(true);
        s.setLastProfile("Office");
        s.setRefreshOnOpen(false);
        store.saveSettings(s);

        VpnSettings back = new VpnStore(dir).loadSettings();
        assertEquals("openvpn", back.getPreferredBackend());
        assertTrue(back.isAutoConnect());
        assertEquals("Office", back.getLastProfile());
        assertFalse(back.isRefreshOnOpen());
    }

    @Test
    @DisplayName("imported profiles survive a round-trip with all fields")
    void profilesRoundTrip(@TempDir Path dir) {
        VpnStore store = new VpnStore(dir);
        VpnProfile nm = new VpnProfile("Office", "u1", ConnectionType.GENERIC);
        nm.setBackend("nmcli");
        nm.setAutoConnect(true);
        VpnProfile imported = new VpnProfile("home", "", ConnectionType.OPENVPN);
        imported.setConfigPath("/home/u/home.ovpn");
        imported.setBackend("openvpn");
        imported.setHost("gw.example.com");
        store.saveProfiles(List.of(nm, imported));

        List<VpnProfile> back = new VpnStore(dir).loadProfiles();
        assertEquals(2, back.size());
        assertEquals("Office", back.get(0).getName());
        assertEquals(ConnectionType.GENERIC, back.get(0).getType());
        assertTrue(back.get(0).isAutoConnect());
        assertEquals("home", back.get(1).getName());
        assertEquals(ConnectionType.OPENVPN, back.get(1).getType());
        assertEquals("/home/u/home.ovpn", back.get(1).getConfigPath());
        assertEquals("gw.example.com", back.get(1).getHost());
    }

    @Test
    @DisplayName("missing files yield defaults / empty, never throw")
    void missingIsSafe(@TempDir Path dir) {
        VpnStore store = new VpnStore(dir);
        VpnSettings s = store.loadSettings();
        assertEquals("", s.getPreferredBackend());
        assertFalse(s.isAutoConnect());
        assertEquals("", s.getLastProfile());
        assertTrue(s.isRefreshOnOpen());
        assertTrue(store.loadProfiles().isEmpty());
    }

    @Test
    @DisplayName("corrupt files yield defaults / empty, never throw")
    void corruptIsSafe(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(VpnStore.SETTINGS_FILE), "{bad]");
        Files.writeString(dir.resolve(VpnStore.PROFILES_FILE), "not-json");
        VpnStore store = new VpnStore(dir);
        assertEquals("", store.loadSettings().getPreferredBackend());
        assertTrue(store.loadProfiles().isEmpty());
    }

    @Test
    @DisplayName("saving null persists defaults without error")
    void saveNullIsSafe(@TempDir Path dir) {
        VpnStore store = new VpnStore(dir);
        store.saveSettings(null);
        store.saveProfiles(null);
        assertTrue(store.loadSettings().isRefreshOnOpen());
        assertTrue(store.loadProfiles().isEmpty());
    }

    @Test
    @DisplayName("the config dir is explicit or honours the DIR_PROPERTY override")
    void configDirAndOverride() {
        assertEquals(Path.of("/tmp/vpn-x"), new VpnStore(Path.of("/tmp/vpn-x")).getConfigDir());
        String previous = System.getProperty(VpnStore.DIR_PROPERTY);
        try {
            System.setProperty(VpnStore.DIR_PROPERTY, "/tmp/vpn-override");
            assertEquals(Path.of("/tmp/vpn-override"), VpnStore.defaultConfigDir());
            assertEquals(Path.of("/tmp/vpn-override"), new VpnStore().getConfigDir());
        } finally {
            if (previous == null) {
                System.clearProperty(VpnStore.DIR_PROPERTY);
            } else {
                System.setProperty(VpnStore.DIR_PROPERTY, previous);
            }
        }
    }

    @Test
    @DisplayName("settings normalise null / blank and trim")
    void settingsNormalization() {
        VpnSettings s = new VpnSettings();
        s.setPreferredBackend(null);
        assertEquals("", s.getPreferredBackend());
        s.setPreferredBackend("  wg-quick  ");
        assertEquals("wg-quick", s.getPreferredBackend());
        s.setLastProfile(null);
        assertEquals("", s.getLastProfile());
    }
}
