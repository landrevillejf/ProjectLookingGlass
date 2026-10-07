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

import com.protonmail.landrevillejf.swingide.core.bus.EventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Routing between the new release-bundle installer and the legacy single-jar
 * path inside {@link UpdateService}, plus the bundle configuration accessors.
 */
class UpdateServiceBundleInstallTest {

    @TempDir
    Path tempDir;

    private static final String UPDATE_VERSION = "9.9.9";

    private UpdateService newService(Properties config) {
        UpdateRepository repository = new UpdateRepository("http://mock.local");
        return new UpdateService(repository, new EventBus(), config);
    }

    private Path makeBundleRoot(Path root) throws IOException {
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lg3d.sh"), "#!/bin/bash\n");
        Files.writeString(root.resolve("VERSION"), "1.0.0\n");
        return root;
    }

    private Path makeBundleZip(Path zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("lib/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("lib/lg3d-core.jar"));
            zos.write("core".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("lg3d.sh"));
            zos.write("#!/bin/bash\nnew\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("VERSION"));
            zos.write("9.9.9\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return zipFile;
    }

    private UpdateInfo updateFor(Path artifact) throws IOException {
        return new UpdateInfo(
            UPDATE_VERSION,
            Instant.now(),
            artifact.getFileName().toString(),
            Files.size(artifact),
            ChecksumCalculator.calculateSHA256(artifact),
            null,
            false,
            null,
            "21",
            false);
    }

    @SuppressWarnings("unchecked")
    private void seedStaged(UpdateService service, String version, Path file) throws Exception {
        Field field = UpdateService.class.getDeclaredField("stagedUpdates");
        field.setAccessible(true);
        ((Map<String, Path>) field.get(service)).put(version, file);
    }

    // --- configuration accessors ------------------------------------------

    @Test
    void bundleRelaunchDefaultsToFalseAndIsConfigurable() {
        assertThat(newService(new Properties()).isBundleRelaunchEnabled()).isFalse();

        Properties config = new Properties();
        config.setProperty(UpdateService.CONFIG_BUNDLE_RELAUNCH, "true");
        assertThat(newService(config).isBundleRelaunchEnabled()).isTrue();
    }

    @Test
    void installLocationHonoursTheConfiguredInstallDir() throws IOException {
        Path root = makeBundleRoot(tempDir.resolve("bundle"));
        Properties config = new Properties();
        config.setProperty(UpdateService.CONFIG_INSTALL_DIR, root.toString());

        UpdateService service = newService(config);

        assertThat(service.getInstallLocation().detect()).contains(root);
    }

    @Test
    void getBundleInstallerIsStableAndInjectable() throws IOException {
        Path root = makeBundleRoot(tempDir.resolve("bundle"));
        UpdateService service = newService(new Properties());

        BundleUpdateInstaller first = service.getBundleInstaller(root);
        assertThat(service.getBundleInstaller(root)).isSameAs(first);
        assertThat(first.getInstallRoot()).isEqualTo(root);

        BundleUpdateInstaller injected = new BundleUpdateInstaller(root);
        service.setBundleInstaller(injected);
        assertThat(service.getBundleInstaller(root)).isSameAs(injected);
    }

    @Test
    void setInstallLocationOverridesTheLocator() {
        UpdateService service = newService(new Properties());
        InstallLocation injected = new InstallLocation(() -> null, key -> null);

        service.setInstallLocation(injected);

        assertThat(service.getInstallLocation()).isSameAs(injected);
    }

    // --- install routing ---------------------------------------------------

    @Test
    void installNowAppliesABundleWhenRunningFromABundleRoot() throws Exception {
        Path root = makeBundleRoot(tempDir.resolve("bundle"));
        Path zip = makeBundleZip(tempDir.resolve("lg3d-9.9.9.zip"));

        UpdateService service = newService(new Properties());
        service.setInstallLocation(new InstallLocation(() -> root.resolve("lib/um.jar"), key -> null));
        RecordingBundleInstaller recorder = new RecordingBundleInstaller(root, tempDir.resolve("work"),
            tempDir.resolve("backup"), false, true);
        service.setBundleInstaller(recorder);
        seedStaged(service, UPDATE_VERSION, zip);

        service.installNow(updateFor(zip));

        assertThat(recorder.immediate).isEqualTo(1);
        assertThat(recorder.deferred).isZero();
        assertThat(recorder.launchedScripts).hasSize(1);
    }

    @Test
    void installNowFallsBackToSingleJarWhenNoBundleRootIsDetected() throws Exception {
        Path zip = makeBundleZip(tempDir.resolve("lg3d-9.9.9.zip"));
        Path root = makeBundleRoot(tempDir.resolve("bundle"));

        UpdateService service = newService(new Properties());
        // No install root detected -> the bundle path must be skipped.
        service.setInstallLocation(new InstallLocation(() -> null, key -> null));
        RecordingBundleInstaller recorder = new RecordingBundleInstaller(root, tempDir.resolve("work"),
            tempDir.resolve("backup"), false, true);
        service.setBundleInstaller(recorder);
        seedStaged(service, UPDATE_VERSION, zip);

        // The single-jar path fails in a test JVM (not running from a packaged jar),
        // which is exactly the signal that the bundle installer was bypassed.
        assertThatThrownBy(() -> service.installNow(updateFor(zip)))
            .isInstanceOf(UpdateException.class);
        assertThat(recorder.immediate).isZero();
        assertThat(recorder.launchedScripts).isEmpty();
    }

    @Test
    void installNowFallsBackToSingleJarWhenTheArtifactIsNotABundleZip() throws Exception {
        Path root = makeBundleRoot(tempDir.resolve("bundle"));
        Path plainJar = tempDir.resolve("update-9.9.9.jar");
        Files.writeString(plainJar, "not a zip archive");

        UpdateService service = newService(new Properties());
        service.setInstallLocation(new InstallLocation(() -> root.resolve("lib/um.jar"), key -> null));
        RecordingBundleInstaller recorder = new RecordingBundleInstaller(root, tempDir.resolve("work"),
            tempDir.resolve("backup"), false, true);
        service.setBundleInstaller(recorder);
        seedStaged(service, UPDATE_VERSION, plainJar);

        assertThatThrownBy(() -> service.installNow(updateFor(plainJar)))
            .isInstanceOf(UpdateException.class);
        assertThat(recorder.immediate).isZero();
        assertThat(recorder.launchedScripts).isEmpty();
    }

    /** Bundle installer recording the destructive steps instead of performing them. */
    private static class RecordingBundleInstaller extends BundleUpdateInstaller {

        private final List<Path> launchedScripts = new ArrayList<>();
        private int immediate;
        private int deferred;

        RecordingBundleInstaller(Path installRoot, Path workDir, Path backupDir,
                                 boolean relaunch, boolean escalation) {
            super(installRoot, workDir, backupDir, relaunch, escalation);
        }

        @Override
        protected void launchApplyScript(Path scriptPath) {
            launchedScripts.add(scriptPath);
        }

        @Override
        protected void exitForRestart() {
            // Counted through installBundle/installBundleOnExit below.
        }

        @Override
        public void installBundle(Path bundleZip) throws UpdateException {
            immediate++;
            super.installBundle(bundleZip);
        }

        @Override
        public void installBundleOnExit(Path bundleZip) throws UpdateException {
            deferred++;
            super.installBundleOnExit(bundleZip);
        }
    }
}
