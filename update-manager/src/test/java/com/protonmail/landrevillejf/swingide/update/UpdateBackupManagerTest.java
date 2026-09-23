package com.protonmail.landrevillejf.swingide.update;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class UpdateBackupManagerTest {
    
    @TempDir
    Path tempDir;
    
    private UpdateBackupManager backupManager;
    private Path backupDir;
    private Path jarFile;
    
    @BeforeEach
    void setUp() throws IOException {
        backupDir = tempDir.resolve("backups");
        backupManager = new UpdateBackupManager(backupDir);
        jarFile = tempDir.resolve("app.jar");
        Files.writeString(jarFile, "JAR content");
    }
    
    @Test
    void testCreateBackup() throws UpdateException {
        Path backup = backupManager.createBackup(jarFile);
        
        assertThat(backup).exists();
        assertThat(backup.getFileName().toString()).startsWith("lg3d-backup-");
        assertThat(backup.getFileName().toString()).endsWith(".jar");
    }
    
    @Test
    void testBackupContents() throws UpdateException, IOException {
        Path backup = backupManager.createBackup(jarFile);
        
        String originalContent = Files.readString(jarFile);
        String backupContent = Files.readString(backup);
        
        assertThat(backupContent).isEqualTo(originalContent);
    }
    
    @Test
    void testListBackups() throws UpdateException, IOException, InterruptedException {
        backupManager.createBackup(jarFile);
        Thread.sleep(100);
        backupManager.createBackup(jarFile);
        
        List<Path> backups = backupManager.listBackups();
        
        assertThat(backups).hasSize(2);
    }
    
    @Test
    void testGetLatestBackup() throws UpdateException, IOException, InterruptedException {
        Path firstBackup = backupManager.createBackup(jarFile);
        Thread.sleep(100);
        Path secondBackup = backupManager.createBackup(jarFile);
        
        Path latest = backupManager.getLatestBackup();
        
        assertThat(latest).isEqualTo(secondBackup);
    }
    
    @Test
    void testGetLatestBackupThrowsWhenNoBackups() {
        assertThatThrownBy(() -> backupManager.getLatestBackup())
            .isInstanceOf(UpdateException.class)
            .hasMessageContaining("No backups available");
    }
    
    @Test
    void testCleanupOldBackups() throws UpdateException, IOException, InterruptedException {
        for (int i = 0; i < 5; i++) {
            backupManager.createBackup(jarFile);
            Thread.sleep(50);
        }
        
        List<Path> backupsBeforeCleanup = backupManager.listBackups();
        assertThat(backupsBeforeCleanup).hasSize(5);
        
        backupManager.cleanupOldBackups(2);
        
        List<Path> backupsAfterCleanup = backupManager.listBackups();
        assertThat(backupsAfterCleanup).hasSize(2);
    }
    
    @Test
    void testGetBackupSize() throws UpdateException, IOException {
        Path backup = backupManager.createBackup(jarFile);
        long size = backupManager.getBackupSize(backup);
        
        assertThat(size).isGreaterThan(0);
    }
    
    @Test
    void testBackupDirectoryCreation() throws UpdateException {
        assertThat(backupDir).doesNotExist();
        
        backupManager.createBackup(jarFile);
        
        assertThat(backupDir).exists();
    }
    
    @Test
    void testListBackupsEmptyDirectory() throws UpdateException {
        List<Path> backups = backupManager.listBackups();
        
        assertThat(backups).isEmpty();
    }
    
    @Test
    void testCreateBackupWithNonexistentSource() {
        Path nonexistentFile = tempDir.resolve("nonexistent.jar");
        
        assertThatThrownBy(() -> backupManager.createBackup(nonexistentFile))
            .isInstanceOf(UpdateException.class);
    }
}
