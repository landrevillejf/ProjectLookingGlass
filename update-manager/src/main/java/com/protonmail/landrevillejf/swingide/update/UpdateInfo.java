package com.protonmail.landrevillejf.swingide.update;

import lombok.Value;

import java.time.Instant;

@Value
public class UpdateInfo {
    String version;
    Instant releaseDate;
    String downloadUrl;
    long size;
    String sha256;
    String signatureUrl;
    boolean critical;
    String changelogUrl;
    String minJavaVersion;
    boolean breakingChanges;

    /**
     * Identifier of the PGP key the release JAR was signed with, as published in
     * the {@code signingKeyId} metadata field. May be {@code null} when the
     * server does not advertise one, in which case the configured default key is
     * used (see {@code update.signature.key.id}).
     */
    String signingKeyId;

    /**
     * Backward compatible constructor for callers that do not advertise a
     * signing key. The signature verification then falls back on the key id
     * configured locally.
     */
    public UpdateInfo(String version,
                      Instant releaseDate,
                      String downloadUrl,
                      long size,
                      String sha256,
                      String signatureUrl,
                      boolean critical,
                      String changelogUrl,
                      String minJavaVersion,
                      boolean breakingChanges) {
        this(version, releaseDate, downloadUrl, size, sha256, signatureUrl,
            critical, changelogUrl, minJavaVersion, breakingChanges, null);
    }

    /**
     * Full constructor including the PGP signing key id.
     */
    public UpdateInfo(String version,
                      Instant releaseDate,
                      String downloadUrl,
                      long size,
                      String sha256,
                      String signatureUrl,
                      boolean critical,
                      String changelogUrl,
                      String minJavaVersion,
                      boolean breakingChanges,
                      String signingKeyId) {
        this.version = version;
        this.releaseDate = releaseDate;
        this.downloadUrl = downloadUrl;
        this.size = size;
        this.sha256 = sha256;
        this.signatureUrl = signatureUrl;
        this.critical = critical;
        this.changelogUrl = changelogUrl;
        this.minJavaVersion = minJavaVersion;
        this.breakingChanges = breakingChanges;
        this.signingKeyId = signingKeyId;
    }

    public static UpdateInfo fromJson(String json) {
        // TODO: Implement JSON parsing using Jackson or similar
        // For now, return a placeholder
        return new UpdateInfo(
            "0.0.0",
            Instant.now(),
            "",
            0,
            "",
            "",
            false,
            "",
            "21",
            false
        );
    }

    public String getFormattedSize() {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }
}
