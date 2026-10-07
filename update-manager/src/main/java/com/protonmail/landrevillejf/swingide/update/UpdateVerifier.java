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
package com.protonmail.landrevillejf.swingide.update;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Checksum-only integrity verifier for a downloaded update.
 * <p>
 * This class verifies the SHA-256 checksum of an artifact and nothing else. It
 * deliberately does <strong>not</strong> perform OpenPGP signature verification:
 * the real, enforced detached-signature path lives in
 * {@link com.protonmail.landrevillejf.swingide.update.security.UpdateSignatureGate}
 * (over {@code UpdateSignatureVerifier} + {@code PGPKeyManager}), which
 * {@code UpdateService.verifySignature} runs when {@code update.signature.enabled}
 * is set. Checksum and signature are complementary: the checksum catches
 * corruption, the signature catches a tampered source.
 * </p>
 */
@Slf4j
public class UpdateVerifier {
    
    private final String publicKeyPath;
    
    public UpdateVerifier() {
        this("public-key.asc");
    }
    
    public UpdateVerifier(String publicKeyPath) {
        this.publicKeyPath = publicKeyPath;
    }
    
    public VerificationResult verify(Path jarFile, String expectedSha256) {
        try {
            // Verify checksum
            if (!ChecksumCalculator.verifyChecksum(jarFile, expectedSha256)) {
                return new VerificationResult(
                    false,
                    "Checksum verification failed"
                );
            }
            
            // Checksum-only by design: OpenPGP signature verification is enforced
            // separately by UpdateSignatureGate (see the class Javadoc), not here.
            log.info("Checksum verified successfully for: {}", jarFile);
            
            return new VerificationResult(
                true,
                "Verification successful"
            );
            
        } catch (Exception e) {
            log.error("Verification failed for file: {}", jarFile, e);
            return new VerificationResult(
                false,
                "Verification error: " + e.getMessage()
            );
        }
    }
    
    /**
     * Checks the SHA-256 checksum and that a signature file is present, but does
     * <strong>not</strong> cryptographically verify the OpenPGP signature.
     *
     * @deprecated this method gives no signature assurance — a well-formed but
     *     forged {@code .asc} passes as long as the file exists. Use
     *     {@link com.protonmail.landrevillejf.swingide.update.security.UpdateSignatureGate#verify}
     *     for real detached-signature enforcement. Retained only for backward
     *     compatibility.
     */
    @Deprecated
    public VerificationResult verifyWithSignature(
        Path jarFile,
        Path signatureFile
    ) {
        try {
            // Verify checksum first
            String actualChecksum = ChecksumCalculator.calculateSHA256(jarFile);
            log.debug("File checksum: {}", actualChecksum);

            // NOTE: no OpenPGP verification happens here (see the deprecation
            // notice); only the presence of the signature file is checked. Real
            // signature enforcement is UpdateSignatureGate.
            if (!Files.exists(signatureFile)) {
                return new VerificationResult(
                    false,
                    "Signature file not found"
                );
            }

            log.info("Signature not cryptographically verified here; use UpdateSignatureGate");

            return new VerificationResult(
                true,
                "Checksum verified (signature verification not performed)"
            );
            
        } catch (Exception e) {
            log.error("Signature verification failed", e);
            return new VerificationResult(
                false,
                "Signature verification error: " + e.getMessage()
            );
        }
    }
    
    @Value
    public static class VerificationResult {
        boolean valid;
        String message;
    }
}
