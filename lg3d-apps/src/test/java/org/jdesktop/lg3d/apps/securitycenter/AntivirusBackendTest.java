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

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link AntivirusBackend}'s pure command / parser table against real
 * ClamAV output. Nothing here starts a process, so it runs headless.
 */
class AntivirusBackendTest {

    /** A real {@code clamscan -r} clean run, warnings and progress included. */
    private static final List<String> CLEAN = List.of(
            "LibClamAV Warning: **************************************************",
            "LibClamAV Warning: ***  The virus database is older than 7 days!  ***",
            "Loading:    11s, ETA:   0s [========================>]    8.68M/8.68M sigs",
            "Compiling:   3s, ETA:   0s [========================>]       41/41 tasks",
            "",
            "/tmp/sc-clean/a.txt: OK",
            "",
            "----------- SCAN SUMMARY -----------",
            "Known viruses: 8683852",
            "Engine version: 1.4.6",
            "Scanned directories: 1",
            "Scanned files: 1",
            "Infected files: 0",
            "Data scanned: 0.00 MB",
            "Data read: 0.00 MB (ratio 0.00:1)",
            "Time: 14.965 sec (0 m 14 s)",
            "Start Date: 2026:10:01 16:44:00",
            "End Date:   2026:10:01 16:44:15");

    @Test
    @DisplayName("resolveScanner prefers the user's choice, else the first known")
    void resolveScanner() {
        assertEquals(Optional.of("clamscan"),
                AntivirusBackend.resolveScanner("clamscan", s -> true));
        assertEquals(Optional.of("clamdscan"),
                AntivirusBackend.resolveScanner("", s -> true),
                "auto-detect picks clamdscan first when both are present");
        assertEquals(Optional.of("clamscan"),
                AntivirusBackend.resolveScanner("clamdscan", exe -> exe.equals("clamscan")),
                "an unavailable preference falls back to the first known scanner");
        assertEquals(Optional.empty(), AntivirusBackend.resolveScanner("clamscan", s -> false));
        assertEquals(Optional.empty(), AntivirusBackend.firstAvailableScanner(null));
        assertTrue(AntivirusBackend.KNOWN_SCANNERS.contains("clamdscan"));
        assertTrue(AntivirusBackend.KNOWN_SCANNERS.contains("clamscan"));
    }

    @Test
    @DisplayName("scanCommand builds an --infected clamscan line")
    void scanCommandBasic() {
        assertEquals(List.of("clamscan", "--infected", "--recursive", "/home/me"),
                AntivirusBackend.scanCommand("clamscan", "/home/me", true, false, ""));
    }

    @Test
    @DisplayName("quarantine adds --move and non-recursive drops --recursive")
    void scanCommandOptions() {
        List<String> quarantined = AntivirusBackend.scanCommand(
                "clamscan", "/home/me", false, true, "/home/me/.quarantine");
        assertTrue(quarantined.contains("--move=/home/me/.quarantine"));
        assertFalse(quarantined.contains("--recursive"));

        List<String> quarantineNoDir = AntivirusBackend.scanCommand(
                "clamscan", "/home/me", true, true, "  ");
        assertFalse(quarantineNoDir.stream().anyMatch(a -> a.startsWith("--move")),
                "quarantine without a folder is a no-op");
    }

    @Test
    @DisplayName("scanCommand is empty when a required input is blank")
    void scanCommandGuards() {
        assertTrue(AntivirusBackend.scanCommand(null, "/x", true, false, "").isEmpty());
        assertTrue(AntivirusBackend.scanCommand("clamscan", "  ", true, false, "").isEmpty());
    }

    @Test
    @DisplayName("update / version command builders")
    void otherCommands() {
        assertEquals(List.of("freshclam"), AntivirusBackend.updateCommand());
        assertEquals(List.of("clamscan", "--version"),
                AntivirusBackend.versionCommand("clamscan"));
        assertTrue(AntivirusBackend.versionCommand(" ").isEmpty());
    }

    @Test
    @DisplayName("a clean run parses to a clean report with the summary counters")
    void parseClean() {
        ScanReport report = AntivirusBackend.parseScanOutput(CLEAN, 0);
        assertTrue(report.ranSuccessfully());
        assertTrue(report.isClean());
        assertFalse(report.hasThreats());
        assertEquals(1, report.scannedFiles());
        assertEquals(1, report.scannedDirectories());
        assertEquals(0, report.infectedFiles());
        assertEquals(8683852L, report.knownViruses());
        assertEquals("1.4.6", report.engineVersion());
        assertEquals("14.965 sec (0 m 14 s)", report.timeSummary());
        assertTrue(report.detections().isEmpty());
        assertEquals(1, report.fileLinesSeen(), "the single OK line is counted");
        assertEquals(0, report.totalErrors());
    }

    @Test
    @DisplayName("an infected run yields a threat detection and is not clean")
    void parseInfected() {
        List<String> lines = List.of(
                "/tmp/eicar-test.txt: Win.Test.EICAR_HDB-1 FOUND",
                "----------- SCAN SUMMARY -----------",
                "Known viruses: 8683852",
                "Engine version: 1.4.6",
                "Scanned directories: 0",
                "Scanned files: 1",
                "Infected files: 1",
                "Time: 0.123 sec (0 m 0 s)");
        ScanReport report = AntivirusBackend.parseScanOutput(lines, 1);
        assertTrue(report.hasThreats());
        assertFalse(report.isClean());
        assertTrue(report.ranSuccessfully());
        assertEquals(1, report.infectedFiles());
        assertEquals(1, report.detectionCount());
        Detection d = report.detections().get(0);
        assertEquals("/tmp/eicar-test.txt", d.path());
        assertEquals("Win.Test.EICAR_HDB-1", d.threat());
        assertTrue(d.isInfected());
    }

    @Test
    @DisplayName("clamdscan with the daemon stopped is a failed run, not clean")
    void parseDaemonDown() {
        List<String> lines = List.of(
                "ERROR: Can't connect to clamd through /run/clamd.scan/clamd.ctl: Connection refused",
                "",
                "----------- SCAN SUMMARY -----------",
                "Infected files: 0",
                "Total errors: 1",
                "Time: 0.000 sec (0 m 0 s)");
        ScanReport report = AntivirusBackend.parseScanOutput(lines, 2);
        assertFalse(report.ranSuccessfully(), "nothing scanned + only errors = did not run");
        assertFalse(report.isClean());
        assertFalse(report.hasThreats());
        assertEquals(0, report.fileLinesSeen());
        assertTrue(report.totalErrors() > 0);
        assertTrue(report.errorMessage().startsWith("ERROR:"), report.errorMessage());
    }

    @Test
    @DisplayName("a per-file ERROR line is recorded but the scan still ran")
    void parseFileError() {
        List<String> lines = List.of(
                "/root/locked.dat: Can't open file or directory ERROR",
                "/home/me/ok.txt: OK",
                "----------- SCAN SUMMARY -----------",
                "Scanned files: 1",
                "Infected files: 0");
        ScanReport report = AntivirusBackend.parseScanOutput(lines, 2);
        assertTrue(report.ranSuccessfully(), "file lines were seen, so it ran");
        assertEquals(1, report.detectionCount());
        Detection d = report.detections().get(0);
        assertEquals(Detection.Status.ERROR, d.status());
        assertEquals("/root/locked.dat", d.path());
        assertFalse(report.hasThreats());
    }

    @Test
    @DisplayName("null / empty output yields a report that did not run")
    void parseEmpty() {
        ScanReport nullReport = AntivirusBackend.parseScanOutput(null, 0);
        assertFalse(nullReport.ranSuccessfully());
        assertFalse(nullReport.isClean());
        assertTrue(nullReport.detections().isEmpty());

        ScanReport emptyReport = AntivirusBackend.parseScanOutput(List.of(), 2);
        assertFalse(emptyReport.ranSuccessfully());
        assertEquals(ScanReport.empty().scannedFiles(), emptyReport.scannedFiles());
    }

    @Test
    @DisplayName("parseVersion reads the engine and database revisions")
    void parseVersion() {
        VersionInfo v = AntivirusBackend.parseVersion(
                "ClamAV 1.4.6/27171/Wed Jan 31 04:46:17 2024");
        assertEquals("1.4.6", v.engine());
        assertEquals("27171", v.database());
        assertTrue(v.isPresent());
        assertEquals("ClamAV 1.4.6 (db 27171)", v.toString());

        assertFalse(AntivirusBackend.parseVersion(null).isPresent());
        assertFalse(AntivirusBackend.parseVersion("  ").isPresent());
        assertEquals("unknown", VersionInfo.unknown().toString());
        assertEquals("1.4.6",
                AntivirusBackend.parseVersion("ClamAV 1.4.6\nnoise").engine(),
                "only the first line is parsed");
    }

    @Test
    @DisplayName("describeUpdate reports success and failure honestly")
    void describeUpdate() {
        String ok = AntivirusBackend.describeUpdate(List.of(
                "ClamAV update process started at Wed Oct  1 08:32:00 2025",
                "daily.cvd database is up-to-date (version: 27171, sigs: 2100000)"), 0);
        assertTrue(ok.contains("updated") || ok.contains("up to date"), ok);

        String plain = AntivirusBackend.describeUpdate(List.of(), 0);
        assertTrue(plain.contains("up to date"), plain);

        String failed = AntivirusBackend.describeUpdate(List.of(
                "ERROR: Can't create new /var/lib/clamav/daily.cvd"), 1);
        assertTrue(failed.contains("Could not update"), failed);
        assertTrue(failed.contains("administrator"), failed);
    }
}
