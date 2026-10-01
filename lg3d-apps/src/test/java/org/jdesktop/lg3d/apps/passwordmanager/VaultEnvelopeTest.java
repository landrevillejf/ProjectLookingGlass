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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link VaultEnvelope}: the sealed, persisted form of the vault. Sealing
 * with a master password and opening with the right one round-trips the
 * plaintext; opening with the wrong one throws (GCM authentication), which is how
 * the panel distinguishes "incorrect password" from a corrupt file. A low
 * iteration count keeps the derivations fast.
 */
class VaultEnvelopeTest {

    @Test
    @DisplayName("a default envelope is absent (no vault yet)")
    void defaultIsAbsent() {
        VaultEnvelope e = new VaultEnvelope();
        assertFalse(e.isPresent());
        assertEquals("", e.getSalt());
        assertEquals("", e.getIv());
        assertEquals("", e.getData());
        assertEquals(VaultCrypto.DEFAULT_ITERATIONS, e.getIterations());
    }

    @Test
    @DisplayName("seal then open recovers the exact plaintext")
    void sealThenOpen() throws GeneralSecurityException {
        byte[] plain = "{\"entries\":[]}".getBytes(StandardCharsets.UTF_8);
        VaultEnvelope e = VaultEnvelope.seal(plain, "master-pw".toCharArray(), 1000);
        assertTrue(e.isPresent());
        assertEquals(1000, e.getIterations());
        assertArrayEquals(plain, e.open("master-pw".toCharArray()));
    }

    @Test
    @DisplayName("opening with the wrong password throws")
    void wrongPasswordThrows() throws GeneralSecurityException {
        VaultEnvelope e = VaultEnvelope.seal(
                "secret".getBytes(StandardCharsets.UTF_8), "right".toCharArray(), 1000);
        assertThrows(GeneralSecurityException.class, () -> e.open("wrong".toCharArray()));
    }

    @Test
    @DisplayName("seal clamps a sub-minimum iteration count")
    void sealClampsIterations() throws GeneralSecurityException {
        VaultEnvelope e = VaultEnvelope.seal(
                "x".getBytes(StandardCharsets.UTF_8), "pw".toCharArray(), 10);
        assertEquals(1000, e.getIterations());
        assertArrayEquals("x".getBytes(StandardCharsets.UTF_8), e.open("pw".toCharArray()));
    }

    @Test
    @DisplayName("the sealed fields are Base64 of the documented lengths")
    void sealedFieldsAreBase64() throws GeneralSecurityException {
        VaultEnvelope e = VaultEnvelope.seal(
                "hello".getBytes(StandardCharsets.UTF_8), "pw".toCharArray(), 1000);
        Base64.Decoder dec = Base64.getDecoder();
        assertEquals(VaultCrypto.SALT_BYTES, dec.decode(e.getSalt()).length);
        assertEquals(VaultCrypto.IV_BYTES, dec.decode(e.getIv()).length);
        assertTrue(dec.decode(e.getData()).length >= VaultCrypto.TAG_BITS / 8,
                "ciphertext carries at least the auth tag");
    }

    @Test
    @DisplayName("setters normalise null / blank and clamp iterations")
    void settersNormalize() {
        VaultEnvelope e = new VaultEnvelope();
        e.setSalt(null);
        assertEquals("", e.getSalt());
        e.setIv("   ");
        assertEquals("", e.getIv());
        e.setData(null);
        assertEquals("", e.getData());
        e.setIterations(5);
        assertEquals(1000, e.getIterations());
        e.setIterations(2000);
        assertEquals(2000, e.getIterations());
    }
}
