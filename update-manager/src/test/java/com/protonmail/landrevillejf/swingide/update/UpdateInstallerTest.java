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
package com.protonmail.landrevillejf.swingide.update;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * The installer ends every successful run by launching a platform script and
 * exiting the JVM. Both steps are overridden here so the whole pipeline can be
 * asserted without killing the test JVM nor touching the real
 * {@code ~/.swing-ide} directory.
 */
class UpdateInstallerTest {

    @TempDir
    Path tempDir;

    private Path backupDir;
    private Path scriptDir;
    private Path currentJarFile;
    private Path newJarFile;
    private UpdateBackupManager backupManager;
    private TestableUpdateInstaller installer;

    @BeforeEach
    void setUp() throws IOException {
        backupDir = tempDir.resolve("backups");
        scriptDir = tempDir.resolve("updates");
        newJarFile = tempDir.resolve("swing-ide-new.jar");
        Files.writeString(newJarFile, "New JAR content");

        // JarLocator returns the classes directory when running from an IDE, which
        // cannot be archived; the installer therefore receives a real JAR file.
        currentJarFile = tempDir.resolve("swing-ide.jar");
        Files.writeString(currentJarFile, "Current JAR content");

        backupManager = new UpdateBackupManager(backupDir);
        installer = new TestableUpdateInstaller(backupManager, scriptDir, currentJarFile);
    }

    @Test
    void testInstallUpdateBacksUpWritesScriptAndExits() throws Exception {
        installer.installUpdate(newJarFile);

        assertThat(installer.exitCount).isEqualTo(1);
        assertThat(installer.launchedScripts).hasSize(1);

        Path script = installer.launchedScripts.get(0);
        assertThat(script).exists();
        assertThat(script.getParent()).isEqualTo(scriptDir);
        assertThat(Files.readString(script)).contains(newJarFile.toString());

        if (script.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(script).isExecutable();
        }

        List<Path> backups = backupManager.listBackups();
        assertThat(backups).hasSize(1);
        assertThat(Files.readString(backups.get(0))).isEqualTo("Current JAR content");
    }

    @Test
    void testInstallUpdateReusesProvidedRollbackArchive() throws Exception {
        Path existingBackup = tempDir.resolve("rollback.jar");
        Files.writeString(existingBackup, "Archived JAR content");

        installer.installUpdate(newJarFile, existingBackup);

        assertThat(installer.exitCount).isEqualTo(1);
        assertThat(backupDir).doesNotExist();
    }

    @Test
    void testInstallUpdateFallsBackToItsOwnBackupWhenArchiveIsMissing() throws Exception {
        installer.installUpdate(newJarFile, tempDir.resolve("absent-rollback.jar"));

        assertThat(installer.exitCount).isEqualTo(1);
        assertThat(backupManager.listBackups()).hasSize(1);
    }

    @Test
    void testInstallUpdateThrowsWhenNewJarNotFound() {
        Path nonexistent = tempDir.resolve("nonexistent.jar");

        assertThatThrownBy(() -> installer.installUpdate(nonexistent))
            .isInstanceOf(UpdateException.class)
            .hasMessageContaining("does not exist");

        assertThat(installer.exitCount).isZero();
        assertThat(installer.launchedScripts).isEmpty();
    }

    @Test
    void testInstallUpdateWithVerificationThrowsOnChecksumMismatch() {
        String wrongChecksum = "0".repeat(64);

        assertThatThrownBy(() -> installer.installUpdateWithVerification(newJarFile, wrongChecksum))
            .isInstanceOf(UpdateException.class)
            .hasMessageContaining("verification");

        assertThat(installer.exitCount).isZero();
        assertThat(installer.launchedScripts).isEmpty();
    }

    @Test
    void testInstallUpdateWithVerificationInstallsOnCorrectChecksum() throws Exception {
        String correctChecksum = ChecksumCalculator.calculateSHA256(newJarFile);

        installer.installUpdateWithVerification(newJarFile, correctChecksum);

        assertThat(installer.exitCount).isEqualTo(1);
        assertThat(installer.launchedScripts).hasSize(1);
    }

    @Test
    void testScriptLaunchFailureIsReported() {
        UpdateInstaller failing = new TestableUpdateInstaller(backupManager, scriptDir, currentJarFile) {
            @Override
            protected void launchUpdateScript(Path scriptPath) throws IOException {
                throw new IOException("script cannot start");
            }
        };

        assertThatThrownBy(() -> failing.installUpdate(newJarFile))
            .isInstanceOf(UpdateException.class)
            .hasMessageContaining("Update installation failed");
    }

    @Test
    void testInstallUpdateFailsWhenTheRunningJarCannotBeArchived() {
        // The classes directory produced by JarLocator is not a regular file.
        TestableUpdateInstaller runningFromClasses =
            new TestableUpdateInstaller(backupManager, scriptDir, tempDir.resolve("classes"));

        assertThatThrownBy(() -> runningFromClasses.installUpdate(newJarFile))
            .isInstanceOf(UpdateException.class)
            .hasMessageContaining("not a regular file");

        assertThat(runningFromClasses.launchedScripts).isEmpty();
    }

    @Test
    void testAccessors() {
        assertThat(installer.getBackupManager()).isSameAs(backupManager);
        assertThat(installer.getDownloadDir()).isEqualTo(scriptDir);
        assertThat(installer.getCurrentJarPath()).isEqualTo(currentJarFile);
    }

    @Test
    void testTwoArgConstructorFallsBackToTheRunningJar() {
        UpdateInstaller inferred = new TestableUpdateInstaller(backupManager, scriptDir);

        assertThat(inferred.getCurrentJarPath()).isEqualTo(JarLocator.getCurrentJarPath());
    }

    @Test
    void testDefaultConstructorTargetsTheUserHome() {
        UpdateInstaller defaultInstaller = new UpdateInstaller();

        assertThat(defaultInstaller.getDownloadDir())
            .isEqualTo(Path.of(System.getProperty("user.home"), ".lg3d", "updates"));
        assertThat(defaultInstaller.getBackupManager()).isNotNull();
    }

    /**
     * Installer recording the destructive steps instead of performing them.
     */
    private static class TestableUpdateInstaller extends UpdateInstaller {

        private final List<Path> launchedScripts = new ArrayList<>();
        private int exitCount;

        TestableUpdateInstaller(UpdateBackupManager backupManager, Path downloadDir) {
            super(backupManager, downloadDir);
        }

        TestableUpdateInstaller(UpdateBackupManager backupManager, Path downloadDir, Path currentJarPath) {
            super(backupManager, downloadDir, currentJarPath);
        }

        @Override
        protected void launchUpdateScript(Path scriptPath) throws IOException {
            launchedScripts.add(scriptPath);
        }

        @Override
        protected void exitForRestart() {
            exitCount++;
        }
    }
}
