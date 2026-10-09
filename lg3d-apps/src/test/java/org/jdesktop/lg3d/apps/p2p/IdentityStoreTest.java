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
package org.jdesktop.lg3d.apps.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link IdentityStore} over a {@link TempDir}: our identity is generated
 * once, persisted, and reloaded identically (with owner-only {@code 0600} permissions
 * where the filesystem is POSIX), and a corrupt identity file is regenerated rather
 * than crashing. The TOFU pin lifecycle is exercised end to end - first contact pins,
 * a match refreshes, a mismatch leaves the old pin intact until an explicit override
 * replaces and flags it - and pins survive across store instances over the same
 * directory. Directory resolution honours the {@code lg3d.p2p.dir} override.
 */
class IdentityStoreTest {

    private static String fingerprint() throws GeneralSecurityException {
        return P2pCrypto.fingerprint(P2pCrypto.generateKeyPair().getPublic());
    }

    @Test
    @DisplayName("our identity is generated, persisted and reloaded identically")
    void identityPersists(@TempDir Path dir) {
        IdentityStore store = new IdentityStore(dir);
        KeyPair first = store.loadOrCreateIdentity();
        assertNotNull(first);
        Path identityFile = dir.resolve("identity.json");
        assertTrue(Files.exists(identityFile), "the identity should be written to disk");

        String fp = fingerprintOf(first);
        assertEquals(fp, store.getOurFingerprint());

        // A fresh store over the same directory loads the very same identity.
        IdentityStore reopened = new IdentityStore(dir);
        KeyPair second = reopened.loadOrCreateIdentity();
        assertEquals(fp, fingerprintOf(second), "the reloaded identity is stable");
        assertEquals(fp, reopened.getOurFingerprint());
    }

    @Test
    @DisplayName("the identity file is owner-only (0600) on a POSIX filesystem")
    void identityIsRestricted(@TempDir Path dir) {
        new IdentityStore(dir).loadOrCreateIdentity();
        Path identityFile = dir.resolve("identity.json");
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        org.junit.jupiter.api.Assumptions.assumeTrue(posix, "not a POSIX filesystem");
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(identityFile);
            assertEquals(PosixFilePermissions.fromString("rw-------"), perms,
                    "the private key must not be readable by group/others");
        } catch (Exception ex) {
            org.junit.jupiter.api.Assertions.fail("could not read permissions", ex);
        }
    }

    @Test
    @DisplayName("a corrupt identity file is regenerated instead of crashing")
    void corruptIdentityRegenerates(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("identity.json"), "{ this is not valid json ");
        IdentityStore store = new IdentityStore(dir);
        KeyPair regenerated = store.loadOrCreateIdentity();
        assertNotNull(regenerated);
        assertNotNull(P2pCrypto.fingerprint(regenerated.getPublic()));
    }

    @Test
    @DisplayName("TOFU: first contact pins, match refreshes, mismatch is held, override replaces")
    void tofuLifecycle(@TempDir Path dir) throws Exception {
        String fpA = fingerprint();
        String fpB = fingerprint();
        IdentityStore store = new IdentityStore(dir);

        // Nothing pinned yet.
        assertEquals(TrustDecision.FIRST_CONTACT, store.verify("acct-1", fpA));
        assertFalse(store.isPinned("acct-1"));
        assertNull(store.getPinnedFingerprint("acct-1"));

        // First contact records the pin.
        assertEquals(TrustDecision.FIRST_CONTACT, store.verifyAndRecord("acct-1", fpA, "alice"));
        assertTrue(store.isPinned("acct-1"));
        assertEquals(fpA, store.getPinnedFingerprint("acct-1"));
        assertEquals(1, store.getPinCount());
        assertEquals("alice", store.getPin("acct-1").getNickname());

        // The same fingerprint now matches, and a new nickname is picked up.
        assertEquals(TrustDecision.MATCH, store.verifyAndRecord("acct-1", fpA, "alice2"));
        assertEquals("alice2", store.getPin("acct-1").getNickname());
        assertEquals(1, store.getPinCount(), "a match must not create a second pin");
        assertFalse(store.getPin("acct-1").isOverridden());

        // A different fingerprint for the same slot is a mismatch and is NOT pinned.
        assertEquals(TrustDecision.MISMATCH, store.verifyAndRecord("acct-1", fpB, "mallory"));
        assertEquals(fpA, store.getPinnedFingerprint("acct-1"),
                "a mismatch must leave the original pin untouched");
        assertFalse(store.getPin("acct-1").isOverridden());

        // An explicit override replaces the pin and flags it.
        store.overrideMismatch("acct-1", fpB, "alice");
        assertEquals(fpB, store.getPinnedFingerprint("acct-1"));
        assertTrue(store.getPin("acct-1").isOverridden());
        assertEquals(TrustDecision.MATCH, store.verify("acct-1", fpB));
        assertTrue(store.getPin("acct-1").toString().contains("overridden"));
    }

    @Test
    @DisplayName("pins survive across store instances over the same directory")
    void pinsPersistAcrossInstances(@TempDir Path dir) throws Exception {
        String fp = fingerprint();
        IdentityStore writer = new IdentityStore(dir);
        writer.verifyAndRecord("peer-key", fp, "bob");
        assertTrue(Files.exists(dir.resolve("pins.json")));

        IdentityStore reader = new IdentityStore(dir);
        assertEquals(1, reader.getPinCount());
        assertEquals(fp, reader.getPinnedFingerprint("peer-key"));
        assertEquals(TrustDecision.MATCH, reader.verify("peer-key", fp));
        assertEquals(TrustDecision.MISMATCH, reader.verify("peer-key", fingerprint()));
    }

    @Test
    @DisplayName("explicit pin, removal and the pin snapshot behave")
    void pinRemoveAndSnapshot(@TempDir Path dir) throws Exception {
        String fp1 = fingerprint();
        String fp2 = fingerprint();
        IdentityStore store = new IdentityStore(dir);
        store.pin("k1", fp1, "one");
        store.pin("k2", fp2, "two");
        assertEquals(2, store.getPinCount());
        assertEquals(2, store.getPins().size());
        assertEquals(fp2, store.getPinnedFingerprint("k2"));

        store.remove("k1");
        assertFalse(store.isPinned("k1"));
        assertEquals(1, store.getPinCount());
        // Removing an unknown key is a harmless no-op.
        store.remove("nope");
        assertEquals(1, store.getPinCount());

        IdentityStore.PinRecord rec = store.getPin("k2");
        assertEquals("k2", rec.getPeerKey());
        assertEquals(fp2, rec.getFingerprint());
        assertEquals("two", rec.getNickname());
        assertTrue(rec.getFirstSeenMs() > 0);
        assertTrue(rec.getLastSeenMs() >= rec.getFirstSeenMs());
        assertNotNull(rec.toString());
    }

    @Test
    @DisplayName("blank keys and fingerprints are ignored rather than pinned")
    void blankInputsIgnored(@TempDir Path dir) throws Exception {
        IdentityStore store = new IdentityStore(dir);
        store.pin("  ", fingerprint(), "x");
        store.pin(null, fingerprint(), "x");
        store.pin("k", null, "x");
        store.pin("k", "   ", "x");
        assertEquals(0, store.getPinCount(), "nothing stable to key on or pin");

        // A blank presented fingerprint is a mismatch and records nothing.
        assertEquals(TrustDecision.MISMATCH, store.verifyAndRecord("k", "", "x"));
        assertEquals(0, store.getPinCount());
        assertNull(store.getPin(null));
        assertNull(store.getPin("   "));
    }

    @Test
    @DisplayName("directory resolution honours the lg3d.p2p.dir override")
    void directoryOverride(@TempDir Path dir) {
        assertEquals(dir, new IdentityStore(dir).getConfigDir());

        String previous = System.getProperty(IdentityStore.DIR_PROPERTY);
        try {
            System.setProperty(IdentityStore.DIR_PROPERTY, dir.toString());
            assertEquals(dir, IdentityStore.defaultConfigDir());
        } finally {
            restore(previous);
        }

        // With no override, the default sits under ~/.lg3d/p2p.
        String beforeDefault = System.getProperty(IdentityStore.DIR_PROPERTY);
        System.clearProperty(IdentityStore.DIR_PROPERTY);
        try {
            assertTrue(IdentityStore.defaultConfigDir().endsWith(Paths.get(".lg3d", "p2p")),
                    "the default directory should end with .lg3d/p2p");
        } finally {
            restore(beforeDefault);
        }
    }

    @Test
    @DisplayName("a null config dir falls back to the default")
    void nullConfigDirFallsBack() {
        String previous = System.getProperty(IdentityStore.DIR_PROPERTY);
        try {
            System.clearProperty(IdentityStore.DIR_PROPERTY);
            IdentityStore store = new IdentityStore(null);
            assertTrue(store.getConfigDir().endsWith(Paths.get(".lg3d", "p2p")));
        } finally {
            restore(previous);
        }
    }

    private static void restore(String value) {
        if (value == null) {
            System.clearProperty(IdentityStore.DIR_PROPERTY);
        } else {
            System.setProperty(IdentityStore.DIR_PROPERTY, value);
        }
    }

    private static String fingerprintOf(KeyPair keyPair) {
        try {
            return P2pCrypto.fingerprint(keyPair.getPublic());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
