package com.protonmail.landrevillejf.swingide.update;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class UpdateDownloader {
    
    private static final int BUFFER_SIZE = 8192;
    
    private final ExecutorService downloadExecutor;
    private final Path downloadDir;
    private final HttpClient httpClient;

    /**
     * Bearer token attached to same-host requests so a private release host
     * serves the artefact; {@code null} keeps the download anonymous.
     */
    private final String authToken;
    
    public UpdateDownloader() {
        this(getDefaultDownloadDir(), null);
    }
    
    /**
     * Creates a downloader storing the artefacts in a dedicated directory.
     *
     * @param downloadDir directory receiving the downloaded JARs
     */
    public UpdateDownloader(Path downloadDir) {
        this(downloadDir, null);
    }

    /**
     * Creates a downloader storing the artefacts in a dedicated directory and
     * authenticating against a private release host.
     *
     * @param downloadDir directory receiving the downloaded JARs
     * @param authToken   bearer token, may be {@code null}
     */
    public UpdateDownloader(Path downloadDir, String authToken) {
        this.downloadExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "UpdateDownloader");
            thread.setDaemon(true);
            return thread;
        });
        this.downloadDir = downloadDir != null ? downloadDir : getDefaultDownloadDir();
        this.authToken = authToken;
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }
    
    /**
     * Directory the downloaded artefacts are written to.
     */
    public Path getDownloadDir() {
        return downloadDir;
    }
    
    public CompletableFuture<DownloadResult> downloadUpdate(
        String url,
        ProgressCallback callback
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(downloadDir);
                
                String fileName = url.substring(url.lastIndexOf('/') + 1);
                Path targetFile = downloadDir.resolve(fileName);
                
                log.info("Downloading update from: {}", url);
                log.info("Target file: {}", targetFile);
                
                downloadWithProgress(url, targetFile, callback);
                
                return new DownloadResult(
                    true,
                    targetFile,
                    "Download completed successfully"
                );
                
            } catch (IOException e) {
                log.error("Download failed", e);
                return new DownloadResult(
                    false,
                    null,
                    "Download failed: " + e.getMessage()
                );
            }
        }, downloadExecutor);
    }
    
    private void downloadWithProgress(
        String url,
        Path target,
        ProgressCallback callback
    ) throws IOException {
        try {
            HttpResponse<InputStream> response = UpdateHttpSupport.get(
                httpClient, URI.create(url), authToken, Duration.ofMinutes(10)
            );
            
            if (response.statusCode() != 200) {
                response.body().close();
                throw new IOException(
                    "HTTP error downloading file: " + response.statusCode()
                );
            }
            
            long contentLength = response.headers()
                .firstValueAsLong("Content-Length")
                .orElse(-1L);
            
            try (InputStream is = response.body()) {
                downloadWithStreamProgress(is, target, contentLength, callback);
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }
    
    private void downloadWithStreamProgress(
        InputStream is,
        Path target,
        long contentLength,
        ProgressCallback callback
    ) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        long totalBytesRead = 0;
        
        try (var os = Files.newOutputStream(target)) {
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
                
                if (callback != null && contentLength > 0) {
                    int progress = (int) ((totalBytesRead * 100) / contentLength);
                    String status = String.format(
                        "Downloading: %s / %s",
                        formatBytes(totalBytesRead),
                        formatBytes(contentLength)
                    );
                    callback.onProgress(progress, status);
                }
            }
        }
        
        log.info("Download completed: {} bytes downloaded", totalBytesRead);
    }
    
    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
    
    public CompletableFuture<DownloadResult> downloadAndVerify(
        String url,
        String expectedSha256,
        ProgressCallback callback
    ) {
        return downloadUpdate(url, callback)
            .thenApply(result -> {
                if (!result.isSuccess()) {
                    return result;
                }
                
                log.info("Verifying downloaded file: {}", result.getFile());
                
                if (!ChecksumCalculator.verifyChecksum(result.getFile(), expectedSha256)) {
                    log.error("Checksum verification failed for: {}", result.getFile());
                    return new DownloadResult(
                        false,
                        result.getFile(),
                        "Checksum verification failed"
                    );
                }
                
                log.info("Download verification successful");
                return new DownloadResult(
                    true,
                    result.getFile(),
                    "Download verified successfully"
                );
            });
    }
    
    public void shutdown() {
        downloadExecutor.shutdown();
        try {
            if (!downloadExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                downloadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            downloadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    private static Path getDefaultDownloadDir() {
        String userHome = System.getProperty("user.home");
        return Path.of(userHome, ".lg3d", "updates");
    }
    
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int percent, String status);
    }
    
    @Value
    public static class DownloadResult {
        boolean success;
        Path file;
        String message;
    }
}
