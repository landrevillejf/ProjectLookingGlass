package com.protonmail.landrevillejf.swingide.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;

@Slf4j
public class UpdateRepository {
    
    /**
     * Fallback endpoint used when {@code update.url} is not set in
     * {@code update-config.properties}.
     * <p>
     * Points at the GitHub Releases latest-asset redirect of this repository so
     * the URL always tracks the most recent tag published by
     * {@code .github/workflows/release.yml}. Because the repository is private,
     * an unauthenticated request receives 401/404; {@link UpdateChecker} treats
     * that as a quiet skip rather than a fatal error.
     * </p>
     */
    private static final String DEFAULT_UPDATE_URL =
        "https://github.com/landrevillejf/ProjectLookingGlass/releases/latest/download/version.json";
    
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String updateUrl;

    /**
     * Bearer token attached to same-host requests, needed because the release
     * assets of a private repository are not served anonymously. {@code null}
     * keeps the requests anonymous.
     */
    private final String authToken;
    
    public UpdateRepository() {
        this(null, null);
    }
    
    public UpdateRepository(String updateUrl) {
        this(updateUrl, null);
    }

    /**
     * @param updateUrl metadata endpoint; {@code null} or blank selects
     *                  {@link #DEFAULT_UPDATE_URL}
     * @param authToken bearer token for a private release host, may be
     *                  {@code null}
     */
    public UpdateRepository(String updateUrl, String authToken) {
        this.updateUrl = updateUrl == null || updateUrl.isBlank() ? DEFAULT_UPDATE_URL : updateUrl;
        this.authToken = authToken;
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        this.objectMapper = new ObjectMapper();
    }
    
    public UpdateInfo fetchLatestVersion() throws UpdateException {
        try {
            log.debug("Fetching update metadata from: {}", updateUrl);

            HttpResponse<InputStream> response = UpdateHttpSupport.get(
                httpClient, URI.create(updateUrl), authToken, Duration.ofSeconds(30)
            );

            if (response.statusCode() != 200) {
                response.body().close();
                throw new UpdateServerUnavailableException(response.statusCode(), updateUrl);
            }

            String body = readAndClose(response.body());
            return parseUpdateInfo(body);

        } catch (IOException e) {
            throw new UpdateException("Failed to fetch update metadata", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException("Update check interrupted", e);
        }
    }

    private static String readAndClose(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    
    private UpdateInfo parseUpdateInfo(String json) throws UpdateException {
        try {
            JsonNode root = objectMapper.readTree(json);
            
            String version = root.path("latestVersion").asText();
            String releaseDateStr = root.path("releaseDate").asText();
            Instant releaseDate = Instant.parse(releaseDateStr);
            
            JsonNode platforms = root.path("platforms").path("universal");
            String downloadUrl = platforms.path("url").asText();
            long size = platforms.path("size").asLong();
            String sha256 = platforms.path("sha256").asText();
            String signatureUrl = platforms.path("signatureUrl").asText();
            JsonNode signingKeyNode = platforms.path("signingKeyId");
            String signingKeyId = signingKeyNode.isMissingNode() || signingKeyNode.isNull()
                ? null
                : signingKeyNode.asText();
            
            boolean critical = root.path("critical").asBoolean(false);
            String changelogUrl = root.path("changelog").asText();
            String minJavaVersion = root.path("minJavaVersion").isEmpty() ? "21" : root.path("minJavaVersion").asText();
            boolean breakingChanges = root.path("breakingChanges").asBoolean(false);
            
            return new UpdateInfo(
                version,
                releaseDate,
                downloadUrl,
                size,
                sha256,
                signatureUrl,
                critical,
                changelogUrl,
                minJavaVersion,
                breakingChanges,
                signingKeyId
            );
            
        } catch (Exception e) {
            throw new UpdateException("Failed to parse update metadata", e);
        }
    }
    
    /**
     * Downloads a small artefact (typically the detached {@code .asc} signature)
     * to a local file.
     *
     * @param url    resource to fetch
     * @param target file receiving the bytes
     * @return {@code true} when the resource was fetched and written
     */
    public boolean downloadTo(String url, Path target) {
        if (url == null || url.isBlank()) {
            return false;
        }

        try {
            HttpResponse<InputStream> response = UpdateHttpSupport.get(
                httpClient, URI.create(url), authToken, Duration.ofSeconds(30)
            );

            if (response.statusCode() != 200) {
                response.body().close();
                log.warn("HTTP error {} fetching {}", response.statusCode(), url);
                return false;
            }

            try (InputStream in = response.body()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;

        } catch (IOException e) {
            log.warn("Could not download {}: {}", url, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Download of {} interrupted", url);
            return false;
        }
    }

    public String fetchChangelog(String changelogUrl) throws UpdateException {
        try {
            HttpResponse<InputStream> response = UpdateHttpSupport.get(
                httpClient, URI.create(changelogUrl), authToken, Duration.ofSeconds(30)
            );

            if (response.statusCode() != 200) {
                response.body().close();
                throw new UpdateException(
                    "HTTP error fetching changelog: " + response.statusCode()
                );
            }

            return readAndClose(response.body());

        } catch (IOException e) {
            throw new UpdateException("Failed to fetch changelog", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException("Changelog fetch interrupted", e);
        }
    }
}
