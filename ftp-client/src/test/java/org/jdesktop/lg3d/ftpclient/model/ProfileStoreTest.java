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
package org.jdesktop.lg3d.ftpclient.model;

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
 * Covers the JSON {@link ProfileStore}: profile/settings round-trips, password
 * obfuscation only for opted-in profiles, forgiving loads (missing or corrupt
 * files yield defaults rather than throwing), the write-failure path and the
 * configuration-directory override property.
 */
class ProfileStoreTest {

    @Test
    @DisplayName("missing files load as empty profiles and default settings")
    void missingFilesLoadDefaults(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(dir.resolve("absent"));
        assertThat(store.loadProfiles()).isEmpty();
        AppSettings s = store.loadSettings();
        assertThat(s.getConnectTimeoutSeconds()).isEqualTo(15);
        assertThat(s.isAllowSavePasswords()).isFalse();
    }

    @Test
    @DisplayName("profiles round-trip; the opted-in password is restored, others dropped")
    void profilesRoundTrip(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(dir);

        SiteProfile saved = new SiteProfile("saved", Protocol.SFTP, "sftp.example.com");
        saved.setUser("alice");
        saved.setPassword("keepme");
        saved.setSavePassword(true);

        SiteProfile dropped = new SiteProfile("dropped", Protocol.FTP, "ftp.example.com");
        dropped.setPassword("ephemeral");
        dropped.setSavePassword(false);

        store.saveProfiles(List.of(saved, dropped));

        String json = readJson(dir, ProfileStore.PROFILES_FILE);
        assertThat(json).doesNotContain("keepme").doesNotContain("ephemeral");
        assertThat(json).contains("obf1:");

        List<SiteProfile> loaded = store.loadProfiles();
        assertThat(loaded).hasSize(2);
        SiteProfile lsaved = loaded.stream()
                .filter(p -> p.getId().equals(saved.getId())).findFirst().orElseThrow();
        SiteProfile ldropped = loaded.stream()
                .filter(p -> p.getId().equals(dropped.getId())).findFirst().orElseThrow();
        assertThat(lsaved.getPassword()).isEqualTo("keepme");
        assertThat(lsaved.getProtocol()).isEqualTo(Protocol.SFTP);
        assertThat(lsaved.getUser()).isEqualTo("alice");
        assertThat(ldropped.getPassword()).isNull();
    }

    @Test
    @DisplayName("settings round-trip, and saveSettings(null) writes defaults")
    void settingsRoundTrip(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(dir);
        AppSettings s = new AppSettings();
        s.setRetryCount(7);
        s.setBufferSize(16 * 1024);
        s.setShowHiddenFiles(true);
        store.saveSettings(s);

        AppSettings loaded = store.loadSettings();
        assertThat(loaded.getRetryCount()).isEqualTo(7);
        assertThat(loaded.getBufferSize()).isEqualTo(16 * 1024);
        assertThat(loaded.isShowHiddenFiles()).isTrue();

        store.saveSettings(null);
        assertThat(store.loadSettings().getRetryCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("a corrupt profiles/settings file degrades to defaults")
    void corruptFilesDegradeToDefaults(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(ProfileStore.PROFILES_FILE), "{not json", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(ProfileStore.SETTINGS_FILE), "[broken", StandardCharsets.UTF_8);

        ProfileStore store = new ProfileStore(dir);
        assertThat(store.loadProfiles()).isEmpty();
        assertThat(store.loadSettings().getConnectTimeoutSeconds()).isEqualTo(15);
    }

    @Test
    @DisplayName("a write failure surfaces as StoreException")
    void writeFailureSurfaces(@TempDir Path dir) throws IOException {
        Path notADir = dir.resolve("blocked");
        Files.writeString(notADir, "i am a file, not a directory", StandardCharsets.UTF_8);
        ProfileStore store = new ProfileStore(notADir);
        assertThatThrownBy(() -> store.saveSettings(new AppSettings()))
                .isInstanceOf(ProfileStore.StoreException.class);
    }

    @Test
    @DisplayName("getConfigDir and defaultConfigDir honour the override property")
    void configDirResolution() {
        Path dir = Path.of("/tmp/ftpclient-xyz");
        assertThat(new ProfileStore(dir).getConfigDir()).isEqualTo(dir);

        String previous = System.getProperty(ProfileStore.DIR_PROPERTY);
        try {
            System.setProperty(ProfileStore.DIR_PROPERTY, "/tmp/override-ftpclient");
            assertThat(ProfileStore.defaultConfigDir()).isEqualTo(Path.of("/tmp/override-ftpclient"));
            System.setProperty(ProfileStore.DIR_PROPERTY, "   ");
            assertThat(ProfileStore.defaultConfigDir())
                    .isEqualTo(Path.of(System.getProperty("user.home"), ".lg3d", "ftpclient"));
        } finally {
            if (previous == null) {
                System.clearProperty(ProfileStore.DIR_PROPERTY);
            } else {
                System.setProperty(ProfileStore.DIR_PROPERTY, previous);
            }
        }
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
