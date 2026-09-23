package com.protonmail.landrevillejf.swingide.update;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

/**
 * Replaces the running JAR with a freshly downloaded one.
 * <p>
 * The last two steps of an installation are inherently destructive for the
 * running JVM: a platform specific script is launched and the JVM exits. Both
 * are isolated in {@code protected} methods ({@link #launchUpdateScript(Path)}
 * and {@link #exitForRestart()}) so they can be replaced in tests, and the
 * directory holding the generated script is injectable so tests never write
 * into the real {@code ~/.lg3d/updates} folder.
 * </p>
 */
@Slf4j
public class UpdateInstaller {
    
    private final Path currentJarPath;
    private final Path downloadDir;
    private final UpdateBackupManager backupManager;
    
    public UpdateInstaller() {
        this(new UpdateBackupManager());
    }
    
    public UpdateInstaller(UpdateBackupManager backupManager) {
        this(backupManager, getDefaultDownloadDir());
    }

    /**
     * Creates an installer writing its update script into a dedicated directory.
     *
     * @param backupManager manager archiving the running JAR
     * @param downloadDir   directory holding the generated update script
     */
    public UpdateInstaller(UpdateBackupManager backupManager, Path downloadDir) {
        this(backupManager, downloadDir, JarLocator.getCurrentJarPath());
    }

    /**
     * Full constructor: every path involved in the installation is injectable.
     * <p>
     * Used by tests, and by hosts launching the IDE through a custom launcher
     * where {@link JarLocator} cannot infer the archive to replace.
     * </p>
     *
     * @param backupManager  manager archiving the running JAR
     * @param downloadDir    directory holding the generated update script
     * @param currentJarPath JAR replaced by the update, defaults to the running one
     */
    public UpdateInstaller(UpdateBackupManager backupManager, Path downloadDir, Path currentJarPath) {
        this.currentJarPath = currentJarPath != null ? currentJarPath : JarLocator.getCurrentJarPath();
        this.downloadDir = downloadDir != null ? downloadDir : getDefaultDownloadDir();
        this.backupManager = backupManager;
    }
    
    public void installUpdate(Path newJarFile) throws UpdateException {
        installUpdate(newJarFile, null);
    }
    
    /**
     * Installs the downloaded JAR, reusing an already created backup instead of
     * copying the running JAR a second time.
     *
     * @param newJarFile     verified JAR to install
     * @param existingBackup rollback archive prepared by the caller, may be {@code null}
     * @throws UpdateException when the installation cannot be prepared
     */
    public void installUpdate(Path newJarFile, Path existingBackup) throws UpdateException {
        try {
            log.info("Installing update from: {}", newJarFile);
            log.info("Target location: {}", currentJarPath);
            
            // Verify the new JAR exists
            if (!Files.exists(newJarFile)) {
                throw new UpdateException("Update file does not exist: " + newJarFile);
            }
            
            if (existingBackup != null && Files.exists(existingBackup)) {
                log.info("Reusing the rollback archive prepared by the caller: {}", existingBackup);
            } else {
                // Create backup of current JAR
                log.info("Creating backup before update");
                backupManager.createBackup(currentJarPath);
            }
            
            // Create update script based on OS
            Path updateScript = createUpdateScript(newJarFile);
            
            // Make script executable
            setExecutable(updateScript);
            
            // Launch update script and exit
            launchUpdateScript(updateScript);
            
            // Exit current JVM
            exitForRestart();
            
        } catch (UpdateException e) {
            throw e;
        } catch (Exception e) {
            throw new UpdateException("Update installation failed", e);
        }
    }
    
    /**
     * Backup manager used when the caller does not provide a rollback archive.
     */
    public UpdateBackupManager getBackupManager() {
        return backupManager;
    }
    
    /**
     * JAR replaced by the installation.
     */
    public Path getCurrentJarPath() {
        return currentJarPath;
    }

    /**
     * Directory the generated update script is written to.
     */
    public Path getDownloadDir() {
        return downloadDir;
    }
    
    public void installUpdateWithVerification(Path newJarFile, String expectedSha256) 
        throws UpdateException {
        
        try {
            // Verify checksum before installation
            if (!ChecksumCalculator.verifyChecksum(newJarFile, expectedSha256)) {
                throw new UpdateException("Checksum verification failed before installation");
            }
            
            installUpdate(newJarFile);
            
        } catch (UpdateException e) {
            throw e;
        } catch (Exception e) {
            throw new UpdateException("Installation with verification failed", e);
        }
    }

    /**
     * Applies a staged update when the JVM exits, without relaunching it.
     * <p>
     * Used by the auto-install feature: the platform script waits for the file
     * lock to be released, replaces the running JAR and stops, so the new version
     * takes effect the next time the user launches Project Looking Glass. Unlike
     * {@link #installUpdate(Path, Path)} this never calls {@link #exitForRestart()}
     * and is therefore safe to run from a shutdown hook.
     * </p>
     *
     * @param newJarFile     verified JAR to apply
     * @param existingBackup rollback archive prepared by the caller, may be {@code null}
     * @throws UpdateException when the deferred installation cannot be prepared
     */
    public void installOnExit(Path newJarFile, Path existingBackup) throws UpdateException {
        try {
            log.info("Scheduling deferred installation from: {}", newJarFile);

            if (!Files.exists(newJarFile)) {
                throw new UpdateException("Update file does not exist: " + newJarFile);
            }

            if (existingBackup != null && Files.exists(existingBackup)) {
                log.info("Reusing the rollback archive prepared by the caller: {}", existingBackup);
            } else {
                log.info("Creating backup before the deferred update");
                backupManager.createBackup(currentJarPath);
            }

            Path updateScript = createApplyOnlyScript(newJarFile);
            setExecutable(updateScript);
            launchUpdateScript(updateScript);

        } catch (UpdateException e) {
            throw e;
        } catch (Exception e) {
            throw new UpdateException("Deferred installation failed", e);
        }
    }

    private Path createApplyOnlyScript(Path newJarFile) throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String scriptName;
        String scriptContent;

        if (os.contains("win")) {
            scriptName = "update-on-exit.bat";
            scriptContent = generateWindowsApplyScript(newJarFile);
        } else {
            scriptName = "update-on-exit.sh";
            scriptContent = generateUnixApplyScript(newJarFile);
        }

        Path scriptPath = downloadDir.resolve(scriptName);
        Files.createDirectories(downloadDir);
        Files.writeString(scriptPath, scriptContent);
        return scriptPath;
    }

    private String generateWindowsApplyScript(Path newJarFile) {
        String currentJar = currentJarPath.toString().replace("\\", "\\\\");
        String newJar = newJarFile.toString().replace("\\", "\\\\");

        return String.format("""
            @echo off
            timeout /t 3 /nobreak > nul
            copy /Y "%s" "%s"
            if errorlevel 1 (
                echo Deferred update failed: could not copy the new JAR
                exit /b 1
            )
            echo Update applied, it will take effect on the next launch
            del "%s"
            """,
            newJar,
            currentJar,
            newJar
        );
    }

    private String generateUnixApplyScript(Path newJarFile) {
        return String.format("""
            #!/bin/bash
            set -e
            sleep 3
            cp -f "%s" "%s"
            echo "Update applied, it will take effect on the next launch"
            rm -f "%s"
            """,
            newJarFile,
            currentJarPath,
            newJarFile
        );
    }
    
    private Path createUpdateScript(Path newJarFile) throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String scriptName;
        String scriptContent;
        
        if (os.contains("win")) {
            scriptName = "update.bat";
            scriptContent = generateWindowsScript(newJarFile);
        } else if (os.contains("mac")) {
            scriptName = "update.sh";
            scriptContent = generateMacScript(newJarFile);
        } else {
            scriptName = "update.sh";
            scriptContent = generateLinuxScript(newJarFile);
        }
        
        Path scriptPath = downloadDir.resolve(scriptName);
        Files.createDirectories(downloadDir);
        Files.writeString(scriptPath, scriptContent);
        return scriptPath;
    }
    
    private String generateWindowsScript(Path newJarFile) {
        String currentJar = currentJarPath.toString().replace("\\", "\\\\");
        String newJar = newJarFile.toString().replace("\\", "\\\\");
        
        return String.format("""
            @echo off
            setlocal enabledelayedexpansion
            timeout /t 2 /nobreak > nul
            copy /Y "%s" "%s"
            if errorlevel 1 (
                echo Update failed: Could not copy new JAR
                pause
                exit /b 1
            )
            echo Update successful, restarting...
            start "" javaw -jar "%s"
            timeout /t 1 /nobreak > nul
            del "%s"
            """,
            newJar,
            currentJar,
            currentJar,
            newJar
        );
    }
    
    private String generateMacScript(Path newJarFile) {
        return String.format("""
            #!/bin/bash
            set -e
            sleep 2
            cp -f "%s" "%s"
            if [ $? -ne 0 ]; then
                echo "Update failed: Could not copy new JAR"
                exit 1
            fi
            echo "Update successful, restarting..."
            java -jar "%s" &
            JAVA_PID=$!
            sleep 1
            rm -f "%s"
            wait $JAVA_PID 2>/dev/null || true
            """,
            newJarFile,
            currentJarPath,
            currentJarPath,
            newJarFile
        );
    }
    
    private String generateLinuxScript(Path newJarFile) {
        return String.format("""
            #!/bin/bash
            set -e
            sleep 2
            cp -f "%s" "%s"
            if [ $? -ne 0 ]; then
                echo "Update failed: Could not copy new JAR"
                exit 1
            fi
            echo "Update successful, restarting..."
            java -jar "%s" &
            JAVA_PID=$!
            sleep 1
            rm -f "%s"
            wait $JAVA_PID 2>/dev/null || true
            """,
            newJarFile,
            currentJarPath,
            currentJarPath,
            newJarFile
        );
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
            // Windows doesn't support POSIX permissions
            log.debug("Cannot set executable permissions on Windows");
        }
    }
    
    /**
     * Hands the generated script over to the platform. Overridden in tests so
     * the update is never really applied.
     *
     * @param scriptPath the executable script replacing the running JAR
     * @throws IOException when the script cannot be started
     */
    protected void launchUpdateScript(Path scriptPath) throws IOException {
        ProcessBuilder pb;
        String os = System.getProperty("os.name").toLowerCase();
        
        if (os.contains("win")) {
            pb = new ProcessBuilder("cmd.exe", "/c", scriptPath.toString());
        } else {
            pb = new ProcessBuilder("bash", scriptPath.toString());
        }
        
        pb.directory(downloadDir.toFile());
        pb.inheritIO();
        
        log.info("Launching update script: {}", scriptPath);
        pb.start();
        
        // Don't wait for the process to complete
        // It will continue after this JVM exits
    }

    /**
     * Terminates the running JVM so the update script can replace the JAR and
     * restart the IDE. Overridden in tests.
     */
    protected void exitForRestart() {
        log.info("Exiting current JVM to complete update");
        System.exit(0);
    }
    
    private static Path getDefaultDownloadDir() {
        String userHome = System.getProperty("user.home");
        return Path.of(userHome, ".lg3d", "updates");
    }
}
