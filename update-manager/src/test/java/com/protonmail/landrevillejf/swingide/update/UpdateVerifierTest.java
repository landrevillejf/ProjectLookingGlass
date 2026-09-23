package com.protonmail.landrevillejf.swingide.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class UpdateVerifierTest {
    
    @TempDir
    Path tempDir;
    
    @Test
    void testVerifySuccessWithValidChecksum() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        String content = "Fake JAR content";
        Files.writeString(jarFile, content);
        
        String correctChecksum = ChecksumCalculator.calculateSHA256(jarFile);
        
        UpdateVerifier verifier = new UpdateVerifier();
        UpdateVerifier.VerificationResult result = verifier.verify(jarFile, correctChecksum);
        
        assertThat(result.isValid()).isTrue();
        assertThat(result.getMessage()).contains("successful");
    }
    
    @Test
    void testVerifyFailureWithInvalidChecksum() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        Files.writeString(jarFile, "Fake JAR content");
        
        String wrongChecksum = "0000000000000000000000000000000000000000000000000000000000000000";
        
        UpdateVerifier verifier = new UpdateVerifier();
        UpdateVerifier.VerificationResult result = verifier.verify(jarFile, wrongChecksum);
        
        assertThat(result.isValid()).isFalse();
        assertThat(result.getMessage()).contains("Checksum");
    }
    
    @Test
    void testVerifyWithSignatureSuccess() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        Path sigFile = tempDir.resolve("test.jar.asc");
        
        String jarContent = "Fake JAR content";
        Files.writeString(jarFile, jarContent);
        Files.writeString(sigFile, "Fake signature");
        
        UpdateVerifier verifier = new UpdateVerifier();
        UpdateVerifier.VerificationResult result = 
            verifier.verifyWithSignature(jarFile, sigFile);
        
        assertThat(result.isValid()).isTrue();
    }
    
    @Test
    void testVerifyWithSignatureFailureWhenSignatureFileMissing() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        Path sigFile = tempDir.resolve("missing.jar.asc");
        
        Files.writeString(jarFile, "Fake JAR content");
        
        UpdateVerifier verifier = new UpdateVerifier();
        UpdateVerifier.VerificationResult result = 
            verifier.verifyWithSignature(jarFile, sigFile);
        
        assertThat(result.isValid()).isFalse();
        assertThat(result.getMessage()).contains("Signature file not found");
    }
    
    @Test
    void testVerifyWithDifferentKeyPath() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        Files.writeString(jarFile, "Fake JAR content");
        
        String correctChecksum = ChecksumCalculator.calculateSHA256(jarFile);
        
        UpdateVerifier verifier = new UpdateVerifier("custom-key.asc");
        UpdateVerifier.VerificationResult result = verifier.verify(jarFile, correctChecksum);
        
        assertThat(result.isValid()).isTrue();
    }
    
    @Test
    void testVerifyChecksumCaseInsensitivity() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        Files.writeString(jarFile, "Test JAR content");
        
        String correctChecksum = ChecksumCalculator.calculateSHA256(jarFile);
        String uppercaseChecksum = correctChecksum.toUpperCase();
        
        UpdateVerifier verifier = new UpdateVerifier();
        UpdateVerifier.VerificationResult result = verifier.verify(jarFile, uppercaseChecksum);
        
        assertThat(result.isValid()).isTrue();
    }
    
    @Test
    void testVerifyLargeFile() throws IOException {
        Path largeFile = tempDir.resolve("large.jar");
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            sb.append("Content line ").append(i).append("\n");
        }
        Files.writeString(largeFile, sb.toString());
        
        String correctChecksum = ChecksumCalculator.calculateSHA256(largeFile);
        
        UpdateVerifier verifier = new UpdateVerifier();
        UpdateVerifier.VerificationResult result = verifier.verify(largeFile, correctChecksum);
        
        assertThat(result.isValid()).isTrue();
    }
    
    @Test
    void testVerifyMessageContent() throws IOException {
        Path jarFile = tempDir.resolve("test.jar");
        Files.writeString(jarFile, "Test content");
        
        String checksum = ChecksumCalculator.calculateSHA256(jarFile);
        
        UpdateVerifier verifier = new UpdateVerifier();
        UpdateVerifier.VerificationResult result = verifier.verify(jarFile, checksum);
        
        assertThat(result.getMessage())
            .isNotEmpty()
            .isNotBlank();
    }
}
