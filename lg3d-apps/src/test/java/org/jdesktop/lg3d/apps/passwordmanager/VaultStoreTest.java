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
package org.jdesktop.lg3d.apps.passwordmanager;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link VaultStore}'s defensive JSON round-trip (the sealed envelope and
 * the plaintext settings) and {@link PasswordManagerSettings}'s normalisation. A
 * missing, corrupt or null file must always yield defaults, never throw.
 */
class VaultStoreTest {

    @Test
    @DisplayName("settings survive a save / load round-trip")
    void settingsRoundTrip(@TempDir Path dir) {
        VaultStore store = new VaultStore(dir);
        PasswordManagerSettings s = new PasswordManagerSettings();
        s.setAutoLockMinutes(10);
        s.setGeneratorLength(32);
        s.setGenSymbols(false);
        s.setMaskByDefault(false);
        s.setLastCategory("Work");
        store.saveSettings(s);

        PasswordManagerSettings back = new VaultStore(dir).loadSettings();
        assertEquals(10, back.getAutoLockMinutes());
        assertEquals(32, back.getGeneratorLength());
        assertFalse(back.isGenSymbols());
        assertFalse(back.isMaskByDefault());
        assertEquals("Work", back.getLastCategory());
    }

    @Test
    @DisplayName("a sealed envelope survives a round-trip and still opens")
    void envelopeRoundTrip(@TempDir Path dir) throws GeneralSecurityException {
        VaultStore store = new VaultStore(dir);
        assertFalse(store.hasVault());
        assertFalse(store.loadEnvelope().isPresent());

        byte[] plain = "[]".getBytes(StandardCharsets.UTF_8);
        store.saveEnvelope(VaultEnvelope.seal(plain, "pw".toCharArray(), 1000));

        assertTrue(new VaultStore(dir).hasVault());
        VaultEnvelope back = new VaultStore(dir).loadEnvelope();
        assertTrue(back.isPresent());
        assertArrayEquals(plain, back.open("pw".toCharArray()));
    }

    @Test
    @DisplayName("missing files yield defaults / empty, never throw")
    void missingIsSafe(@TempDir Path dir) {
        VaultStore store = new VaultStore(dir);
        assertEquals(PasswordManagerSettings.DEFAULT_AUTOLOCK_MINUTES,
                store.loadSettings().getAutoLockMinutes());
        assertTrue(store.loadSettings().isMaskByDefault());
        assertFalse(store.loadEnvelope().isPresent());
        assertFalse(store.hasVault());
    }

    @Test
    @DisplayName("corrupt files yield defaults / empty, never throw")
    void corruptIsSafe(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(VaultStore.SETTINGS_FILE), "{bad]");
        Files.writeString(dir.resolve(VaultStore.VAULT_FILE), "not-json");
        VaultStore store = new VaultStore(dir);
        assertEquals(PasswordManagerSettings.DEFAULT_AUTOLOCK_MINUTES,
                store.loadSettings().getAutoLockMinutes());
        assertFalse(store.loadEnvelope().isPresent());
        assertFalse(store.hasVault());
    }

    @Test
    @DisplayName("saving null persists defaults without error")
    void saveNullIsSafe(@TempDir Path dir) {
        VaultStore store = new VaultStore(dir);
        store.saveSettings(null);
        store.saveEnvelope(null);
        assertEquals(PasswordManagerSettings.DEFAULT_LENGTH,
                store.loadSettings().getGeneratorLength());
        assertFalse(store.loadEnvelope().isPresent());
    }

    @Test
    @DisplayName("the config dir is explicit or honours the DIR_PROPERTY override")
    void configDirAndOverride() {
        assertEquals(Path.of("/tmp/pm-x"), new VaultStore(Path.of("/tmp/pm-x")).getConfigDir());
        String previous = System.getProperty(VaultStore.DIR_PROPERTY);
        try {
            System.setProperty(VaultStore.DIR_PROPERTY, "/tmp/pm-override");
            assertEquals(Path.of("/tmp/pm-override"), VaultStore.defaultConfigDir());
            assertEquals(Path.of("/tmp/pm-override"), new VaultStore().getConfigDir());
        } finally {
            if (previous == null) {
                System.clearProperty(VaultStore.DIR_PROPERTY);
            } else {
                System.setProperty(VaultStore.DIR_PROPERTY, previous);
            }
        }
    }

    @Test
    @DisplayName("settings normalise / clamp every input")
    void settingsNormalization() {
        PasswordManagerSettings s = new PasswordManagerSettings();
        s.setAutoLockMinutes(-5);
        assertEquals(0, s.getAutoLockMinutes());
        s.setGeneratorLength(1);
        assertEquals(PasswordManagerSettings.MIN_LENGTH, s.getGeneratorLength());
        s.setGeneratorLength(9999);
        assertEquals(PasswordManagerSettings.MAX_LENGTH, s.getGeneratorLength());
        s.setLastCategory(null);
        assertEquals("", s.getLastCategory());

        assertTrue(s.isGeneratorUsable());
        s.setGenUpper(false);
        s.setGenLower(false);
        s.setGenDigits(false);
        s.setGenSymbols(false);
        assertFalse(s.isGeneratorUsable(), "no class selected is unusable");

        PasswordManagerSettings d = new PasswordManagerSettings();
        assertEquals(5, d.getAutoLockMinutes());
        assertEquals(20, d.getGeneratorLength());
        assertTrue(d.isGenUpper());
        assertTrue(d.isGenLower());
        assertTrue(d.isGenDigits());
        assertTrue(d.isGenSymbols());
        assertTrue(d.isMaskByDefault());
    }
}
