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
package org.jdesktop.lg3d.utils.system;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jdesktop.lg3d.utils.system.InitSystemService.InitSystem;
import org.jdesktop.lg3d.utils.system.PrivacyService.FileContent;
import org.jdesktop.lg3d.utils.system.PrivacyService.Operation;
import org.jdesktop.lg3d.utils.system.PrivacyService.Probes;
import org.jdesktop.lg3d.utils.system.PrivacyService.TorState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link PrivacyService}: the §4.7 detection matrix, the init-delegated
 * command vectors (no tor-specific reimplementation, no shell interpolation),
 * the pure state parser, the bounded read-only file views and the non-spawning
 * guards of the {@code run*} helpers. Everything here is pure or touches only a
 * {@link TempDir} - no process is started - so it runs headless in CI.
 */
class PrivacyServiceTest {

    // ------------------------------------------------------------------
    // Detection matrix

    @Test
    @DisplayName("tor binary present is reported, absent is not")
    void detectTorInstalled() {
        assertTrue(PrivacyService.isTorInstalled(new Probes(true, false, false, false)));
        assertFalse(PrivacyService.isTorInstalled(new Probes(false, true, true, true)));
        assertFalse(PrivacyService.isTorInstalled(null));
    }

    @Test
    @DisplayName("a detected init system is reported, unknown is not")
    void detectInitKnown() {
        assertTrue(PrivacyService.isInitKnown(new Probes(false, true, false, false)));
        assertFalse(PrivacyService.isInitKnown(new Probes(true, false, true, true)));
        assertFalse(PrivacyService.isInitKnown(null));
    }

    @Test
    @DisplayName("tor is manageable only when the binary AND an init are present")
    void detectTorManageable() {
        assertTrue(PrivacyService.isTorManageable(new Probes(true, true, false, false)));
        assertFalse(PrivacyService.isTorManageable(new Probes(true, false, false, false)),
                "installed but no init to drive it");
        assertFalse(PrivacyService.isTorManageable(new Probes(false, true, false, false)),
                "an init but no tor");
        assertFalse(PrivacyService.isTorManageable(null));
    }

    @Test
    @DisplayName("torrc / log presence are reported independently")
    void detectFiles() {
        assertTrue(PrivacyService.hasTorrc(new Probes(false, false, true, false)));
        assertFalse(PrivacyService.hasTorrc(new Probes(false, false, false, true)));
        assertTrue(PrivacyService.hasTorLog(new Probes(false, false, false, true)));
        assertFalse(PrivacyService.hasTorLog(new Probes(false, false, true, false)));
        assertFalse(PrivacyService.hasTorrc(null));
        assertFalse(PrivacyService.hasTorLog(null));
    }

    @Test
    @DisplayName("live probes never throw and never return null")
    void liveProbesAreSafe() {
        assertDoesNotThrow(() -> {
            Probes p = PrivacyService.probe();
            assertNotNull(p);
            PrivacyService.isTorAvailable();
            PrivacyService.isTorManageable();
            PrivacyService.torrcPresent();
            PrivacyService.torLogPresent();
        });
    }

    // ------------------------------------------------------------------
    // Command vectors (all delegate to the §4.1 init abstraction)

    @Test
    @DisplayName("tor status/start/stop/restart delegate to the init abstraction (systemd)")
    void lifecycleVectorsSystemd() {
        assertEquals(List.of("systemctl", "status", "tor"),
                PrivacyService.buildCommand(Operation.TOR_STATUS, InitSystem.SYSTEMD));
        assertEquals(List.of("systemctl", "start", "tor"),
                PrivacyService.buildCommand(Operation.TOR_START, InitSystem.SYSTEMD));
        assertEquals(List.of("systemctl", "stop", "tor"),
                PrivacyService.buildCommand(Operation.TOR_STOP, InitSystem.SYSTEMD));
        assertEquals(List.of("systemctl", "restart", "tor"),
                PrivacyService.buildCommand(Operation.TOR_RESTART, InitSystem.SYSTEMD));
    }

    @Test
    @DisplayName("tor lifecycle maps per init system (openrc/runit)")
    void lifecycleVectorsOtherInit() {
        assertEquals(List.of("rc-service", "tor", "status"),
                PrivacyService.buildCommand(Operation.TOR_STATUS, InitSystem.OPENRC));
        assertEquals(List.of("rc-service", "tor", "restart"),
                PrivacyService.buildCommand(Operation.TOR_RESTART, InitSystem.OPENRC));
        assertEquals(List.of("sv", "up", "tor"),
                PrivacyService.buildCommand(Operation.TOR_START, InitSystem.RUNIT));
        assertEquals(List.of("sv", "down", "tor"),
                PrivacyService.buildCommand(Operation.TOR_STOP, InitSystem.RUNIT));
    }

    @Test
    @DisplayName("an unknown/null init or a null op yields an empty vector")
    void unknownInitYieldsEmpty() {
        assertEquals(List.of(), PrivacyService.buildCommand(Operation.TOR_STATUS, InitSystem.UNKNOWN));
        assertEquals(List.of(), PrivacyService.buildCommand(Operation.TOR_START, InitSystem.UNKNOWN));
        assertEquals(List.of(), PrivacyService.buildCommand(Operation.TOR_STATUS, null));
        assertEquals(List.of(), PrivacyService.buildCommand(null, InitSystem.SYSTEMD));
    }

    @Test
    @DisplayName("mutating classification matches §4.7 (start/stop/restart only)")
    void mutatingClassification() {
        assertFalse(Operation.TOR_STATUS.isMutating());
        assertTrue(Operation.TOR_START.isMutating());
        assertTrue(Operation.TOR_STOP.isMutating());
        assertTrue(Operation.TOR_RESTART.isMutating());
    }

    // ------------------------------------------------------------------
    // State parser

    @Test
    @DisplayName("parseTorState reads the systemd status shapes")
    void parseTorStateSystemd() {
        assertEquals(TorState.RUNNING, PrivacyService.parseTorState(0, "Active: active (running) since ..."));
        assertEquals(TorState.STOPPED, PrivacyService.parseTorState(3, "Active: inactive (dead)"));
        assertEquals(TorState.STOPPED, PrivacyService.parseTorState(3, "Active: failed (Result: exit-code)"));
    }

    @Test
    @DisplayName("parseTorState reads the runit / openrc status shapes")
    void parseTorStateOtherInit() {
        assertEquals(TorState.RUNNING, PrivacyService.parseTorState(0, "run: tor: (pid 1234) 10s"));
        assertEquals(TorState.STOPPED, PrivacyService.parseTorState(1, "down: tor: 3s, normally up"));
        assertEquals(TorState.RUNNING, PrivacyService.parseTorState(0, " * status: started"));
        assertEquals(TorState.STOPPED, PrivacyService.parseTorState(3, " * status: stopped"));
        assertEquals(TorState.STOPPED, PrivacyService.parseTorState(1, "tor is not running"));
    }

    @Test
    @DisplayName("with no usable text the exit code decides; negative words win first")
    void parseTorStateFallback() {
        assertEquals(TorState.RUNNING, PrivacyService.parseTorState(0, ""));
        assertEquals(TorState.RUNNING, PrivacyService.parseTorState(0, null));
        assertEquals(TorState.UNKNOWN, PrivacyService.parseTorState(3, ""));
        assertEquals(TorState.STOPPED, PrivacyService.parseTorState(0, "inactive"),
                "a negative phrase beats a zero exit");
    }

    @Test
    @DisplayName("describeTor labels every state and never returns null")
    void describeTor() {
        assertEquals("Running", PrivacyService.describeTor(TorState.RUNNING));
        assertEquals("Stopped", PrivacyService.describeTor(TorState.STOPPED));
        assertEquals("Unknown", PrivacyService.describeTor(TorState.UNKNOWN));
        assertEquals("Unknown", PrivacyService.describeTor(null));
    }

    // ------------------------------------------------------------------
    // Read-only file views

    @Test
    @DisplayName("a missing file is reported as not present, not empty")
    void readFileMissing(@TempDir Path dir) {
        FileContent fc = PrivacyService.readFile(dir.resolve("nope").toString());
        assertFalse(fc.isExists());
        assertFalse(fc.isReadable());
        assertEquals("", fc.getText());
        assertEquals("not present", fc.describe());
        assertEquals(0, fc.getLineCount());
    }

    @Test
    @DisplayName("a readable file is returned verbatim with a line count")
    void readFilePresent(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("torrc");
        Files.writeString(f, "# tor config\nSocksPort 9050\n");
        FileContent fc = PrivacyService.readFile(f.toString());
        assertTrue(fc.isExists());
        assertTrue(fc.isReadable());
        assertFalse(fc.isTruncated());
        assertEquals("# tor config\nSocksPort 9050\n", fc.getText());
        assertEquals(2, fc.getLineCount());
        assertEquals("2 lines", fc.describe());
    }

    @Test
    @DisplayName("a single unterminated line counts as one line")
    void readFileSingleLine(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("one");
        Files.writeString(f, "x");
        FileContent fc = PrivacyService.readFile(f.toString());
        assertEquals(1, fc.getLineCount());
        assertEquals("1 line", fc.describe());
    }

    @Test
    @DisplayName("a null / blank path is guarded, never thrown")
    void readFileNullGuard() {
        assertFalse(PrivacyService.readFile(null).isExists());
        assertFalse(PrivacyService.readFile("   ").isExists());
    }

    @Test
    @DisplayName("a path that cannot be read as text degrades to unreadable")
    void readFileUnreadable(@TempDir Path dir) {
        // A directory exists and is "readable" but readString throws, exercising
        // the honest unreadable path without depending on chmod / root.
        FileContent fc = PrivacyService.readFile(dir.toString());
        assertTrue(fc.isExists());
        assertFalse(fc.isReadable());
        assertEquals("", fc.getText());
        assertTrue(fc.describe().contains("not readable"), fc.describe());
    }

    @Test
    @DisplayName("a large file is bounded to the trailing window and flagged truncated")
    void readFileTruncation(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("big.log");
        int over = PrivacyService.MAX_READ_BYTES + 2000;
        Files.writeString(f, "a".repeat(over));
        FileContent fc = PrivacyService.readFile(f.toString());
        assertTrue(fc.isExists());
        assertTrue(fc.isReadable());
        assertTrue(fc.isTruncated());
        assertEquals(PrivacyService.MAX_READ_BYTES, fc.getText().length());
        assertTrue(fc.describe().contains("KiB"), fc.describe());
    }

    @Test
    @DisplayName("the live torrc / log readers never throw and never return null")
    void liveFileReadsAreSafe() {
        assertDoesNotThrow(() -> {
            assertNotNull(PrivacyService.readTorrc());
            assertNotNull(PrivacyService.readTorLog());
        });
    }

    // ------------------------------------------------------------------
    // Non-spawning execution guards

    @Test
    @DisplayName("runRead refuses a mutating op without spawning")
    void runReadRejectsMutating() {
        for (Operation op : List.of(Operation.TOR_START, Operation.TOR_STOP, Operation.TOR_RESTART)) {
            ProcessRunner.Result r = PrivacyService.runRead(op);
            assertFalse(r.isStarted(), op + " must not be started by runRead");
            assertTrue(r.getStderr().contains("mutating"), r.getStderr());
        }
        assertFalse(PrivacyService.runRead(null).isStarted());
    }

    @Test
    @DisplayName("runMutating refuses a read-only op without spawning")
    void runMutatingRejectsReadOnly() {
        PrivilegedRunner.PrivilegedResult r = PrivacyService.runMutating(Operation.TOR_STATUS);
        assertEquals(PrivilegedRunner.Status.ERROR, r.getStatus());
        assertTrue(r.getMessage().contains("not mutating"), r.getMessage());
        assertEquals(PrivilegedRunner.Status.ERROR, PrivacyService.runMutating(null).getStatus());
    }
}
