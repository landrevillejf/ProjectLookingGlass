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
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider;

import java.io.*;
import java.nio.file.Path;

@Slf4j
public class UpdateSignatureVerifier {
    
    private final PGPKeyManager keyManager;
    
    public UpdateSignatureVerifier(PGPKeyManager keyManager) {
        this.keyManager = keyManager;
    }
    
    /**
     * Verify a file signature.
     */
    public boolean verifySignature(Path filePath, Path signaturePath, String keyId) 
            throws UpdateSecurityException {
        try {
            // Read the file to be verified
            byte[] fileData = java.nio.file.Files.readAllBytes(filePath);
            
            // Read and parse the signature
            try (InputStream sigInputStream = new FileInputStream(signaturePath.toFile())) {
                ArmoredInputStream armoredSig = new ArmoredInputStream(sigInputStream);
                JcaPGPObjectFactory pgpObjectFactory = new JcaPGPObjectFactory(armoredSig);
                PGPSignatureList signatureList = (PGPSignatureList) pgpObjectFactory.nextObject();
                
                if (signatureList.isEmpty()) {
                    throw new UpdateSecurityException("No signatures found in signature file");
                }
                
                PGPSignature signature = signatureList.get(0);
                
                // Get the public key
                PGPPublicKey publicKey = keyManager.getPublicKey(keyId);
                
                // Initialize the signature verifier
                signature.init(
                    new JcaPGPContentVerifierBuilderProvider(),
                    publicKey
                );
                
                // Verify the signature
                signature.update(fileData);
                boolean isValid = signature.verify();
                
                if (isValid) {
                    log.info("Signature verification successful for file: {}", filePath.getFileName());
                } else {
                    log.error("Signature verification failed for file: {}", filePath.getFileName());
                }
                
                return isValid;
                
            }
        } catch (IOException e) {
            throw new UpdateSecurityException("I/O error during signature verification", e);
        } catch (PGPException e) {
            throw new UpdateSecurityException("PGP error during signature verification", e);
        }
    }
    
    /**
     * Verify file with additional metadata checks.
     */
    public void verifySignatureStrict(Path filePath, Path signaturePath, String keyId, 
                                      String expectedFingerprint) throws UpdateSecurityException {
        // Validate key is trusted
        keyManager.validateKeyTrust(keyId, expectedFingerprint);
        
        // Validate key is not expired
        if (!keyManager.isKeyValid(keyId)) {
            throw new UpdateSecurityException("Signing key has expired: " + keyId);
        }
        
        // Verify the signature
        boolean isValid = verifySignature(filePath, signaturePath, keyId);
        if (!isValid) {
            throw new UpdateSecurityException("Signature verification failed for update file");
        }
        
        log.info("Strict signature verification passed for: {}", filePath.getFileName());
    }
    
    /**
     * Get signature metadata without verification.
     */
    public String getSignatureKeyId(Path signaturePath) throws UpdateSecurityException {
        try (InputStream sigInputStream = new FileInputStream(signaturePath.toFile())) {
            ArmoredInputStream armoredSig = new ArmoredInputStream(sigInputStream);
            JcaPGPObjectFactory pgpObjectFactory = new JcaPGPObjectFactory(armoredSig);
            Object obj = pgpObjectFactory.nextObject();
            
            if (!(obj instanceof PGPSignatureList)) {
                throw new UpdateSecurityException("Invalid signature file format");
            }
            
            PGPSignatureList signatureList = (PGPSignatureList) obj;
            
            if (signatureList.isEmpty()) {
                throw new UpdateSecurityException("No signatures found in signature file");
            }
            
            PGPSignature signature = signatureList.get(0);
            return Long.toHexString(signature.getKeyID()).toUpperCase();
            
        } catch (IOException e) {
            throw new UpdateSecurityException("Failed to read signature metadata", e);
        }
    }
}
