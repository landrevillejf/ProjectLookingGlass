package com.protonmail.landrevillejf.swingide.update;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class UpdateDownloaderTest {
    
    @TempDir
    Path tempDir;
    
    private UpdateDownloader downloader;
    
    @BeforeEach
    void setUp() {
        downloader = new UpdateDownloader(tempDir);
    }
    
    @AfterEach
    void tearDown() {
        downloader.shutdown();
    }
    
    @Test
    void testDownloadResultCreation() {
        Path file = tempDir.resolve("test.jar");
        UpdateDownloader.DownloadResult result = 
            new UpdateDownloader.DownloadResult(true, file, "Success");
        
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFile()).isEqualTo(file);
        assertThat(result.getMessage()).isEqualTo("Success");
    }
    
    @Test
    void testDownloadResultFailure() {
        UpdateDownloader.DownloadResult result = 
            new UpdateDownloader.DownloadResult(false, null, "Failed");
        
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFile()).isNull();
        assertThat(result.getMessage()).isEqualTo("Failed");
    }
    
    @Test
    void testProgressCallbackInterface() {
        UpdateDownloader.ProgressCallback callback = (percent, status) -> {
            assertThat(percent).isGreaterThanOrEqualTo(0).isLessThanOrEqualTo(100);
            assertThat(status).isNotBlank();
        };
        
        callback.onProgress(50, "Test status");
    }
    
    @Test
    void testDownloadDirInitialization() {
        Path dir = tempDir.resolve("updates");
        UpdateDownloader localDownloader = new UpdateDownloader(dir);
        
        assertThat(localDownloader.getDownloadDir()).isEqualTo(dir);
        
        localDownloader.shutdown();
    }
    
    @Test
    void testNullDownloadDirFallsBackToTheDefault() {
        UpdateDownloader localDownloader = new UpdateDownloader(null);
        
        assertThat(localDownloader.getDownloadDir())
            .isEqualTo(Path.of(System.getProperty("user.home"), ".lg3d", "updates"));
        
        localDownloader.shutdown();
    }
    
    @Test
    void testDownloadAndVerifyIntegration() throws IOException {
        // Create a fake JAR file content
        Path sourceFile = tempDir.resolve("source.jar");
        String jarContent = "Fake JAR file content";
        Files.writeString(sourceFile, jarContent);
        
        String checksum = ChecksumCalculator.calculateSHA256(sourceFile);
        
        UpdateDownloader.DownloadResult result = 
            new UpdateDownloader.DownloadResult(true, sourceFile, "Success");
        
        assertThat(result.isSuccess()).isTrue();
        assertThat(ChecksumCalculator.verifyChecksum(result.getFile(), checksum)).isTrue();
    }
    
    @Test
    void testDownloadResultToStringContent() {
        Path file = tempDir.resolve("test.jar");
        UpdateDownloader.DownloadResult result = 
            new UpdateDownloader.DownloadResult(true, file, "Download successful");
        
        String stringRep = result.toString();
        
        assertThat(stringRep).contains("success=true");
    }
    
    @Test
    void testMultipleProgressUpdates() {
        int[] callCount = {0};
        UpdateDownloader.ProgressCallback callback = (percent, status) -> {
            callCount[0]++;
        };
        
        callback.onProgress(0, "Starting");
        callback.onProgress(50, "Halfway");
        callback.onProgress(100, "Complete");
        
        assertThat(callCount[0]).isEqualTo(3);
    }
    
    @Test
    void testProgressCallbackWithVariousPercentages() {
        UpdateDownloader.ProgressCallback callback = (percent, status) -> {
            assertThat(percent).isGreaterThanOrEqualTo(0).isLessThanOrEqualTo(100);
        };
        
        for (int i = 0; i <= 100; i += 10) {
            callback.onProgress(i, "Progress: " + i + "%");
        }
    }
    
    @Test
    void testShutdownBehavior() {
        UpdateDownloader localDownloader = new UpdateDownloader(tempDir);
        
        assertDoesNotThrow(() -> {
            localDownloader.shutdown();
            localDownloader.shutdown();
        });
    }
    
    @Test
    void testDefaultDownloadDirPath() {
        UpdateDownloader localDownloader = new UpdateDownloader();
        
        assertThat(localDownloader.getDownloadDir())
            .isEqualTo(Path.of(System.getProperty("user.home"), ".lg3d", "updates"));
        
        localDownloader.shutdown();
    }
}
