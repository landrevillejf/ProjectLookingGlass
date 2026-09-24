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

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.bcpg.ArmoredInputStream;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;

import java.io.*;
import java.util.*;

@Slf4j
public class PGPKeyManager {
    
    private final Map<String, PGPPublicKey> keyRing;
    
    public PGPKeyManager() {
        this.keyRing = new HashMap<>();
    }
    
    /**
     * Load a PGP public key from an armored ASCII file.
     */
    public void loadPublicKey(InputStream keyInputStream, String keyId) throws UpdateSecurityException {
        try {
            ArmoredInputStream armoredInput = new ArmoredInputStream(keyInputStream);
            PGPPublicKeyRingCollection pgpPub = new PGPPublicKeyRingCollection(
                armoredInput,
                new BcKeyFingerprintCalculator()
            );
            
            PGPPublicKey publicKey = pgpPub.getPublicKey(Long.parseUnsignedLong(keyId, 16));
            
            if (publicKey == null) {
                throw new UpdateSecurityException("Public key not found in ring: " + keyId);
            }
            
            keyRing.put(keyId, publicKey);
            log.info("Loaded public key: {} (fingerprint: {})", 
                keyId, Long.toHexString(publicKey.getKeyID()));
            
        } catch (IOException | PGPException e) {
            throw new UpdateSecurityException("Failed to load public key: " + keyId, e);
        }
    }
    
    /**
     * Load a PGP public key from a file resource.
     */
    public void loadPublicKeyFromClasspath(String resourcePath, String keyId) 
            throws UpdateSecurityException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new UpdateSecurityException("Resource not found: " + resourcePath);
            }
            loadPublicKey(stream, keyId);
        } catch (IOException e) {
            throw new UpdateSecurityException("Failed to load key from classpath: " + resourcePath, e);
        }
    }
    
    /**
     * Get a public key from the keyring.
     */
    public PGPPublicKey getPublicKey(String keyId) throws UpdateSecurityException {
        PGPPublicKey key = keyRing.get(keyId);
        if (key == null) {
            throw new UpdateSecurityException("Public key not found in keyring: " + keyId);
        }
        return key;
    }
    
    /**
     * Check if a key is in the keyring.
     */
    public boolean hasKey(String keyId) {
        return keyRing.containsKey(keyId);
    }
    
    /**
     * Get all loaded key IDs.
     */
    public Set<String> getLoadedKeyIds() {
        return Collections.unmodifiableSet(keyRing.keySet());
    }
    
    /**
     * Validate key expiration.
     */
    public boolean isKeyValid(String keyId) throws UpdateSecurityException {
        PGPPublicKey key = getPublicKey(keyId);
        
        long validSeconds = key.getValidSeconds();
        if (validSeconds == 0) {
            // Key never expires
            return true;
        }
        
        long expirationTime = key.getCreationTime().getTime() + (validSeconds * 1000L);
        boolean isExpired = System.currentTimeMillis() > expirationTime;
        
        if (isExpired) {
            log.warn("Key {} has expired", keyId);
        }
        
        return !isExpired;
    }
    
    /**
     * Validate key trust level.
     */
    public void validateKeyTrust(String keyId, String expectedFingerprint) throws UpdateSecurityException {
        PGPPublicKey key = getPublicKey(keyId);
        String actualFingerprint = toHexString(key.getFingerprint());
        
        if (!actualFingerprint.equalsIgnoreCase(expectedFingerprint)) {
            throw new UpdateSecurityException(
                "Key fingerprint mismatch. Expected: " + expectedFingerprint + 
                ", Actual: " + actualFingerprint
            );
        }
        
        log.info("Key fingerprint validated: {}", actualFingerprint);
    }
    
    /**
     * Get key fingerprint as hex string.
     */
    public String getKeyFingerprint(String keyId) throws UpdateSecurityException {
        PGPPublicKey key = getPublicKey(keyId);
        return toHexString(key.getFingerprint());
    }
    
    /**
     * Clear all loaded keys.
     */
    public void clear() {
        keyRing.clear();
        log.debug("PGP keyring cleared");
    }
    
    private String toHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
