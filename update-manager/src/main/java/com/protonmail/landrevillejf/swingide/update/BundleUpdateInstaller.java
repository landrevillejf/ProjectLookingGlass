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

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Applies a downloaded <em>release bundle</em> ({@code lg3d-<version>.zip}) to an
 * installed Project Looking Glass tree.
 * <p>
 * The ported {@link UpdateInstaller} replaces a single running JAR
 * ({@code cp new.jar current.jar} + {@code java -jar current.jar}), which cannot
 * update lg3d: the desktop is a multi-jar bundle launched from a classpath by
 * {@code <root>/lg3d.sh}. This installer closes that gap. It extracts the
 * verified archive into a staging directory, then hands a deferred platform
 * script the destructive work — snapshot the current managed entries
 * ({@code lib/ resources/ etc/ ext/ lg3d.sh README.txt VERSION}) into a
 * timestamped rollback directory, replace them with the staged tree, and
 * optionally relaunch the desktop.
 * </p>
 * <p>
 * The replacement runs <em>after</em> the JVM exits (the script sleeps first), so
 * the classpath jars are never rewritten under a live process. Relaunch defaults
 * to off: under the LFS production target lg3d <em>is</em> the X session started
 * by systemd/xinit, so killing it and letting the session manager bring it back is
 * the safe posture; a windowed user can enable {@code update.bundle.relaunch}.
 * </p>
 * <p>
 * The two destructive steps ({@link #launchApplyScript(Path)} and
 * {@link #exitForRestart()}) are {@code protected} so tests can assert the whole
 * pipeline without spawning {@code pkexec} nor killing the test JVM, and every
 * directory is injectable so tests never touch the real installation.
 * </p>
 */
@Slf4j
public class BundleUpdateInstaller {

    /** Top-level bundle entries replaced on update; everything else is left alone. */
    static final List<String> MANAGED_ENTRIES =
        List.of("lib", "resources", "etc", "ext", "lg3d.sh", "README.txt", "VERSION");

    /** Space-separated {@link #MANAGED_ENTRIES}, embedded in the generated scripts. */
    private static final String MANAGED_LIST = String.join(" ", MANAGED_ENTRIES);

    /** Seconds the apply script waits for the running desktop to release its jars. */
    static final int APPLY_WAIT_SECONDS = 3;

    /** Prefix of the timestamped rollback directories. */
    static final String BACKUP_PREFIX = "lg3d-bundle-backup-";

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");

    private final Path installRoot;
    private final Path workDir;
    private final Path backupDir;
    private final boolean relaunchEnabled;
    private final boolean escalationEnabled;

    /**
     * Creates an installer for a bundle rooted at {@code installRoot}, using the
     * default work/backup directories under {@code ~/.lg3d}.
     *
     * @param installRoot the release bundle root to update
     */
    public BundleUpdateInstaller(Path installRoot) {
        this(installRoot, defaultWorkDir(), defaultBackupDir(), false, true);
    }

    /**
     * Full constructor: every path and behaviour flag is injectable.
     *
     * @param installRoot     the release bundle root to update
     * @param workDir         directory holding the staging tree and apply script
     * @param backupDir       directory holding the timestamped rollback snapshots
     * @param relaunchEnabled whether an immediate install relaunches {@code lg3d.sh}
     * @param escalationEnabled whether {@code pkexec} may be used for a read-only root
     */
    public BundleUpdateInstaller(Path installRoot,
                                 Path workDir,
                                 Path backupDir,
                                 boolean relaunchEnabled,
                                 boolean escalationEnabled) {
        this.installRoot = installRoot;
        this.workDir = workDir != null ? workDir : defaultWorkDir();
        this.backupDir = backupDir != null ? backupDir : defaultBackupDir();
        this.relaunchEnabled = relaunchEnabled;
        this.escalationEnabled = escalationEnabled;
    }

    /**
     * Downloads-verified bundle to apply immediately: stages it, launches the
     * deferred apply script and exits the JVM so the script can replace the tree.
     *
     * @param bundleZip verified {@code lg3d-<version>.zip}
     * @throws UpdateException when the archive cannot be staged or applied
     */
    public void installBundle(Path bundleZip) throws UpdateException {
        try {
            Path staged = stage(bundleZip);
            Path backup = createBackupDir();
            Path script = createApplyScript(staged, backup, relaunchEnabled);
            setExecutable(script);
            launchApplyScript(script);
            exitForRestart();
        } catch (UpdateException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new UpdateException("Bundle installation failed", e);
        }
    }

    /**
     * Applies a staged bundle when the JVM exits, without relaunching it. Safe to
     * call from a shutdown hook; the new version is active on the next launch.
     *
     * @param bundleZip verified {@code lg3d-<version>.zip}
     * @throws UpdateException when the archive cannot be staged or applied
     */
    public void installBundleOnExit(Path bundleZip) throws UpdateException {
        try {
            Path staged = stage(bundleZip);
            Path backup = createBackupDir();
            Path script = createApplyScript(staged, backup, false);
            setExecutable(script);
            launchApplyScript(script);
        } catch (UpdateException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new UpdateException("Deferred bundle installation failed", e);
        }
    }

    /**
     * Extracts and validates the archive into a fresh staging directory.
     *
     * @param bundleZip verified {@code lg3d-<version>.zip}
     * @return the staged bundle root
     * @throws UpdateException when the file is missing, not a zip, unsafe to
     *                         extract, or does not look like a release bundle
     */
    Path stage(Path bundleZip) throws UpdateException {
        if (bundleZip == null || !Files.isRegularFile(bundleZip)) {
            throw new UpdateException("Update bundle does not exist: " + bundleZip);
        }
        if (!isZipArchive(bundleZip)) {
            throw new UpdateException(
                "The downloaded update is not a ZIP bundle: " + bundleZip
                    + ". The desktop is distributed as lg3d-<version>.zip."
            );
        }

        Path staged = workDir.resolve("staging-" + System.nanoTime());
        try {
            Files.createDirectories(staged);
            extract(bundleZip, staged);
        } catch (IOException e) {
            throw new UpdateException("Failed to extract the update bundle", e);
        }

        if (!InstallLocation.isBundleLayout(staged)) {
            throw new UpdateException(
                "The extracted bundle is not a valid Project Looking Glass release "
                    + "(missing lib/ or lg3d.sh): " + staged
            );
        }

        log.info("Update bundle staged at {}", staged);
        return staged;
    }

    /**
     * Extracts a zip into {@code dest}, rejecting any entry that would escape the
     * destination directory (zip-slip).
     *
     * @param zip  archive to extract
     * @param dest extraction target, created if absent
     * @throws IOException     on read/write failure
     * @throws UpdateException when an entry tries to escape {@code dest}
     */
    void extract(Path zip, Path dest) throws IOException, UpdateException {
        Path target = dest.toAbsolutePath().normalize();
        Files.createDirectories(target);

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path resolved = target.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(target)) {
                    throw new UpdateException(
                        "Refusing to extract a bundle entry outside the target directory: "
                            + entry.getName()
                    );
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Path parent = resolved.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(zis, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * Whether {@code file} starts with the ZIP local-file-header magic ({@code PK}).
     *
     * @param file candidate archive
     * @return {@code true} when the file looks like a zip
     * @throws UpdateException when the header cannot be read
     */
    boolean isZipArchive(Path file) throws UpdateException {
        byte[] header = new byte[2];
        try (InputStream in = Files.newInputStream(file)) {
            int read = in.readNBytes(header, 0, 2);
            return read == 2 && header[0] == 'P' && header[1] == 'K';
        } catch (IOException e) {
            throw new UpdateException("Cannot read the update bundle header: " + file, e);
        }
    }

    private Path createBackupDir() throws UpdateException {
        Path backup = backupDir.resolve(BACKUP_PREFIX + LocalDateTime.now().format(STAMP));
        try {
            Files.createDirectories(backup);
            writeRestoreScript(backup);
            return backup;
        } catch (IOException e) {
            throw new UpdateException("Failed to prepare the rollback directory: " + backup, e);
        }
    }

    /**
     * Writes a {@code restore.sh} into the rollback directory so a broken update
     * can be reverted from a shell even if the desktop no longer starts.
     */
    private void writeRestoreScript(Path backup) throws IOException {
        String script = String.format("""
            #!/bin/bash
            # Roll Project Looking Glass back to the snapshot in this directory.
            set -euo pipefail
            ROOT="%s"
            BACKUP="%s"
            for e in %s; do
                rm -rf "$ROOT/$e"
                if [ -e "$BACKUP/$e" ]; then cp -a "$BACKUP/$e" "$ROOT/$e"; fi
            done
            echo "Rolled back $ROOT from $BACKUP"
            """, installRoot, backup, MANAGED_LIST);

        Path restore = backup.resolve("restore.sh");
        Files.writeString(restore, script);
        setExecutable(restore);
    }

    private Path createApplyScript(Path staged, Path backup, boolean relaunch) throws UpdateException {
        String relaunchLine = relaunch
            ? "setsid bash \"$ROOT/lg3d.sh\" >/dev/null 2>&1 </dev/null &"
            : "# relaunch disabled: the session manager (or the user) restarts the desktop";

        String script = String.format("""
            #!/bin/bash
            # Deferred Project Looking Glass bundle update: runs once the desktop exited.
            set -euo pipefail
            STAGED="%s"
            ROOT="%s"
            BACKUP="%s"
            sleep %d
            mkdir -p "$BACKUP"
            for e in %s; do
                if [ -e "$ROOT/$e" ]; then cp -a "$ROOT/$e" "$BACKUP/"; fi
            done
            for e in %s; do
                rm -rf "$ROOT/$e"
                if [ -e "$STAGED/$e" ]; then cp -a "$STAGED/$e" "$ROOT/$e"; fi
            done
            rm -rf "$STAGED"
            echo "Project Looking Glass bundle updated in $ROOT"
            %s
            """, staged, installRoot, backup, APPLY_WAIT_SECONDS, MANAGED_LIST, MANAGED_LIST, relaunchLine);

        try {
            Files.createDirectories(workDir);
            Path scriptPath = workDir.resolve("apply-bundle-update.sh");
            Files.writeString(scriptPath, script);
            return scriptPath;
        } catch (IOException e) {
            throw new UpdateException("Failed to write the bundle update script", e);
        }
    }

    /**
     * Command used to run the apply script: {@code pkexec bash <script>} when the
     * install root is read-only and escalation is enabled, plain {@code bash}
     * otherwise. Package-visible so tests can assert both branches.
     *
     * @param script the apply script to run
     * @return the launch command
     */
    List<String> launchCommand(Path script) {
        if (escalationEnabled && !Files.isWritable(installRoot)) {
            return List.of("pkexec", "bash", script.toString());
        }
        return List.of("bash", script.toString());
    }

    private void setExecutable(Path scriptPath) throws IOException {
        try {
            Set<PosixFilePermission> permissions = new HashSet<>();
            permissions.add(PosixFilePermission.OWNER_READ);
            permissions.add(PosixFilePermission.OWNER_WRITE);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_READ);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_READ);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(scriptPath, permissions);
        } catch (UnsupportedOperationException e) {
            log.debug("Cannot set executable permissions on this platform");
        }
    }

    /**
     * Hands the generated apply script over to the platform. Overridden in tests so
     * the update is never really applied.
     *
     * @param scriptPath the executable apply script
     * @throws IOException when the script cannot be started
     */
    protected void launchApplyScript(Path scriptPath) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(launchCommand(scriptPath));
        pb.directory(workDir.toFile());
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        log.info("Launching bundle update script: {}", scriptPath);
        pb.start();
        // Not waited on: it applies the tree once this JVM has exited.
    }

    /**
     * Terminates the running JVM so the apply script can replace the bundle and
     * (optionally) relaunch it. Overridden in tests.
     */
    protected void exitForRestart() {
        log.info("Exiting the JVM to complete the bundle update");
        System.exit(0);
    }

    /** Release bundle root this installer updates. */
    public Path getInstallRoot() {
        return installRoot;
    }

    /** Directory holding the staging tree and the apply script. */
    public Path getWorkDir() {
        return workDir;
    }

    /** Directory holding the timestamped rollback snapshots. */
    public Path getBackupDir() {
        return backupDir;
    }

    /** Whether an immediate install relaunches {@code lg3d.sh}. */
    public boolean isRelaunchEnabled() {
        return relaunchEnabled;
    }

    /** Whether {@code pkexec} may be used for a read-only install root. */
    public boolean isEscalationEnabled() {
        return escalationEnabled;
    }

    private static Path defaultWorkDir() {
        return Path.of(System.getProperty("user.home"), ".lg3d", "updates");
    }

    private static Path defaultBackupDir() {
        return Path.of(System.getProperty("user.home"), ".lg3d", "backups", "bundles");
    }
}
