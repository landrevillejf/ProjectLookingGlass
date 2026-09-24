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

import static org.assertj.core.api.Assertions.*;

class PGPKeyManagerTest {
    
    private PGPKeyManager keyManager;
    
    @BeforeEach
    void setUp() {
        keyManager = new PGPKeyManager();
    }
    
    @Test
    void testKeyManagerInitialization() {
        assertThat(keyManager).isNotNull();
        assertThat(keyManager.getLoadedKeyIds()).isEmpty();
    }
    
    @Test
    void testHasKeyWhenEmpty() {
        assertThat(keyManager.hasKey("0123456789ABCDEF")).isFalse();
    }
    
    @Test
    void testGetLoadedKeyIds() {
        assertThat(keyManager.getLoadedKeyIds()).isNotNull();
        assertThat(keyManager.getLoadedKeyIds()).isEmpty();
    }
    
    @Test
    void testClearKeyRing() {
        keyManager.clear();
        assertThat(keyManager.getLoadedKeyIds()).isEmpty();
    }
    
    @Test
    void testGetPublicKeyNotFound() {
        assertThatThrownBy(() -> keyManager.getPublicKey("NONEXISTENT"))
            .isInstanceOf(UpdateSecurityException.class)
            .hasMessageContaining("Public key not found in keyring");
    }
    
    @Test
    void testLoadPublicKeyFromNullInputStream() {
        assertThatThrownBy(() -> keyManager.loadPublicKey(null, "TESTKEY"))
            .isInstanceOf(NullPointerException.class);
    }
    
    @Test
    void testLoadPublicKeyFromNonexistentClasspath() {
        assertThatThrownBy(() -> 
            keyManager.loadPublicKeyFromClasspath("nonexistent.asc", "TESTKEY"))
            .isInstanceOf(UpdateSecurityException.class)
            .hasMessageContaining("Resource not found");
    }
    
    @Test
    void testIsKeyValidWithNonexistentKey() {
        assertThatThrownBy(() -> keyManager.isKeyValid("NONEXISTENT"))
            .isInstanceOf(UpdateSecurityException.class)
            .hasMessageContaining("Public key not found");
    }
    
    @Test
    void testValidateKeyTrustWithNonexistentKey() {
        assertThatThrownBy(() -> 
            keyManager.validateKeyTrust("NONEXISTENT", "ABCDEF123456"))
            .isInstanceOf(UpdateSecurityException.class)
            .hasMessageContaining("Public key not found");
    }
    
    @Test
    void testGetKeyFingerprintWithNonexistentKey() {
        assertThatThrownBy(() -> keyManager.getKeyFingerprint("NONEXISTENT"))
            .isInstanceOf(UpdateSecurityException.class)
            .hasMessageContaining("Public key not found");
    }
    
    @Test
    void testKeyManagerIsSingleton() {
        PGPKeyManager keyManager2 = new PGPKeyManager();
        assertThat(keyManager).isNotSameAs(keyManager2);
    }
}
