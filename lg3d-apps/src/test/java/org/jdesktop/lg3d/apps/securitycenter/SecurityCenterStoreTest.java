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
package org.jdesktop.lg3d.apps.securitycenter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers {@link SecurityCenterStore}'s defensive JSON round-trip and beans. */
class SecurityCenterStoreTest {

    @Test
    @DisplayName("settings survive a save / load round-trip")
    void settingsRoundTrip(@TempDir Path dir) {
        SecurityCenterStore store = new SecurityCenterStore(dir);
        SecurityCenterSettings s = new SecurityCenterSettings();
        s.setScanPath("/home/me/Downloads");
        s.setRecursive(false);
        s.setQuarantine(true);
        s.setQuarantineDir("/home/me/.q");
        s.setPreferredScanner("clamscan");
        s.setUpdateBeforeScan(true);
        s.setRefreshOverviewOnOpen(false);
        store.saveSettings(s);

        SecurityCenterSettings back = new SecurityCenterStore(dir).loadSettings();
        assertEquals("/home/me/Downloads", back.getScanPath());
        assertFalse(back.isRecursive());
        assertTrue(back.isQuarantine());
        assertEquals("/home/me/.q", back.getQuarantineDir());
        assertEquals("clamscan", back.getPreferredScanner());
        assertTrue(back.isUpdateBeforeScan());
        assertFalse(back.isRefreshOverviewOnOpen());
    }

    @Test
    @DisplayName("settings normalise blank / null inputs")
    void settingsNormalization() {
        SecurityCenterSettings s = new SecurityCenterSettings();
        s.setScanPath("   ");
        assertEquals(AntivirusBackend.DEFAULT_TARGET, s.getScanPath(),
                "a blank target resets to the home folder");
        s.setQuarantineDir(null);
        assertEquals("", s.getQuarantineDir());
        s.setPreferredScanner(null);
        assertEquals("", s.getPreferredScanner());
        assertEquals(AntivirusBackend.DEFAULT_TARGET, new SecurityCenterSettings().getScanPath());
        assertTrue(new SecurityCenterSettings().isRecursive());
    }

    @Test
    @DisplayName("history survives a round-trip with counts intact")
    void historyRoundTrip(@TempDir Path dir) {
        SecurityCenterStore store = new SecurityCenterStore(dir);
        ScanReport infected = new ScanReport(
                List.of(Detection.infected("/tmp/eicar", "Win.Test.EICAR_HDB-1")),
                1, 0, 1, 0, 100L, "1.4.6", "0.1 sec", true, 1, 1, "");
        ScanRecord record = new ScanRecord("/tmp", "clamscan", infected);
        record.setDurationMillis(1500);
        store.saveHistory(List.of(record));

        List<ScanRecord> back = new SecurityCenterStore(dir).loadHistory();
        assertEquals(1, back.size());
        assertEquals("/tmp", back.get(0).getTarget());
        assertEquals("clamscan", back.get(0).getScanner());
        assertEquals(1, back.get(0).getInfectedFiles());
        assertEquals(1500, back.get(0).getDurationMillis());
        assertFalse(back.get(0).isClean());
    }

    @Test
    @DisplayName("a ScanRecord built from a report mirrors its outcome")
    void scanRecordFromReport() {
        ScanReport clean = new ScanReport(List.of(), 42, 3, 0, 0, 100L, "1.4.6",
                "1 sec", true, 42, 0, "");
        ScanRecord record = new ScanRecord("/home/me", "clamscan", clean);
        assertEquals(42, record.getScannedFiles());
        assertEquals(0, record.getInfectedFiles());
        assertTrue(record.isClean());
        assertFalse(record.isFailed());
        assertTrue(record.toString().contains("clean"), record.toString());
        assertTrue(record.toString().contains("42 files"), record.toString());

        ScanReport failed = new ScanReport(List.of(), 0, 0, 0, 1, 0L, "", "", true, 0, 2,
                "ERROR: connection refused");
        ScanRecord failedRecord = new ScanRecord("/x", "clamdscan", failed);
        assertTrue(failedRecord.isFailed());
        assertTrue(failedRecord.toString().contains("did not run"), failedRecord.toString());
    }

    @Test
    @DisplayName("ScanRecord.shorten reduces a path to its last segment")
    void shorten() {
        assertEquals("Downloads", ScanRecord.shorten("/home/me/Downloads"));
        assertEquals("file.txt", ScanRecord.shorten("file.txt"));
        assertEquals("(unknown)", ScanRecord.shorten("  "));
        assertEquals("(unknown)", ScanRecord.shorten(null));
    }

    @Test
    @DisplayName("missing files yield defaults / empty, never throw")
    void missingIsSafe(@TempDir Path dir) {
        SecurityCenterStore store = new SecurityCenterStore(dir);
        assertTrue(store.loadSettings().isRecursive());
        assertTrue(store.loadHistory().isEmpty());
    }

    @Test
    @DisplayName("corrupt files yield defaults / empty, never throw")
    void corruptIsSafe(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(SecurityCenterStore.SETTINGS_FILE), "{bad json]");
        Files.writeString(dir.resolve(SecurityCenterStore.HISTORY_FILE), "not-a-list");
        SecurityCenterStore store = new SecurityCenterStore(dir);
        assertEquals(AntivirusBackend.DEFAULT_TARGET, store.loadSettings().getScanPath());
        assertTrue(store.loadHistory().isEmpty());
    }

    @Test
    @DisplayName("saving null persists defaults without error")
    void saveNullIsSafe(@TempDir Path dir) {
        SecurityCenterStore store = new SecurityCenterStore(dir);
        store.saveSettings(null);
        store.saveHistory(null);
        assertEquals(AntivirusBackend.DEFAULT_TARGET, store.loadSettings().getScanPath());
        assertTrue(store.loadHistory().isEmpty());
    }

    @Test
    @DisplayName("the quarantine dir defaults under the config dir and is created")
    void quarantineDir(@TempDir Path dir) {
        SecurityCenterStore store = new SecurityCenterStore(dir);
        Path q = store.resolveQuarantineDir(new SecurityCenterSettings());
        assertEquals(dir.resolve("quarantine"), q);
        assertTrue(Files.isDirectory(q), "the quarantine folder is created on demand");

        SecurityCenterSettings custom = new SecurityCenterSettings();
        custom.setQuarantineDir(dir.resolve("custom-q").toString());
        assertEquals(dir.resolve("custom-q"), store.resolveQuarantineDir(custom));
        assertEquals(dir.resolve("quarantine"), store.resolveQuarantineDir(null),
                "null settings fall back to the config quarantine dir");
    }

    @Test
    @DisplayName("the DIR_PROPERTY override resolves the default directory")
    void honoursOverride() {
        String previous = System.getProperty(SecurityCenterStore.DIR_PROPERTY);
        try {
            System.setProperty(SecurityCenterStore.DIR_PROPERTY, "/tmp/sc-override");
            assertEquals(Path.of("/tmp/sc-override"), SecurityCenterStore.defaultConfigDir());
            assertEquals(Path.of("/tmp/sc-override"), new SecurityCenterStore().getConfigDir());
        } finally {
            if (previous == null) {
                System.clearProperty(SecurityCenterStore.DIR_PROPERTY);
            } else {
                System.setProperty(SecurityCenterStore.DIR_PROPERTY, previous);
            }
        }
    }
}
