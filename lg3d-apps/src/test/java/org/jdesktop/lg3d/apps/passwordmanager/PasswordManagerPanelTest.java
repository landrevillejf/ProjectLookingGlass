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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link PasswordManagerPanel}'s lock / unlock / create lifecycle and its
 * entry handling, driven entirely through its package-private hooks so no EDT is
 * pumped and no window is shown. Swing widgets construct headless, so this runs
 * in CI. The store's settings disable auto-lock so no Swing {@code Timer} starts.
 *
 * <p>{@code statusText()} reads a volatile field set synchronously in
 * {@code setStatus}, so the assertions are deterministic off the EDT.</p>
 */
class PasswordManagerPanelTest {

    private static char[] master() {
        return "Sup3rSecret!Passphrase".toCharArray();
    }

    private static char[] wrong() {
        return "totally-wrong-pw".toCharArray();
    }

    /** A store whose saved settings disable auto-lock, so no Swing Timer starts. */
    private static VaultStore noAutoLock(Path dir) {
        VaultStore store = new VaultStore(dir);
        PasswordManagerSettings s = new PasswordManagerSettings();
        s.setAutoLockMinutes(0);
        store.saveSettings(s);
        return store;
    }

    @Test
    @DisplayName("a fresh panel is locked, with no vault and a Locked status")
    void defaultsAreLocked(@TempDir Path dir) {
        PasswordManagerPanel panel = new PasswordManagerPanel(noAutoLock(dir));
        assertTrue(panel.isLocked());
        assertFalse(panel.hasVault());
        assertEquals(0, panel.entryCount());
        assertTrue(panel.entries().isEmpty());
        assertEquals("Locked", panel.statusText());
        assertNotNull(panel.settings());
    }

    @Test
    @DisplayName("createVault rejects an empty master password")
    void createRejectsEmpty(@TempDir Path dir) {
        PasswordManagerPanel panel = new PasswordManagerPanel(noAutoLock(dir));
        assertFalse(panel.createVault(new char[0], new char[0]));
        assertFalse(panel.createVault(null, null));
        assertTrue(panel.isLocked());
        assertFalse(panel.hasVault());
        assertEquals("Choose a master password first.", panel.statusText());
    }

    @Test
    @DisplayName("createVault rejects a mismatched confirmation")
    void createRejectsMismatch(@TempDir Path dir) {
        PasswordManagerPanel panel = new PasswordManagerPanel(noAutoLock(dir));
        assertFalse(panel.createVault(master(), "different".toCharArray()));
        assertTrue(panel.isLocked());
        assertTrue(panel.statusText().contains("do not match"), panel.statusText());
    }

    @Test
    @DisplayName("createVault rejects a too-weak master password")
    void createRejectsWeak(@TempDir Path dir) {
        PasswordManagerPanel panel = new PasswordManagerPanel(noAutoLock(dir));
        assertFalse(panel.createVault("aaa".toCharArray(), "aaa".toCharArray()));
        assertTrue(panel.isLocked());
        assertTrue(panel.statusText().contains("too weak"), panel.statusText());
    }

    @Test
    @DisplayName("unlock is refused when there is no vault yet")
    void unlockWithoutVault(@TempDir Path dir) {
        PasswordManagerPanel panel = new PasswordManagerPanel(noAutoLock(dir));
        assertFalse(panel.unlock(master()));
        assertTrue(panel.isLocked());
        assertTrue(panel.statusText().contains("No vault"), panel.statusText());
    }

    @Test
    @DisplayName("create unlocks, lock clears, and only the right password reopens")
    void createLockUnlockRoundTrip(@TempDir Path dir) {
        PasswordManagerPanel panel = new PasswordManagerPanel(noAutoLock(dir));
        assertTrue(panel.createVault(master(), master()));
        assertFalse(panel.isLocked());
        assertTrue(panel.hasVault());
        assertTrue(panel.statusText().contains("Vault created"), panel.statusText());

        panel.lock();
        assertTrue(panel.isLocked());
        assertEquals(0, panel.entryCount());
        assertEquals("Locked.", panel.statusText());

        PasswordManagerPanel reopened = new PasswordManagerPanel(new VaultStore(dir));
        assertTrue(reopened.hasVault(), "the sealed vault persists");
        assertTrue(reopened.isLocked());

        assertFalse(reopened.unlock(wrong()));
        assertTrue(reopened.isLocked());
        assertEquals("Incorrect master password.", reopened.statusText());

        assertTrue(reopened.unlock(master()));
        assertFalse(reopened.isLocked());
        reopened.lock();
        assertTrue(reopened.isLocked());
    }

    @Test
    @DisplayName("an added entry is persisted and reloads after unlock")
    void addEntryPersists(@TempDir Path dir) {
        PasswordManagerPanel panel = new PasswordManagerPanel(noAutoLock(dir));
        assertTrue(panel.createVault(master(), master()));
        PasswordEntry e = new PasswordEntry("GitHub", "octocat", "pw");
        e.setCategory("Dev");
        panel.addEntry(e);
        assertEquals(1, panel.entryCount());
        assertEquals("GitHub", panel.entries().get(0).getTitle());
        panel.lock();

        PasswordManagerPanel reopened = new PasswordManagerPanel(new VaultStore(dir));
        assertTrue(reopened.unlock(master()));
        assertEquals(1, reopened.entryCount());
        assertEquals("Dev", reopened.entries().get(0).getCategory());
        reopened.lock();
    }

    @Test
    @DisplayName("addEntry is ignored while locked, and ignores null")
    void addEntryIgnoredWhenLocked(@TempDir Path dir) {
        PasswordManagerPanel panel = new PasswordManagerPanel(noAutoLock(dir));
        panel.addEntry(new PasswordEntry("X", "u", "p"));
        assertEquals(0, panel.entryCount());
        panel.addEntry(null);
        assertEquals(0, panel.entryCount());
    }

    @Test
    @DisplayName("lock is safe when already locked")
    void lockIsIdempotent(@TempDir Path dir) {
        PasswordManagerPanel panel = new PasswordManagerPanel(noAutoLock(dir));
        panel.lock();
        assertTrue(panel.isLocked());
        assertEquals("Locked.", panel.statusText());
    }
}
