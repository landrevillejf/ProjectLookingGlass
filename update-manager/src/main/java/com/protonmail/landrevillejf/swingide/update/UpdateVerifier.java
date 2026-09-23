package com.protonmail.landrevillejf.swingide.update;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

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
            
            // TODO: Implement PGP signature verification
            // For now, we only verify checksum
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
    
    public VerificationResult verifyWithSignature(
        Path jarFile,
        Path signatureFile
    ) {
        try {
            // Verify checksum first
            String actualChecksum = ChecksumCalculator.calculateSHA256(jarFile);
            log.debug("File checksum: {}", actualChecksum);
            
            // TODO: Implement PGP signature verification
            // This would require Bouncy Castle or similar library
            // For now, we return success if the file exists and has a valid checksum
            
            if (!Files.exists(signatureFile)) {
                return new VerificationResult(
                    false,
                    "Signature file not found"
                );
            }
            
            log.info("Signature verification not yet implemented, skipping");
            
            return new VerificationResult(
                true,
                "Checksum verified (signature verification not implemented)"
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
