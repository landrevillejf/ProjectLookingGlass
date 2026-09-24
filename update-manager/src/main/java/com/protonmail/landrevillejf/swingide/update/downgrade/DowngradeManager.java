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
package com.protonmail.landrevillejf.swingide.update.downgrade;

import com.protonmail.landrevillejf.swingide.update.ChecksumCalculator;
import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Manages version downgrade functionality with backup restoration and signature verification.
 * Allows users to roll back to previous versions with security validation.
 * <p>
 * Every restorable version lives in its own directory holding the archived JAR
 * ({@code app.jar}) and a {@code metadata.properties} file describing the
 * version, the backup date and the SHA-256 checksum of the archive.
 * </p>
 */
@Slf4j
public class DowngradeManager {

    /** Name of the archived JAR inside a backup directory. */
    public static final String BACKUP_JAR_NAME = "app.jar";

    /** Name of the metadata file inside a backup directory. */
    public static final String METADATA_FILE_NAME = "metadata.properties";

    private static final DateTimeFormatter DATE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

    private final Path backupDirectory;

    public DowngradeManager(Path backupDirectory) {
        this.backupDirectory = backupDirectory;
    }

    /**
     * Backup directory holding the versioned archives. It is created on demand so
     * constructing the manager never touches the filesystem.
     */
    public Path getBackupDirectory() {
        return backupDirectory;
    }

    private void ensureBackupDirectory() throws IOException {
        if (!Files.exists(backupDirectory)) {
            Files.createDirectories(backupDirectory);
            log.info("Created backup directory: {}", backupDirectory);
        }
    }

    /**
     * Archive the given JAR so it can be restored later.
     *
     * @param version     version the JAR belongs to
     * @param jarPath     JAR to archive
     * @param buildNumber optional build identifier stored in the metadata
     * @return the registered version
     * @throws DowngradeException when the archive cannot be created
     */
    public DowngradeVersion registerBackup(String version, Path jarPath, String buildNumber)
        throws DowngradeException {

        if (version == null || version.isBlank()) {
            throw new DowngradeException("Cannot register a backup without a version");
        }
        if (jarPath == null || !Files.isRegularFile(jarPath)) {
            throw new DowngradeException("JAR to archive does not exist: " + jarPath);
        }

        try {
            ensureBackupDirectory();

            LocalDateTime now = LocalDateTime.now();
            String timestamp = now.format(DATE_FORMATTER);
            Path target = createUniqueDirectory(version + "_" + timestamp);

            Path archivedJar = target.resolve(BACKUP_JAR_NAME);
            Files.copy(jarPath, archivedJar);

            String checksum = ChecksumCalculator.calculateSHA256(archivedJar);

            Properties props = new Properties();
            props.setProperty("version", version);
            props.setProperty("backup_date", timestamp);
            props.setProperty("backup_type", "update");
            props.setProperty("checksum_sha256", checksum);
            if (buildNumber != null && !buildNumber.isBlank()) {
                props.setProperty("build_number", buildNumber);
            }
            writeMetadata(target, props, "Update backup for version " + version);

            log.info("Registered downgrade backup for version {} at {}", version, target);

            return new DowngradeVersion(version, now, buildNumber, checksum, target);

        } catch (IOException e) {
            throw new DowngradeException("Failed to register backup for version " + version, e);
        }
    }

    /**
     * Get list of available previous versions for downgrade.
     */
    public List<DowngradeVersion> getAvailableVersions() {
        if (!Files.isDirectory(backupDirectory)) {
            return Collections.emptyList();
        }

        try (var directories = Files.list(backupDirectory)) {
            List<DowngradeVersion> versions = new ArrayList<>();
            directories.filter(Files::isDirectory).forEach(path -> {
                Optional<DowngradeVersion> parsed = parseBackupMetadata(path);
                if (parsed.isPresent() && Files.isRegularFile(parsed.get().getJarPath())) {
                    versions.add(parsed.get());
                } else if (parsed.isPresent()) {
                    log.debug("Ignoring backup {} whose archive is missing", path);
                }
            });

            versions.sort(Comparator
                .comparing(DowngradeVersion::getBackupDate)
                .thenComparing(v -> v.getBackupPath().getFileName().toString())
                .reversed());

            return versions;

        } catch (IOException e) {
            log.error("Failed to list available versions", e);
            return Collections.emptyList();
        }
    }

    /**
     * Most recent restorable version, if any.
     */
    public Optional<DowngradeVersion> getLatestVersion() {
        return getAvailableVersions().stream().findFirst();
    }

    /**
     * Parse backup metadata from directory structure.
     */
    private Optional<DowngradeVersion> parseBackupMetadata(Path backupPath) {
        Path metadataFile = backupPath.resolve(METADATA_FILE_NAME);
        if (!Files.exists(metadataFile)) {
            return Optional.empty();
        }

        Properties props = new Properties();
        try (InputStream is = new FileInputStream(metadataFile.toFile())) {
            props.load(is);
        } catch (IOException e) {
            log.debug("Failed to read backup metadata from {}", backupPath, e);
            return Optional.empty();
        }

        String version = props.getProperty("version");
        String backupDate = props.getProperty("backup_date");
        String buildNumber = props.getProperty("build_number");
        String checksumSha256 = props.getProperty("checksum_sha256");

        if (version == null || backupDate == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(new DowngradeVersion(
                version,
                LocalDateTime.parse(backupDate, DATE_FORMATTER),
                buildNumber,
                checksumSha256,
                backupPath
            ));
        } catch (DateTimeParseException e) {
            log.debug("Unparsable backup date '{}' in {}", backupDate, backupPath);
            return Optional.empty();
        }
    }

    /**
     * Restore a previous version from backup.
     */
    public void restoreVersion(String targetVersion, Path currentJarPath) throws DowngradeException {
        restoreVersion(targetVersion, currentJarPath, null);
    }

    /**
     * Restore a previous version from backup, archiving the running version first.
     *
     * @param targetVersion  version to restore
     * @param currentJarPath JAR replaced by the restoration
     * @param currentVersion version currently running, may be {@code null}
     * @throws DowngradeException when the version is unknown or its archive is corrupt
     */
    public void restoreVersion(String targetVersion, Path currentJarPath, String currentVersion)
        throws DowngradeException {

        DowngradeVersion version = getAvailableVersions().stream()
            .filter(v -> v.getVersion().equals(targetVersion))
            .findFirst()
            .orElseThrow(() -> new DowngradeException("Version not found in backups: " + targetVersion));

        restoreVersion(version, currentJarPath, currentVersion);
    }

    /**
     * Restore a specific DowngradeVersion.
     */
    public void restoreVersion(DowngradeVersion version, Path currentJarPath) throws DowngradeException {
        restoreVersion(version, currentJarPath, null);
    }

    /**
     * Restore a specific DowngradeVersion, archiving the running version first.
     */
    public void restoreVersion(DowngradeVersion version, Path currentJarPath, String currentVersion)
        throws DowngradeException {

        Path backupJarPath = version.getJarPath();
        if (!Files.isRegularFile(backupJarPath)) {
            throw new DowngradeException("Backup JAR not found: " + backupJarPath);
        }

        if (!verifyBackupIntegrity(version)) {
            throw new DowngradeException(
                "Backup integrity check failed for version " + version.getVersion()
            );
        }

        try {
            createPreDowngradeBackup(currentJarPath, currentVersion, version.getVersion());

            log.info("Restoring version {} from backup", version.getVersion());
            Files.copy(backupJarPath, currentJarPath, StandardCopyOption.REPLACE_EXISTING);

            log.info("Successfully restored version {}", version.getVersion());

        } catch (IOException e) {
            throw new DowngradeException("Failed to restore version: " + version.getVersion(), e);
        }
    }

    /**
     * Create backup of current version before downgrade.
     */
    private void createPreDowngradeBackup(Path jarPath, String currentVersion, String downgradingToVersion) {
        if (jarPath == null || !Files.isRegularFile(jarPath)) {
            log.debug("No current JAR to archive before downgrade: {}", jarPath);
            return;
        }

        Path preDowngradeBackup = null;
        try {
            ensureBackupDirectory();

            String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
            preDowngradeBackup = createUniqueDirectory("pre-downgrade_" + timestamp);

            Path backupJarPath = preDowngradeBackup.resolve(BACKUP_JAR_NAME);
            Files.copy(jarPath, backupJarPath);

            Properties props = new Properties();
            if (currentVersion != null && !currentVersion.isBlank()) {
                props.setProperty("version", currentVersion);
            }
            props.setProperty("downgrade_to_version", downgradingToVersion);
            props.setProperty("backup_date", timestamp);
            props.setProperty("backup_type", "pre-downgrade");
            props.setProperty("checksum_sha256", ChecksumCalculator.calculateSHA256(backupJarPath));
            writeMetadata(preDowngradeBackup, props, "Pre-downgrade backup");

            log.info("Created pre-downgrade backup at {}", preDowngradeBackup);

        } catch (IOException e) {
            log.warn("Failed to create pre-downgrade backup", e);
            deleteQuietly(preDowngradeBackup);
        }
    }

    /**
     * Creates a backup directory, appending a counter when several archives are
     * registered within the same millisecond.
     */
    private Path createUniqueDirectory(String prefix) throws IOException {
        Path candidate = backupDirectory.resolve(prefix);
        int suffix = 1;

        while (Files.exists(candidate)) {
            candidate = backupDirectory.resolve(prefix + "-" + suffix);
            suffix++;
        }

        Files.createDirectory(candidate);
        return candidate;
    }

    private void writeMetadata(Path directory, Properties props, String comment) throws IOException {
        Path metadataFile = directory.resolve(METADATA_FILE_NAME);
        try (OutputStream os = new FileOutputStream(metadataFile.toFile())) {
            props.store(os, comment);
        }
    }

    /**
     * Verify backup integrity before restoration.
     */
    public boolean verifyBackupIntegrity(String version) throws DowngradeException {
        DowngradeVersion backupVersion = getAvailableVersions().stream()
            .filter(v -> v.getVersion().equals(version))
            .findFirst()
            .orElseThrow(() -> new DowngradeException("Version not found: " + version));

        return verifyBackupIntegrity(backupVersion);
    }

    /**
     * Verify backup integrity using the stored SHA-256 checksum.
     *
     * @param version the archived version to check
     * @return {@code true} when the archive matches its recorded checksum
     * @throws DowngradeException when the archive is missing or cannot be read
     */
    public boolean verifyBackupIntegrity(DowngradeVersion version) throws DowngradeException {
        Path backupJarPath = version.getJarPath();

        if (!Files.isRegularFile(backupJarPath)) {
            throw new DowngradeException("Backup JAR not found: " + backupJarPath);
        }

        String storedChecksum = version.getChecksumSha256();
        if (storedChecksum == null || storedChecksum.isBlank()) {
            log.warn("No stored checksum for version {}, integrity cannot be verified",
                version.getVersion());
            return false;
        }

        try {
            String actualChecksum = ChecksumCalculator.calculateSHA256(backupJarPath);
            boolean valid = actualChecksum.equalsIgnoreCase(storedChecksum);

            if (!valid) {
                log.error("Checksum mismatch for backup of version {}: expected {}, got {}",
                    version.getVersion(), storedChecksum, actualChecksum);
            } else {
                log.info("Backup integrity verified for version {}", version.getVersion());
            }

            return valid;

        } catch (IOException e) {
            throw new DowngradeException("Failed to verify backup integrity", e);
        }
    }

    /**
     * Delete every backup archive recorded for a version.
     */
    public boolean deleteBackup(String version) {
        List<DowngradeVersion> matches = getAvailableVersions().stream()
            .filter(v -> v.getVersion().equals(version))
            .toList();

        if (matches.isEmpty()) {
            return false;
        }

        boolean allDeleted = true;
        for (DowngradeVersion match : matches) {
            try {
                deleteDirectory(match.getBackupPath());
                log.info("Deleted backup for version {} at {}", version, match.getBackupPath());
            } catch (IOException e) {
                log.error("Failed to delete backup {} for version {}", match.getBackupPath(), version, e);
                allDeleted = false;
            }
        }

        return allDeleted;
    }

    /**
     * Get total backup storage size.
     */
    public long getBackupStorageSize() {
        if (!Files.isDirectory(backupDirectory)) {
            return 0;
        }

        try (var paths = Files.walk(backupDirectory)) {
            return paths
                .filter(Files::isRegularFile)
                .mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException e) {
                        log.debug("Cannot read the size of {}", path, e);
                        return 0;
                    }
                })
                .sum();
        } catch (IOException e) {
            log.error("Failed to calculate backup storage size", e);
            return 0;
        }
    }

    /**
     * Cleanup old backups, keeping only the most recent N versions.
     */
    public void cleanupOldBackups(int keepVersions) {
        try {
            List<DowngradeVersion> versions = getAvailableVersions();
            if (versions.size() > keepVersions) {
                versions.subList(Math.max(0, keepVersions), versions.size())
                    .forEach(v -> deleteDirectoryQuietly(v.getBackupPath()));
            }
        } catch (RuntimeException e) {
            log.error("Failed to cleanup old backups", e);
        }
    }

    private void deleteDirectoryQuietly(Path path) {
        try {
            deleteDirectory(path);
            log.debug("Removed outdated backup {}", path);
        } catch (IOException e) {
            log.warn("Failed to remove outdated backup {}", path, e);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            deleteDirectory(path);
        } catch (IOException e) {
            log.debug("Failed to remove incomplete backup {}", path, e);
        }
    }

    /**
     * Recursively delete directory.
     */
    private void deleteDirectory(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }

        try (var walked = Files.walk(path)) {
            List<Path> toDelete = walked.sorted(Comparator.reverseOrder()).toList();
            for (Path item : toDelete) {
                try {
                    Files.delete(item);
                } catch (IOException e) {
                    log.warn("Failed to delete {}", item, e);
                    throw e;
                }
            }
        }
    }

    /**
     * Data class for available downgrade versions.
     */
    public static class DowngradeVersion {
        private final String version;
        private final LocalDateTime backupDate;
        private final String buildNumber;
        private final String checksumSha256;
        private final Path backupPath;

        public DowngradeVersion(String version, LocalDateTime backupDate, String buildNumber,
                              String checksumSha256, Path backupPath) {
            this.version = version;
            this.backupDate = backupDate;
            this.buildNumber = buildNumber;
            this.checksumSha256 = checksumSha256;
            this.backupPath = backupPath;
        }

        public String getVersion() { return version; }
        public LocalDateTime getBackupDate() { return backupDate; }
        public String getBuildNumber() { return buildNumber; }
        public String getChecksumSha256() { return checksumSha256; }
        public Path getBackupPath() { return backupPath; }

        /**
         * Archived JAR restored by a downgrade.
         */
        public Path getJarPath() { return backupPath.resolve(BACKUP_JAR_NAME); }

        @Override
        public String toString() {
            return version + " (backed up on " + backupDate.format(DATE_FORMATTER) + ")";
        }
    }

    /**
     * Custom exception for downgrade operations.
     */
    public static class DowngradeException extends Exception {
        public DowngradeException(String message) {
            super(message);
        }

        public DowngradeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
