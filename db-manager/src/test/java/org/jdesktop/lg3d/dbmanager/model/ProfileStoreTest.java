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
package org.jdesktop.lg3d.dbmanager.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the JSON {@link ProfileStore}: profile/settings/driver round-trips,
 * password obfuscation only for opted-in profiles, forgiving loads (missing or
 * corrupt files yield defaults rather than throwing) and the write-failure path.
 */
class ProfileStoreTest {

    @Test
    @DisplayName("missing files load as empty profiles, default settings, no drivers")
    void missingFilesLoadDefaults(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(dir.resolve("absent"));
        assertThat(store.loadProfiles()).isEmpty();
        assertThat(store.loadCustomDrivers()).isEmpty();
        AppSettings s = store.loadSettings();
        assertThat(s.getMaxRows()).isEqualTo(1000);
        assertThat(s.getPageSize()).isEqualTo(200);
    }

    @Test
    @DisplayName("profiles round-trip; opted-in password is restored, others dropped")
    void profilesRoundTrip(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(dir);

        ConnectionProfile saved = new ConnectionProfile("saved", "jdbc:h2:mem:a");
        saved.setDriverId("h2");
        saved.setUser("sa");
        saved.setPassword("keepme");
        saved.setSavePassword(true);

        ConnectionProfile dropped = new ConnectionProfile("dropped", "jdbc:h2:mem:b");
        dropped.setPassword("ephemeral");
        dropped.setSavePassword(false);

        store.saveProfiles(List.of(saved, dropped));

        // On disk the saved password must be obfuscated, and the non-saved one gone.
        String json = readJson(dir, ProfileStore.PROFILES_FILE);
        assertThat(json).doesNotContain("keepme").doesNotContain("ephemeral");
        assertThat(json).contains("obf1:");

        List<ConnectionProfile> loaded = store.loadProfiles();
        assertThat(loaded).hasSize(2);
        ConnectionProfile lsaved = loaded.stream()
                .filter(p -> p.getId().equals(saved.getId())).findFirst().orElseThrow();
        ConnectionProfile ldropped = loaded.stream()
                .filter(p -> p.getId().equals(dropped.getId())).findFirst().orElseThrow();
        assertThat(lsaved.getPassword()).isEqualTo("keepme");
        assertThat(lsaved.getName()).isEqualTo("saved");
        assertThat(lsaved.getDriverId()).isEqualTo("h2");
        assertThat(ldropped.getPassword()).isNull();
    }

    @Test
    @DisplayName("settings round-trip")
    void settingsRoundTrip(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(dir);
        AppSettings s = new AppSettings();
        s.setMaxRows(123);
        s.setPageSize(45);
        s.setNullText("NULL!");
        store.saveSettings(s);

        AppSettings loaded = store.loadSettings();
        assertThat(loaded.getMaxRows()).isEqualTo(123);
        assertThat(loaded.getPageSize()).isEqualTo(45);
        assertThat(loaded.getNullText()).isEqualTo("NULL!");
    }

    @Test
    @DisplayName("saveSettings(null) writes defaults that reload cleanly")
    void saveNullSettingsWritesDefaults(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(dir);
        store.saveSettings(null);
        assertThat(store.loadSettings().getMaxRows()).isEqualTo(1000);
    }

    @Test
    @DisplayName("custom drivers round-trip as custom, keeping the jar path")
    void customDriversRoundTrip(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(dir);
        DbDriver d = new DbDriver("oracle", "Oracle", "oracle.jdbc.OracleDriver",
                "jdbc:oracle:thin:@//h:1521/s", false, true, "/tmp/ojdbc.jar");
        store.saveCustomDrivers(List.of(d));

        List<DbDriver> loaded = store.loadCustomDrivers();
        assertThat(loaded).hasSize(1);
        DbDriver l = loaded.get(0);
        assertThat(l.getId()).isEqualTo("oracle");
        assertThat(l.getDriverClass()).isEqualTo("oracle.jdbc.OracleDriver");
        assertThat(l.getJarPath()).isEqualTo("/tmp/ojdbc.jar");
        assertThat(l.isCustom()).isTrue();
    }

    @Test
    @DisplayName("a corrupt profiles/settings/drivers file degrades to defaults")
    void corruptFilesDegradeToDefaults(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(ProfileStore.PROFILES_FILE), "{not json",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(ProfileStore.SETTINGS_FILE), "[broken",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(ProfileStore.DRIVERS_FILE), "]]nope[[",
                StandardCharsets.UTF_8);

        ProfileStore store = new ProfileStore(dir);
        assertThat(store.loadProfiles()).isEmpty();
        assertThat(store.loadCustomDrivers()).isEmpty();
        assertThat(store.loadSettings().getMaxRows()).isEqualTo(1000);
    }

    @Test
    @DisplayName("a write failure surfaces as StoreException")
    void writeFailureSurfaces(@TempDir Path dir) throws IOException {
        // Make the config dir path an existing regular FILE so createDirectories
        // (and thus the write) fails.
        Path notADir = dir.resolve("blocked");
        Files.writeString(notADir, "i am a file, not a directory", StandardCharsets.UTF_8);
        ProfileStore store = new ProfileStore(notADir);
        assertThatThrownBy(() -> store.saveSettings(new AppSettings()))
                .isInstanceOf(ProfileStore.StoreException.class);
    }

    @Test
    @DisplayName("getConfigDir and defaultConfigDir honour the override property")
    void configDirResolution() {
        Path dir = Path.of("/tmp/xyz");
        assertThat(new ProfileStore(dir).getConfigDir()).isEqualTo(dir);

        String previous = System.getProperty(ProfileStore.DIR_PROPERTY);
        try {
            System.setProperty(ProfileStore.DIR_PROPERTY, "/tmp/override-dir");
            assertThat(ProfileStore.defaultConfigDir()).isEqualTo(Path.of("/tmp/override-dir"));
            System.setProperty(ProfileStore.DIR_PROPERTY, "   ");
            assertThat(ProfileStore.defaultConfigDir())
                    .isEqualTo(Path.of(System.getProperty("user.home"), ".lg3d", "dbmanager"));
        } finally {
            if (previous == null) {
                System.clearProperty(ProfileStore.DIR_PROPERTY);
            } else {
                System.setProperty(ProfileStore.DIR_PROPERTY, previous);
            }
        }
        // The no-arg constructor resolves through defaultConfigDir().
        assertThat(new ProfileStore().getConfigDir()).isNotNull();
    }

    private static String readJson(Path dir, String fileName) {
        try {
            return Files.readString(dir.resolve(fileName), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + fileName, e);
        }
    }
}
