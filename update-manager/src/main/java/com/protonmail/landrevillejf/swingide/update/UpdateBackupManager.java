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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
public class UpdateBackupManager {
    
    private final Path backupDir;
    
    public UpdateBackupManager() {
        this.backupDir = getDefaultBackupDir();
    }
    
    public UpdateBackupManager(Path backupDir) {
        this.backupDir = backupDir;
    }
    
    public Path createBackup(Path currentJar) throws UpdateException {
        if (currentJar == null || !Files.isRegularFile(currentJar)) {
            // Files.copy on a directory silently creates an empty directory named
            // like a JAR, which would corrupt the rollback archive.
            throw new UpdateException(
                "Cannot back up " + currentJar + ": it is not a regular file. "
                + "Updates can only be applied when the IDE runs from its packaged JAR."
            );
        }

        try {
            Files.createDirectories(backupDir);
            
            String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
            
            String fileName = "lg3d-backup-" + timestamp + ".jar";
            Path backupFile = backupDir.resolve(fileName);
            
            log.info("Creating backup of current JAR: {}", currentJar);
            log.info("Backup destination: {}", backupFile);
            
            Files.copy(currentJar, backupFile);
            
            log.info("Backup created successfully");
            return backupFile;
            
        } catch (IOException e) {
            throw new UpdateException("Failed to create backup", e);
        }
    }
    
    public void restoreBackup(Path backupFile) throws UpdateException {
        try {
            Path currentJarPath = JarLocator.getCurrentJarPath();
            
            if (!Files.exists(backupFile)) {
                throw new UpdateException("Backup file does not exist: " + backupFile);
            }
            
            log.info("Restoring from backup: {}", backupFile);
            log.info("Restoring to: {}", currentJarPath);
            
            Files.copy(backupFile, currentJarPath, 
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            log.info("Backup restored successfully");
            
        } catch (IOException e) {
            throw new UpdateException("Failed to restore backup", e);
        }
    }
    
    public void cleanupOldBackups(int keepCount) throws UpdateException {
        try {
            if (!Files.exists(backupDir)) {
                return;
            }
            
            try (Stream<Path> paths = Files.list(backupDir)) {
                List<Path> backups = paths
                    .filter(p -> p.getFileName().toString().startsWith("lg3d-backup-"))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.reverseOrder())
                    .toList();
                
                if (backups.size() > keepCount) {
                    List<Path> toDelete = backups.subList(keepCount, backups.size());
                    
                    for (Path backup : toDelete) {
                        try {
                            Files.delete(backup);
                            log.debug("Deleted old backup: {}", backup);
                        } catch (IOException e) {
                            log.warn("Failed to delete old backup: {}", backup, e);
                        }
                    }
                    
                    log.info("Cleaned up {} old backups, keeping {}", 
                        toDelete.size(), keepCount);
                }
            }
            
        } catch (IOException e) {
            throw new UpdateException("Failed to cleanup old backups", e);
        }
    }
    
    public List<Path> listBackups() throws UpdateException {
        try {
            if (!Files.exists(backupDir)) {
                return List.of();
            }
            
            try (Stream<Path> paths = Files.list(backupDir)) {
                return paths
                    .filter(p -> p.getFileName().toString().startsWith("lg3d-backup-"))
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.reverseOrder())
                    .toList();
            }
            
        } catch (IOException e) {
            throw new UpdateException("Failed to list backups", e);
        }
    }
    
    public Path getLatestBackup() throws UpdateException {
        List<Path> backups = listBackups();
        
        if (backups.isEmpty()) {
            throw new UpdateException("No backups available");
        }
        
        return backups.get(0);
    }
    
    public long getBackupSize(Path backupFile) throws UpdateException {
        try {
            return Files.size(backupFile);
        } catch (IOException e) {
            throw new UpdateException("Failed to get backup size", e);
        }
    }
    
    private Path getDefaultBackupDir() {
        String userHome = System.getProperty("user.home");
        return Path.of(userHome, ".lg3d", "backups");
    }
}
