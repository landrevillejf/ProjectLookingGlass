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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Random;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link VaultCrypto}'s pure cryptographic seam: random material, PBKDF2
 * key derivation, AES-GCM seal/open, password generation and strength scoring.
 * Nothing here touches a display or the filesystem, so it runs headless in CI.
 * Derivations use a low (clamped) iteration count to keep the suite fast.
 */
class VaultCryptoTest {

    @Test
    @DisplayName("salt / IV have the documented lengths and are never reused")
    void randomMaterial() {
        assertEquals(16, VaultCrypto.SALT_BYTES);
        assertEquals(12, VaultCrypto.IV_BYTES);
        assertEquals(256, VaultCrypto.KEY_BITS);
        assertEquals(128, VaultCrypto.TAG_BITS);
        byte[] s1 = VaultCrypto.newSalt();
        byte[] s2 = VaultCrypto.newSalt();
        assertEquals(VaultCrypto.SALT_BYTES, s1.length);
        assertFalse(Arrays.equals(s1, s2), "two salts differ");
        assertEquals(VaultCrypto.IV_BYTES, VaultCrypto.newIv().length);
    }

    @Test
    @DisplayName("randomBytes honours length, clamps negatives and a null RNG")
    void randomBytes() {
        assertEquals(8, VaultCrypto.randomBytes(8, new Random(1)).length);
        assertEquals(0, VaultCrypto.randomBytes(-3, new Random(1)).length);
        byte[] zeros = VaultCrypto.randomBytes(4, null);
        assertEquals(4, zeros.length);
        for (byte b : zeros) {
            assertEquals(0, b, "a null RNG yields zeros");
        }
        assertArrayEquals(VaultCrypto.randomBytes(6, new Random(42)),
                VaultCrypto.randomBytes(6, new Random(42)),
                "a seeded RNG is reproducible");
    }

    @Test
    @DisplayName("deriveKey is deterministic, password-sensitive and yields AES-256")
    void deriveKeyDeterministic() throws GeneralSecurityException {
        byte[] salt = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        SecretKey a = VaultCrypto.deriveKey("hunter2".toCharArray(), salt, 1000);
        SecretKey b = VaultCrypto.deriveKey("hunter2".toCharArray(), salt, 1000);
        SecretKey c = VaultCrypto.deriveKey("hunter3".toCharArray(), salt, 1000);
        assertEquals("AES", a.getAlgorithm());
        assertEquals(32, a.getEncoded().length, "AES-256 is 32 bytes");
        assertArrayEquals(a.getEncoded(), b.getEncoded());
        assertFalse(Arrays.equals(a.getEncoded(), c.getEncoded()));
    }

    @Test
    @DisplayName("deriveKey tolerates a null password/salt and clamps a tiny count")
    void deriveKeyGuards() throws GeneralSecurityException {
        SecretKey k = VaultCrypto.deriveKey(null, null, 1);
        assertNotNull(k);
        assertEquals(32, k.getEncoded().length);
        SecretKey clamped = VaultCrypto.deriveKey("pw".toCharArray(), new byte[16], 1);
        SecretKey minimum = VaultCrypto.deriveKey("pw".toCharArray(), new byte[16], 1000);
        assertArrayEquals(clamped.getEncoded(), minimum.getEncoded(),
                "a sub-minimum iteration count is clamped to 1000");
    }

    @Test
    @DisplayName("encrypt / decrypt round-trips and a wrong key fails authentication")
    void sealOpenRoundTrip() throws GeneralSecurityException {
        byte[] salt = VaultCrypto.newSalt();
        byte[] iv = VaultCrypto.newIv();
        SecretKey key = VaultCrypto.deriveKey("master".toCharArray(), salt, 1000);
        byte[] plain = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
        byte[] cipher = VaultCrypto.encrypt(plain, key, iv);
        assertFalse(Arrays.equals(plain, cipher), "ciphertext differs from plaintext");
        assertArrayEquals(plain, VaultCrypto.decrypt(cipher, key, iv));

        SecretKey wrong = VaultCrypto.deriveKey("not-it".toCharArray(), salt, 1000);
        assertThrows(GeneralSecurityException.class,
                () -> VaultCrypto.decrypt(cipher, wrong, iv),
                "GCM authentication rejects the wrong key");
    }

    @Test
    @DisplayName("encrypt treats a null plaintext as empty")
    void encryptNull() throws GeneralSecurityException {
        byte[] salt = VaultCrypto.newSalt();
        byte[] iv = VaultCrypto.newIv();
        SecretKey key = VaultCrypto.deriveKey("m".toCharArray(), salt, 1000);
        byte[] cipher = VaultCrypto.encrypt(null, key, iv);
        assertEquals(0, VaultCrypto.decrypt(cipher, key, iv).length);
    }

    @Test
    @DisplayName("generatePassword honours length, clamping, classes and a seed")
    void generatePassword() {
        Random rng = new Random(7);
        String p = VaultCrypto.generatePassword(24, true, true, true, true, rng);
        assertEquals(24, p.length());
        assertEquals(p, VaultCrypto.generatePassword(24, true, true, true, true, new Random(7)),
                "the same seed reproduces the same password");
        assertEquals(4, VaultCrypto.generatePassword(1, true, false, false, false, rng).length(),
                "length is clamped up to 4");
        assertEquals(128, VaultCrypto.generatePassword(9999, true, false, false, false, rng).length(),
                "length is clamped down to 128");
        assertEquals("", VaultCrypto.generatePassword(12, false, false, false, false, rng),
                "no selected class yields an empty password");
    }

    @Test
    @DisplayName("generatePassword guarantees one character per selected class")
    void generatePasswordClasses() {
        Random rng = new Random(99);
        for (int i = 0; i < 20; i++) {
            String p = VaultCrypto.generatePassword(12, true, true, true, true, rng);
            assertTrue(p.chars().anyMatch(Character::isUpperCase), p);
            assertTrue(p.chars().anyMatch(Character::isLowerCase), p);
            assertTrue(p.chars().anyMatch(Character::isDigit), p);
            assertTrue(p.chars().anyMatch(c -> !Character.isLetterOrDigit(c)), p);
        }
    }

    @Test
    @DisplayName("a null RNG still produces a password")
    void generatePasswordNullRng() {
        String p = VaultCrypto.generatePassword(16, true, true, true, true, null);
        assertEquals(16, p.length());
    }

    @Test
    @DisplayName("strengthScore is zero for null/empty and bounded to [0,100]")
    void strengthScoreBounds() {
        assertEquals(0, VaultCrypto.strengthScore(null));
        assertEquals(0, VaultCrypto.strengthScore(""));
        String[] samples = {"a", "aaa", "password", "Monkey", "Tr0ub4dor&3",
            "correct horse battery staple", "abcdefghijklmnop"};
        for (String s : samples) {
            int score = VaultCrypto.strengthScore(s);
            assertTrue(score >= 0 && score <= 100, s + " -> " + score);
        }
    }

    @Test
    @DisplayName("trivial passwords score far below long mixed ones")
    void strengthScoreOrdering() {
        assertTrue(VaultCrypto.strengthScore("aaa") < VaultCrypto.strengthScore("aaaX9!"),
                "a single-character repeat is penalised");
        assertTrue(VaultCrypto.strengthScore("password") < VaultCrypto.strengthScore("pxzzwqrd"),
                "a dictionary word is penalised");
        assertEquals(VaultCrypto.Strength.VERY_WEAK, VaultCrypto.strength("aaa"));
        assertEquals(VaultCrypto.Strength.VERY_STRONG, VaultCrypto.strength("Tr0ub4dor&3"));
    }

    @Test
    @DisplayName("describeStrength labels every band and null")
    void describeStrength() {
        assertEquals("Very weak", VaultCrypto.describeStrength(VaultCrypto.Strength.VERY_WEAK));
        assertEquals("Weak", VaultCrypto.describeStrength(VaultCrypto.Strength.WEAK));
        assertEquals("Fair", VaultCrypto.describeStrength(VaultCrypto.Strength.FAIR));
        assertEquals("Strong", VaultCrypto.describeStrength(VaultCrypto.Strength.STRONG));
        assertEquals("Very strong", VaultCrypto.describeStrength(VaultCrypto.Strength.VERY_STRONG));
        assertEquals("Unknown", VaultCrypto.describeStrength(null));
    }
}
