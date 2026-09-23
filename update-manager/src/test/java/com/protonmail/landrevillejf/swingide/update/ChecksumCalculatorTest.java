package com.protonmail.landrevillejf.swingide.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class ChecksumCalculatorTest {
    
    @TempDir
    Path tempDir;
    
    @Test
    void testCalculateSHA256ForFile() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        String content = "Hello, World!";
        Files.writeString(testFile, content);
        
        String checksum = ChecksumCalculator.calculateSHA256(testFile);
        
        assertThat(checksum)
            .isNotEmpty()
            .hasSize(64)
            .matches("[0-9a-f]+");
    }
    
    @Test
    void testCalculateSHA256Consistency() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        String content = "Test content for consistency";
        Files.writeString(testFile, content);
        
        String checksum1 = ChecksumCalculator.calculateSHA256(testFile);
        String checksum2 = ChecksumCalculator.calculateSHA256(testFile);
        
        assertThat(checksum1).isEqualTo(checksum2);
    }
    
    @Test
    void testVerifyChecksumSuccess() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        String content = "Content to verify";
        Files.writeString(testFile, content);
        
        String expectedChecksum = ChecksumCalculator.calculateSHA256(testFile);
        boolean result = ChecksumCalculator.verifyChecksum(testFile, expectedChecksum);
        
        assertThat(result).isTrue();
    }
    
    @Test
    void testVerifyChecksumFailure() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        String content = "Content to verify";
        Files.writeString(testFile, content);
        
        String wrongChecksum = "0000000000000000000000000000000000000000000000000000000000000000";
        boolean result = ChecksumCalculator.verifyChecksum(testFile, wrongChecksum);
        
        assertThat(result).isFalse();
    }
    
    @Test
    void testCalculateSHA256FromString() {
        String text = "Hello, World!";
        String checksum = ChecksumCalculator.calculateSHA256(text);
        
        assertThat(checksum)
            .isNotEmpty()
            .hasSize(64)
            .matches("[0-9a-f]+");
    }
    
    @Test
    void testCalculateSHA256StringConsistency() {
        String text = "Test string";
        String checksum1 = ChecksumCalculator.calculateSHA256(text);
        String checksum2 = ChecksumCalculator.calculateSHA256(text);
        
        assertThat(checksum1).isEqualTo(checksum2);
    }
    
    @Test
    void testDifferentContentsDifferentChecksums() throws IOException {
        Path file1 = tempDir.resolve("file1.txt");
        Path file2 = tempDir.resolve("file2.txt");
        
        Files.writeString(file1, "Content 1");
        Files.writeString(file2, "Content 2");
        
        String checksum1 = ChecksumCalculator.calculateSHA256(file1);
        String checksum2 = ChecksumCalculator.calculateSHA256(file2);
        
        assertThat(checksum1).isNotEqualTo(checksum2);
    }
    
    @Test
    void testLargeFileChecksum() throws IOException {
        Path largeFile = tempDir.resolve("large.txt");
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100000; i++) {
            sb.append("This is line ").append(i).append(" of a large file\n");
        }
        Files.writeString(largeFile, sb.toString());
        
        String checksum = ChecksumCalculator.calculateSHA256(largeFile);
        
        assertThat(checksum)
            .isNotEmpty()
            .hasSize(64);
    }
    
    @Test
    void testVerifyChecksumCaseInsensitive() throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Case test");
        
        String checksum = ChecksumCalculator.calculateSHA256(testFile);
        String uppercase = checksum.toUpperCase();
        
        boolean result = ChecksumCalculator.verifyChecksum(testFile, uppercase);
        
        assertThat(result).isTrue();
    }
    
    @Test
    void testEmptyFileChecksum() throws IOException {
        Path emptyFile = tempDir.resolve("empty.txt");
        Files.createFile(emptyFile);
        
        String checksum = ChecksumCalculator.calculateSHA256(emptyFile);
        
        assertThat(checksum)
            .isNotEmpty()
            .hasSize(64);
        
        String emptyStringHash = ChecksumCalculator.calculateSHA256("");
        boolean result = ChecksumCalculator.verifyChecksum(emptyFile, emptyStringHash);
        
        assertThat(result).isTrue();
    }
}
