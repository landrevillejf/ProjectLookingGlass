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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.*;

class UpdateServiceIntegrationTest {
    
    private UpdateService updateService;
    private MockUpdateRepository mockRepository;
    private EventBus eventBus;
    
    @BeforeEach
    void setUp() {
        mockRepository = new MockUpdateRepository();
        eventBus = new EventBus();
        updateService = new UpdateService(mockRepository, eventBus);
    }
    
    @AfterEach
    void tearDown() {
        updateService.shutdown();
    }
    
    @Test
    void testUpdateServiceInitialization() {
        assertThat(updateService).isNotNull();
        assertThat(updateService.isUpdateEnabled()).isTrue();
    }
    
    @Test
    void testCheckForUpdates() {
        mockRepository.setAvailableUpdate(
            new UpdateInfo(
                "0.5.2",
                Instant.now(),
                "https://example.com/swing-ide-0.5.2-all.jar",
                52428800,
                "abc123",
                "https://example.com/swing-ide-0.5.2-all.jar.asc",
                false,
                "https://example.com/changelog",
                "21",
                false
            )
        );
        
        UpdateInfo updateInfo = updateService.checkForUpdatesNow();
        
        assertThat(updateInfo).isNotNull();
        assertThat(updateInfo.getVersion()).isEqualTo("0.5.2");
    }
    
    @Test
    void testDownloadAndInstallFlow() throws Exception {
        // Port 1 is never served: the download fails fast and the installer, which
        // would exit the JVM, is never reached.
        UpdateInfo updateInfo = new UpdateInfo(
            "0.5.2",
            Instant.now(),
            "http://127.0.0.1:1/swing-ide-0.5.2-all.jar",
            52428800,
            "abc123",
            "http://127.0.0.1:1/swing-ide-0.5.2-all.jar.asc",
            false,
            "http://127.0.0.1:1/changelog",
            "21",
            false
        );
        
        CompletableFuture<Void> future = updateService.downloadAndInstall(updateInfo);
        
        assertThat(future).isNotNull();
        // CompletableFuture#get unwraps CompletionException, so the failure is
        // reported directly as its UpdateException cause.
        assertThatThrownBy(future::get)
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(UpdateException.class)
            .hasStackTraceContaining("Download failed");
    }
    
    @Test
    void testUpdateServiceShutdown() {
        updateService.shutdown();
    }
    
    @Test
    void testCriticalUpdateDetection() {
        UpdateInfo criticalUpdate = new UpdateInfo(
            "0.5.2",
            Instant.now(),
            "https://example.com/swing-ide-0.5.2-all.jar",
            52428800,
            "abc123",
            "https://example.com/swing-ide-0.5.2-all.jar.asc",
            true,
            "https://example.com/changelog",
            "21",
            false
        );
        
        mockRepository.setAvailableUpdate(criticalUpdate);
        
        UpdateInfo updateInfo = updateService.checkForUpdatesNow();
        
        assertThat(updateInfo).isNotNull();
        assertThat(updateInfo.isCritical()).isTrue();
    }
    
    @Test
    void testBreakingChangesDetection() {
        UpdateInfo breakingUpdate = new UpdateInfo(
            "1.0.0",
            Instant.now(),
            "https://example.com/swing-ide-1.0.0-all.jar",
            62428800,
            "def456",
            "https://example.com/swing-ide-1.0.0-all.jar.asc",
            false,
            "https://example.com/changelog",
            "21",
            true
        );
        
        mockRepository.setAvailableUpdate(breakingUpdate);
        
        UpdateInfo updateInfo = updateService.checkForUpdatesNow();
        
        assertThat(updateInfo).isNotNull();
        assertThat(updateInfo.isBreakingChanges()).isTrue();
    }
    
    private static class MockUpdateRepository extends UpdateRepository {
        private UpdateInfo availableUpdate;
        
        MockUpdateRepository() {
            super("http://mock.local");
        }
        
        void setAvailableUpdate(UpdateInfo update) {
            this.availableUpdate = update;
        }
        
        @Override
        public UpdateInfo fetchLatestVersion() throws UpdateException {
            return availableUpdate;
        }
    }
}
