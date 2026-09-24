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
package com.protonmail.landrevillejf.swingide.update.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the OpenPGP signature gate wired into the install pipeline. The keyring,
 * the verifier and the signature download are mocked so the whole decision tree
 * (missing / invalid / valid, required / optional) is exercised offline.
 */
class UpdateSignatureGateTest {

    private static final String KEY_ID = "ABCDEF0123456789";
    private static final String SIG_URL = "https://example.com/swing-ide-all.jar.asc";

    @TempDir
    Path tempDir;

    private PGPKeyManager keyManager;
    private UpdateSignatureVerifier verifier;
    private Path jarFile;

    @BeforeEach
    void setUp() throws Exception {
        keyManager = mock(PGPKeyManager.class);
        verifier = mock(UpdateSignatureVerifier.class);
        jarFile = tempDir.resolve("swing-ide-all.jar");
        Files.writeString(jarFile, "payload");
    }

    private UpdateSignatureGate gate(UpdateSignatureGate.SignatureDownloader downloader,
                                     boolean required,
                                     String fingerprint) {
        return new UpdateSignatureGate(
            keyManager, verifier, downloader, "public-key.asc", KEY_ID, fingerprint, required
        );
    }

    @Test
    void missingSignatureUrlIsSkippedWhenOptional() throws Exception {
        UpdateSignatureGate gate = gate((url, target) -> true, false, "");

        assertThatCode(() -> gate.verify(jarFile, null, KEY_ID)).doesNotThrowAnyException();
        verify(verifier, never()).verifySignature(any(), any(), any());
    }

    @Test
    void missingSignatureUrlFailsWhenRequired() {
        UpdateSignatureGate gate = gate((url, target) -> true, true, "");

        assertThatThrownBy(() -> gate.verify(jarFile, "  ", KEY_ID))
            .isInstanceOf(UpdateSecurityException.class)
            .hasMessageContaining("Required signature is missing");
    }

    @Test
    void keyNotBundledIsSkippedWhenOptional() throws Exception {
        // A resource that does not exist on the classpath makes the key unavailable.
        UpdateSignatureGate gate = new UpdateSignatureGate(
            keyManager, verifier, (url, target) -> true,
            "does-not-exist.asc", KEY_ID, "", false
        );
        when(keyManager.hasKey(KEY_ID)).thenReturn(false);

        assertThatCode(() -> gate.verify(jarFile, SIG_URL, KEY_ID)).doesNotThrowAnyException();
        verify(verifier, never()).verifySignature(any(), any(), any());
    }

    @Test
    void keyNotBundledFailsWhenRequired() {
        UpdateSignatureGate gate = new UpdateSignatureGate(
            keyManager, verifier, (url, target) -> true,
            "does-not-exist.asc", KEY_ID, "", true
        );
        when(keyManager.hasKey(KEY_ID)).thenReturn(false);

        assertThatThrownBy(() -> gate.verify(jarFile, SIG_URL, KEY_ID))
            .isInstanceOf(UpdateSecurityException.class);
    }

    @Test
    void validSignaturePasses() throws Exception {
        when(keyManager.hasKey(KEY_ID)).thenReturn(true);
        when(verifier.verifySignature(eq(jarFile), any(Path.class), eq(KEY_ID))).thenReturn(true);

        UpdateSignatureGate gate = gate((url, target) -> true, true, "");

        assertThatCode(() -> gate.verify(jarFile, SIG_URL, KEY_ID)).doesNotThrowAnyException();
        verify(verifier).verifySignature(eq(jarFile), any(Path.class), eq(KEY_ID));
    }

    @Test
    void invalidSignatureAlwaysFails() throws Exception {
        when(keyManager.hasKey(KEY_ID)).thenReturn(true);
        when(verifier.verifySignature(eq(jarFile), any(Path.class), eq(KEY_ID))).thenReturn(false);

        // Optional flag must not let an invalid signature through.
        UpdateSignatureGate gate = gate((url, target) -> true, false, "");

        assertThatThrownBy(() -> gate.verify(jarFile, SIG_URL, KEY_ID))
            .isInstanceOf(UpdateSecurityException.class)
            .hasMessageContaining("verification failed");
    }

    @Test
    void strictFingerprintPathIsUsedWhenConfigured() throws Exception {
        when(keyManager.hasKey(KEY_ID)).thenReturn(true);
        doThrow(new UpdateSecurityException("fingerprint mismatch"))
            .when(verifier).verifySignatureStrict(eq(jarFile), any(Path.class), eq(KEY_ID), eq("FP"));

        UpdateSignatureGate gate = gate((url, target) -> true, true, "FP");

        assertThatThrownBy(() -> gate.verify(jarFile, SIG_URL, KEY_ID))
            .isInstanceOf(UpdateSecurityException.class)
            .hasMessageContaining("fingerprint mismatch");
        verify(verifier).verifySignatureStrict(eq(jarFile), any(Path.class), eq(KEY_ID), eq("FP"));
    }

    @Test
    void failedSignatureDownloadIsSkippedWhenOptional() throws Exception {
        when(keyManager.hasKey(KEY_ID)).thenReturn(true);

        UpdateSignatureGate gate = gate((url, target) -> false, false, "");

        assertThatCode(() -> gate.verify(jarFile, SIG_URL, KEY_ID)).doesNotThrowAnyException();
        verify(verifier, never()).verifySignature(any(), any(), any());
    }

    @Test
    void failedSignatureDownloadFailsWhenRequired() {
        when(keyManager.hasKey(KEY_ID)).thenReturn(true);

        UpdateSignatureGate gate = gate((url, target) -> false, true, "");

        assertThatThrownBy(() -> gate.verify(jarFile, SIG_URL, KEY_ID))
            .isInstanceOf(UpdateSecurityException.class);
    }

    @Test
    void metadataKeyIdTakesPrecedenceOverDefault() throws Exception {
        String metadataKey = "1111222233334444";
        when(keyManager.hasKey(metadataKey)).thenReturn(true);
        when(verifier.verifySignature(eq(jarFile), any(Path.class), eq(metadataKey))).thenReturn(true);

        UpdateSignatureGate gate = gate((url, target) -> true, true, "");

        gate.verify(jarFile, SIG_URL, metadataKey);

        verify(verifier).verifySignature(eq(jarFile), any(Path.class), eq(metadataKey));
    }
}
