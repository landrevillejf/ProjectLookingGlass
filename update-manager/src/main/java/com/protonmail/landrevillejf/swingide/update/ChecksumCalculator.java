package com.protonmail.landrevillejf.swingide.update;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Slf4j
public class ChecksumCalculator {
    
    private static final int BUFFER_SIZE = 8192;
    
    private ChecksumCalculator() {
        // Utility class
    }
    
    public static String calculateSHA256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            
            try (InputStream is = Files.newInputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            
            byte[] hash = digest.digest();
            return bytesToHex(hash);
            
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not available", e);
        }
    }
    
    public static boolean verifyChecksum(Path file, String expectedSha256) {
        try {
            String actual = calculateSHA256(file);
            boolean matches = actual.equalsIgnoreCase(expectedSha256);
            
            if (!matches) {
                log.warn("Checksum mismatch for file: {}", file);
                log.debug("Expected: {}, Actual: {}", expectedSha256, actual);
            }
            
            return matches;
        } catch (IOException e) {
            log.error("Failed to calculate checksum for file: {}", file, e);
            return false;
        }
    }
    
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    public static String calculateSHA256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
