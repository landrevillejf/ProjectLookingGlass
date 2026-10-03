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
package org.jdesktop.lg3d.apps.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link CredentialVault}: the seal/open round trip, the
 * random-IV property (the same password seals to a different token each time), and
 * the honest failure modes (blank input, tampered token) that must yield null
 * rather than throw.
 */
class CredentialVaultTest {

    @BeforeEach
    void clear() {
        remove("/mail");
    }

    @AfterEach
    void cleanup() {
        remove("/mail");
    }

    private static void remove(String path) {
        try {
            if (Preferences.userRoot().nodeExists(path)) {
                Preferences.userRoot().node(path).removeNode();
            }
        } catch (Exception e) {
            // best effort
        }
    }

    @Test
    void sealsAndOpensRoundTrip() {
        CredentialVault vault = new CredentialVault();
        String sealed = vault.seal("s3cr3t-pa\u00dfword");
        assertNotEquals("s3cr3t-pa\u00dfword", sealed);
        assertEquals("s3cr3t-pa\u00dfword", vault.open(sealed));
    }

    @Test
    void samePasswordSealsDifferentlyEachTime() {
        CredentialVault vault = new CredentialVault();
        String a = vault.seal("hunter2");
        String b = vault.seal("hunter2");
        assertNotEquals(a, b, "IV should make each token unique");
        assertEquals("hunter2", vault.open(a));
        assertEquals("hunter2", vault.open(b));
    }

    @Test
    void blankInputSealsToNull() {
        CredentialVault vault = new CredentialVault();
        assertNull(vault.seal(null));
        assertNull(vault.seal(""));
        assertNull(vault.open(null));
        assertNull(vault.open(""));
    }

    @Test
    void tamperedTokenOpensToNull() {
        CredentialVault vault = new CredentialVault();
        String sealed = vault.seal("correct horse");
        // Flip a character in the ciphertext body (past the IV prefix).
        char[] chars = sealed.toCharArray();
        chars[chars.length - 3] = chars[chars.length - 3] == 'A' ? 'B' : 'A';
        assertNull(vault.open(new String(chars)));
    }

    @Test
    void keyIsReusedAcrossInstances() {
        CredentialVault first = new CredentialVault();
        String sealed = first.seal("persisted");
        // A fresh instance reads the same per-install key from Preferences.
        CredentialVault second = new CredentialVault();
        assertEquals("persisted", second.open(sealed));
    }
}
