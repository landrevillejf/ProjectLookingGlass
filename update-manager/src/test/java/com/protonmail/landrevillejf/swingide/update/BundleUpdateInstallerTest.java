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
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bundle installer stages a verified {@code lg3d-<version>.zip} and hands a
 * deferred script the destructive tree replacement. Both destructive steps are
 * overridden here so the pipeline is asserted without spawning a process, exiting
 * the JVM nor touching a real installation.
 */
class BundleUpdateInstallerTest {

    @TempDir
    Path tempDir;

    private Path installRoot;
    private Path workDir;
    private Path backupDir;
    private TestableBundleUpdateInstaller installer;

    @BeforeEach
    void setUp() throws IOException {
        installRoot = tempDir.resolve("install");
        Files.createDirectories(installRoot.resolve("lib"));
        Files.writeString(installRoot.resolve("lg3d.sh"), "#!/bin/bash\nold\n");
        Files.writeString(installRoot.resolve("VERSION"), "1.0.0\n");

        workDir = tempDir.resolve("work");
        backupDir = tempDir.resolve("backups");
        installer = new TestableBundleUpdateInstaller(installRoot, workDir, backupDir, true, true);
    }

    // --- zip helpers -------------------------------------------------------

    private static void putFile(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static void putDir(ZipOutputStream zos, String name) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.closeEntry();
    }

    /** A minimal but layout-valid release bundle archive. */
    private Path makeBundleZip(Path zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            putDir(zos, "lib/");
            putFile(zos, "lib/lg3d-core.jar", "core");
            putFile(zos, "lg3d.sh", "#!/bin/bash\nnew\n");
            putFile(zos, "VERSION", "9.9.9\n");
            putFile(zos, "resources/icon.png", "png");
        }
        return zipFile;
    }

    // --- stage / extract / isZipArchive -----------------------------------

    @Test
    void stageExtractsAndValidatesABundle() throws Exception {
        Path zip = makeBundleZip(tempDir.resolve("lg3d-9.9.9.zip"));

        Path staged = installer.stage(zip);

        assertThat(InstallLocation.isBundleLayout(staged)).isTrue();
        assertThat(staged.resolve("lib/lg3d-core.jar")).exists();
        assertThat(Files.readString(staged.resolve("VERSION"))).isEqualTo("9.9.9\n");
    }

    @Test
    void stageRejectsAMissingFile() {
        assertThatThrownBy(() -> installer.stage(tempDir.resolve("absent.zip")))
            .isInstanceOf(UpdateException.class)
            .hasMessageContaining("does not exist");
    }

    @Test
    void stageRejectsANonZipArtifact() throws IOException {
        Path notZip = tempDir.resolve("update.jar");
        Files.writeString(notZip, "this is not a zip archive");

        assertThatThrownBy(() -> installer.stage(notZip))
            .isInstanceOf(UpdateException.class)
            .hasMessageContaining("not a ZIP bundle");
    }

    @Test
    void stageRejectsAnArchiveWithoutABundleLayout() throws IOException {
        Path zip = tempDir.resolve("bad.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            putFile(zos, "random.txt", "hello");
        }

        assertThatThrownBy(() -> installer.stage(zip))
            .isInstanceOf(UpdateException.class)
            .hasMessageContaining("not a valid Project Looking Glass release");
    }

    @Test
    void extractRejectsZipSlipEntries() throws IOException {
        Path zip = tempDir.resolve("slip.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            putFile(zos, "../evil.txt", "escaped");
        }
        Path dest = tempDir.resolve("dest");

        assertThatThrownBy(() -> installer.extract(zip, dest))
            .isInstanceOf(UpdateException.class)
            .hasMessageContaining("outside the target directory");
    }

    @Test
    void isZipArchiveDetectsTheMagicHeader() throws Exception {
        Path zip = makeBundleZip(tempDir.resolve("probe.zip"));
        Path text = tempDir.resolve("probe.txt");
        Files.writeString(text, "plain");

        assertThat(installer.isZipArchive(zip)).isTrue();
        assertThat(installer.isZipArchive(text)).isFalse();
    }

    // --- install (destructive steps stubbed) ------------------------------

    @Test
    void installBundleStagesBacksUpWritesScriptAndExits() throws Exception {
        Path zip = makeBundleZip(tempDir.resolve("lg3d-9.9.9.zip"));

        installer.installBundle(zip);

        assertThat(installer.exitCount).isEqualTo(1);
        assertThat(installer.launchedScripts).hasSize(1);

        Path script = installer.launchedScripts.get(0);
        String body = Files.readString(script);
        assertThat(body)
            .contains(installRoot.toString())
            .contains("sleep")
            .contains("for e in lib resources etc ext lg3d.sh README.txt VERSION")
            .contains("setsid bash \"$ROOT/lg3d.sh\"");
        if (script.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(script).isExecutable();
        }

        // A rollback snapshot directory with a restore helper was prepared.
        assertThat(backupDir).exists();
        try (Stream<Path> snapshots = Files.list(backupDir)) {
            List<Path> dirs = snapshots.filter(Files::isDirectory).toList();
            assertThat(dirs).hasSize(1);
            Path restore = dirs.get(0).resolve("restore.sh");
            assertThat(restore).exists();
            assertThat(Files.readString(restore)).contains(installRoot.toString());
        }
    }

    @Test
    void installBundleOnExitAppliesWithoutRelaunchingNorExiting() throws Exception {
        Path zip = makeBundleZip(tempDir.resolve("lg3d-9.9.9.zip"));

        installer.installBundleOnExit(zip);

        assertThat(installer.exitCount).isZero();
        assertThat(installer.launchedScripts).hasSize(1);
        String body = Files.readString(installer.launchedScripts.get(0));
        assertThat(body).contains("relaunch disabled");
        assertThat(body).doesNotContain("setsid bash");
    }

    @Test
    void installBundleWrapsAScriptLaunchFailure() throws Exception {
        Path zip = makeBundleZip(tempDir.resolve("lg3d-9.9.9.zip"));
        BundleUpdateInstaller failing = new TestableBundleUpdateInstaller(
            installRoot, workDir, backupDir, true, true) {
            @Override
            protected void launchApplyScript(Path scriptPath) throws IOException {
                throw new IOException("pkexec unavailable");
            }
        };

        assertThatThrownBy(() -> failing.installBundle(zip))
            .isInstanceOf(UpdateException.class)
            .hasMessageContaining("Bundle installation failed");
    }

    @Test
    void installBundleOnExitWrapsAScriptLaunchFailure() throws Exception {
        Path zip = makeBundleZip(tempDir.resolve("lg3d-9.9.9.zip"));
        BundleUpdateInstaller failing = new TestableBundleUpdateInstaller(
            installRoot, workDir, backupDir, false, true) {
            @Override
            protected void launchApplyScript(Path scriptPath) throws IOException {
                throw new IOException("cannot start");
            }
        };

        assertThatThrownBy(() -> failing.installBundleOnExit(zip))
            .isInstanceOf(UpdateException.class)
            .hasMessageContaining("Deferred bundle installation failed");
    }

    // --- launch command / escalation --------------------------------------

    @Test
    void launchCommandUsesPkexecForAReadOnlyRootWhenEscalationEnabled() {
        Path readOnlyRoot = tempDir.resolve("missing-root"); // does not exist -> not writable
        BundleUpdateInstaller escalating =
            new BundleUpdateInstaller(readOnlyRoot, workDir, backupDir, false, true);

        assertThat(escalating.launchCommand(Path.of("/tmp/apply.sh")))
            .containsExactly("pkexec", "bash", "/tmp/apply.sh");
    }

    @Test
    void launchCommandUsesPlainBashForAWritableRoot() {
        assertThat(installer.launchCommand(Path.of("/tmp/apply.sh")))
            .containsExactly("bash", "/tmp/apply.sh");
    }

    @Test
    void launchCommandUsesPlainBashWhenEscalationDisabled() {
        Path readOnlyRoot = tempDir.resolve("missing-root");
        BundleUpdateInstaller noEscalation =
            new BundleUpdateInstaller(readOnlyRoot, workDir, backupDir, false, false);

        assertThat(noEscalation.launchCommand(Path.of("/tmp/apply.sh")))
            .containsExactly("bash", "/tmp/apply.sh");
    }

    // --- accessors ---------------------------------------------------------

    @Test
    void accessorsExposeTheInjectedConfiguration() {
        assertThat(installer.getInstallRoot()).isEqualTo(installRoot);
        assertThat(installer.getWorkDir()).isEqualTo(workDir);
        assertThat(installer.getBackupDir()).isEqualTo(backupDir);
        assertThat(installer.isRelaunchEnabled()).isTrue();
        assertThat(installer.isEscalationEnabled()).isTrue();
    }

    @Test
    void defaultConstructorTargetsTheUserHomeAndSafeDefaults() {
        BundleUpdateInstaller defaulted = new BundleUpdateInstaller(installRoot);

        Path home = Path.of(System.getProperty("user.home"));
        assertThat(defaulted.getWorkDir()).isEqualTo(home.resolve(".lg3d/updates"));
        assertThat(defaulted.getBackupDir()).isEqualTo(home.resolve(".lg3d/backups/bundles"));
        assertThat(defaulted.isRelaunchEnabled()).isFalse();
        assertThat(defaulted.isEscalationEnabled()).isTrue();
    }

    @Test
    void nullWorkAndBackupDirsFallBackOnTheDefaults() {
        BundleUpdateInstaller partial =
            new BundleUpdateInstaller(installRoot, null, null, false, false);

        Path home = Path.of(System.getProperty("user.home"));
        assertThat(partial.getWorkDir()).isEqualTo(home.resolve(".lg3d/updates"));
        assertThat(partial.getBackupDir()).isEqualTo(home.resolve(".lg3d/backups/bundles"));
    }

    /** Installer recording the destructive steps instead of performing them. */
    private static class TestableBundleUpdateInstaller extends BundleUpdateInstaller {

        private final List<Path> launchedScripts = new ArrayList<>();
        private int exitCount;

        TestableBundleUpdateInstaller(Path installRoot, Path workDir, Path backupDir,
                                      boolean relaunch, boolean escalation) {
            super(installRoot, workDir, backupDir, relaunch, escalation);
        }

        @Override
        protected void launchApplyScript(Path scriptPath) throws IOException {
            launchedScripts.add(scriptPath);
        }

        @Override
        protected void exitForRestart() {
            exitCount++;
        }
    }
}
