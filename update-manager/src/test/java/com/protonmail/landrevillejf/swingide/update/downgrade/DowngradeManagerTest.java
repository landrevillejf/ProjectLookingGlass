package com.protonmail.landrevillejf.swingide.update.downgrade;

import com.protonmail.landrevillejf.swingide.update.ChecksumCalculator;
import com.protonmail.landrevillejf.swingide.update.downgrade.DowngradeManager.DowngradeException;
import com.protonmail.landrevillejf.swingide.update.downgrade.DowngradeManager.DowngradeVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.*;

class DowngradeManagerTest {

    private static final String METADATA_VERSION_KEY = "version";
    private static final String METADATA_DATE_KEY = "backup_date";

    @TempDir
    Path tempDir;

    private Path backupDirectory;
    private DowngradeManager manager;

    @BeforeEach
    void setUp() {
        backupDirectory = tempDir.resolve("versions");
        manager = new DowngradeManager(backupDirectory);
    }

    @Test
    void testConstructorHasNoFilesystemSideEffect() {
        assertThat(manager.getBackupDirectory()).isEqualTo(backupDirectory);
        assertThat(backupDirectory).doesNotExist();
        assertThat(manager.getAvailableVersions()).isEmpty();
        assertThat(manager.getLatestVersion()).isEmpty();
        assertThat(manager.getBackupStorageSize()).isZero();
    }

    @Test
    void testRegisterBackupArchivesJarAndMetadata() throws Exception {
        Path jar = createJar("1.0.0", "payload-1.0.0");

        DowngradeVersion version = manager.registerBackup("1.0.0", jar, "build-42");

        assertThat(version.getVersion()).isEqualTo("1.0.0");
        assertThat(version.getBuildNumber()).isEqualTo("build-42");
        assertThat(version.getBackupDate()).isNotNull();
        assertThat(version.getJarPath()).exists();
        assertThat(Files.readString(version.getJarPath())).isEqualTo("payload-1.0.0");
        assertThat(version.getChecksumSha256())
            .isEqualTo(ChecksumCalculator.calculateSHA256(version.getJarPath()));
        assertThat(version.getBackupPath().resolve(DowngradeManager.METADATA_FILE_NAME)).exists();
        assertThat(version.toString()).contains("1.0.0");
    }

    @Test
    void testRegisterBackupWithoutBuildNumber() throws Exception {
        DowngradeVersion version = manager.registerBackup("1.0.0", createJar("1.0.0", "payload"), null);

        assertThat(version.getBuildNumber()).isNull();
        assertThat(manager.getAvailableVersions()).hasSize(1);
    }

    @Test
    void testRegisterBackupRejectsBlankVersion() {
        assertThatThrownBy(() -> manager.registerBackup("  ", createJar("1.0.0", "payload"), null))
            .isInstanceOf(DowngradeException.class)
            .hasMessageContaining("without a version");
    }

    @Test
    void testRegisterBackupRejectsMissingJar() {
        Path missing = tempDir.resolve("missing.jar");

        assertThatThrownBy(() -> manager.registerBackup("1.0.0", missing, null))
            .isInstanceOf(DowngradeException.class)
            .hasMessageContaining("does not exist");
    }

    @Test
    void testRegisterBackupTwiceForSameVersionKeepsBothArchives() throws Exception {
        manager.registerBackup("1.0.0", createJar("1.0.0", "first"), null);
        manager.registerBackup("1.0.0", createJar("1.0.0-bis", "second"), null);

        assertThat(manager.getAvailableVersions()).hasSize(2);
        assertThat(manager.deleteBackup("1.0.0")).isTrue();
        assertThat(manager.getAvailableVersions()).isEmpty();
    }

    @Test
    void testAvailableVersionsAreSortedNewestFirst() throws Exception {
        manager.registerBackup("1.0.0", createJar("1.0.0", "a"), null);
        manager.registerBackup("1.1.0", createJar("1.1.0", "b"), null);
        manager.registerBackup("1.2.0", createJar("1.2.0", "c"), null);

        List<DowngradeVersion> versions = manager.getAvailableVersions();

        assertThat(versions)
            .extracting(DowngradeVersion::getVersion)
            .containsExactly("1.2.0", "1.1.0", "1.0.0");
        assertThat(versions).isSortedAccordingTo(
            (left, right) -> right.getBackupDate().compareTo(left.getBackupDate())
        );
        assertThat(manager.getLatestVersion())
            .isPresent()
            .get()
            .extracting(DowngradeVersion::getVersion)
            .isEqualTo("1.2.0");
    }

    @Test
    void testBackupsWithoutArchiveAreIgnored() throws Exception {
        DowngradeVersion version = manager.registerBackup("1.0.0", createJar("1.0.0", "payload"), null);
        Files.delete(version.getJarPath());

        assertThat(manager.getAvailableVersions()).isEmpty();
    }

    @Test
    void testDirectoriesWithoutMetadataAreIgnored() throws Exception {
        manager.registerBackup("1.0.0", createJar("1.0.0", "payload"), null);
        Files.createDirectories(backupDirectory.resolve("not-a-backup"));

        assertThat(manager.getAvailableVersions()).hasSize(1);
    }

    @Test
    void testVerifyBackupIntegrity() throws Exception {
        manager.registerBackup("1.0.0", createJar("1.0.0", "payload"), null);

        assertThat(manager.verifyBackupIntegrity("1.0.0")).isTrue();
    }

    @Test
    void testVerifyBackupIntegrityDetectsTampering() throws Exception {
        DowngradeVersion version = manager.registerBackup("1.0.0", createJar("1.0.0", "payload"), null);
        Files.writeString(version.getJarPath(), "tampered");

        assertThat(manager.verifyBackupIntegrity("1.0.0")).isFalse();
        assertThat(manager.verifyBackupIntegrity(version)).isFalse();
    }

    @Test
    void testVerifyBackupIntegrityWithoutStoredChecksum() throws Exception {
        writeManualBackup("manual_2026-01-01_00-00-00-000", "2026-01-01_00-00-00-000", false);

        assertThat(manager.verifyBackupIntegrity("1.0.0")).isFalse();
    }

    @Test
    void testVerifyBackupIntegrityOfUnknownVersion() {
        assertThatThrownBy(() -> manager.verifyBackupIntegrity("9.9.9"))
            .isInstanceOf(DowngradeException.class)
            .hasMessageContaining("not found");
    }

    @Test
    void testVerifyBackupIntegrityOfMissingArchive() throws Exception {
        DowngradeVersion version = manager.registerBackup("1.0.0", createJar("1.0.0", "payload"), null);
        Files.delete(version.getJarPath());

        assertThatThrownBy(() -> manager.verifyBackupIntegrity(version))
            .isInstanceOf(DowngradeException.class)
            .hasMessageContaining("Backup JAR not found");
    }

    @Test
    void testRestoreVersionReplacesCurrentJar() throws Exception {
        manager.registerBackup("1.0.0", createJar("1.0.0", "old-payload"), null);
        Path currentJar = tempDir.resolve("current.jar");
        Files.writeString(currentJar, "new-payload");

        manager.restoreVersion("1.0.0", currentJar, "2.0.0");

        assertThat(Files.readString(currentJar)).isEqualTo("old-payload");
        assertThat(manager.getAvailableVersions())
            .extracting(DowngradeVersion::getVersion)
            .contains("2.0.0");
    }

    @Test
    void testRestoreVersionWithoutCurrentJar() throws Exception {
        manager.registerBackup("1.0.0", createJar("1.0.0", "old-payload"), null);
        Path target = tempDir.resolve("restored.jar");

        manager.restoreVersion("1.0.0", target);

        assertThat(Files.readString(target)).isEqualTo("old-payload");
    }

    @Test
    void testRestoreVersionAcceptsDowngradeVersionObject() throws Exception {
        DowngradeVersion version = manager.registerBackup("1.0.0", createJar("1.0.0", "old-payload"), null);
        Path target = tempDir.resolve("restored.jar");

        manager.restoreVersion(version, target, "2.0.0");

        assertThat(Files.readString(target)).isEqualTo("old-payload");
    }

    @Test
    void testRestoreUnknownVersionFails() {
        assertThatThrownBy(() -> manager.restoreVersion("9.9.9", tempDir.resolve("current.jar"), "2.0.0"))
            .isInstanceOf(DowngradeException.class)
            .hasMessageContaining("not found in backups");
    }

    @Test
    void testRestoreCorruptedBackupFails() throws Exception {
        DowngradeVersion version = manager.registerBackup("1.0.0", createJar("1.0.0", "old-payload"), null);
        Files.writeString(version.getJarPath(), "tampered");

        assertThatThrownBy(() -> manager.restoreVersion("1.0.0", tempDir.resolve("current.jar"), "2.0.0"))
            .isInstanceOf(DowngradeException.class)
            .hasMessageContaining("integrity check failed");
    }

    @Test
    void testDeleteBackup() throws Exception {
        manager.registerBackup("1.0.0", createJar("1.0.0", "payload"), null);

        assertThat(manager.deleteBackup("1.0.0")).isTrue();
        assertThat(manager.getAvailableVersions()).isEmpty();
        assertThat(manager.deleteBackup("1.0.0")).isFalse();
    }

    @Test
    void testCleanupOldBackupsKeepsTheMostRecentOnes() throws Exception {
        manager.registerBackup("1.0.0", createJar("1.0.0", "a"), null);
        manager.registerBackup("1.1.0", createJar("1.1.0", "b"), null);
        manager.registerBackup("1.2.0", createJar("1.2.0", "c"), null);

        manager.cleanupOldBackups(1);

        assertThat(manager.getAvailableVersions())
            .extracting(DowngradeVersion::getVersion)
            .containsExactly("1.2.0");
    }

    @Test
    void testCleanupOldBackupsIsSafeWhenBelowTheThreshold() throws Exception {
        manager.registerBackup("1.0.0", createJar("1.0.0", "a"), null);

        assertThatCode(() -> manager.cleanupOldBackups(3)).doesNotThrowAnyException();
        assertThat(manager.getAvailableVersions()).hasSize(1);
    }

    @Test
    void testCleanupOldBackupsOnEmptyDirectory() {
        assertThatCode(() -> manager.cleanupOldBackups(3)).doesNotThrowAnyException();
    }

    @Test
    void testBackupStorageSizeGrowsWithArchives() throws Exception {
        assertThat(manager.getBackupStorageSize()).isZero();

        manager.registerBackup("1.0.0", createJar("1.0.0", "0123456789"), null);

        assertThat(manager.getBackupStorageSize()).isGreaterThan(10L);
    }

    private Path createJar(String name, String content) throws IOException {
        Path jars = tempDir.resolve("jars");
        Files.createDirectories(jars);

        Path jar = jars.resolve(name + ".jar");
        Files.writeString(jar, content);
        return jar;
    }

    /**
     * Writes a backup directory by hand so metadata edge cases can be exercised.
     */
    private void writeManualBackup(String directoryName, String backupDate, boolean withChecksum)
        throws IOException {

        Path directory = backupDirectory.resolve(directoryName);
        Files.createDirectories(directory);
        Path archivedJar = directory.resolve(DowngradeManager.BACKUP_JAR_NAME);
        Files.writeString(archivedJar, "manual payload");

        Properties props = new Properties();
        props.setProperty(METADATA_VERSION_KEY, "1.0.0");
        props.setProperty(METADATA_DATE_KEY, backupDate);
        if (withChecksum) {
            props.setProperty("checksum_sha256", ChecksumCalculator.calculateSHA256(archivedJar));
        }

        try (OutputStream out = Files.newOutputStream(directory.resolve(DowngradeManager.METADATA_FILE_NAME))) {
            props.store(out, "manual backup");
        }
    }
}
