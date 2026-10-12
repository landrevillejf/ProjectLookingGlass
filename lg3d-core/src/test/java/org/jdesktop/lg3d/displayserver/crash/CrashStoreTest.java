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
package org.jdesktop.lg3d.displayserver.crash;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for the spool, with every file written under a temporary
 * directory. What is under proof is reliability and containment: one file per
 * bug with a count, both ceilings honoured, a write that cannot leave half a
 * document, and a signature that cannot escape the spool directory.
 */
class CrashStoreTest {

    @TempDir
    Path root;

    private CrashStore store;

    @BeforeEach
    void openStore() {
        store = new CrashStore(root, CrashStore.DEFAULT_MAX_REPORTS,
                CrashStore.DEFAULT_MAX_BYTES);
    }

    @AfterEach
    void clearOverrides() {
        System.clearProperty("lg.crashreport.dir");
        System.clearProperty("lg.crashreport.maxReports");
        System.clearProperty("lg.crashreport.maxBytes");
    }

    private static CrashReport report(String message, Instant when) {
        return CrashReport.builder()
                .message(message)
                .stack("java.lang.IllegalStateException: " + message + "\n"
                        + "\tat com.acme.Thing.doIt(Thing.java:7)")
                .occurredAt(when)
                .environment(ReportEnvironment.collect(when, name -> null,
                        Runtime.getRuntime(), "UTC"))
                .build();
    }

    /** The signature directory of a report, whichever name the store chose. */
    private Path directoryOf(CrashReport report) {
        return root.resolve(report.signature().replaceAll("[^A-Za-z0-9.-]", "_"));
    }

    @Test
    @DisplayName("a stored report is two files in a directory named by its signature")
    void firstStoreCreatesTheEntry() throws IOException {
        CrashReport report = report("texture lost", Instant.now());
        CrashStore.Stored stored = store.store(report);
        assertFalse(stored.duplicate());
        assertEquals(1, stored.occurrences());
        assertNotNull(stored.markdown());
        assertTrue(Files.exists(stored.markdown()));
        assertTrue(Files.readString(stored.markdown()).contains("texture lost"));
        String record = Files.readString(
                directoryOf(report).resolve("record.json"), StandardCharsets.UTF_8);
        assertTrue(record.contains("\"signature\": \"" + report.signature() + "\""));
        assertTrue(record.contains("\"occurrences\": \"1\""));
        // Nothing is left mid-write: the staging file is renamed, not kept.
        try (var files = Files.list(directoryOf(report))) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")),
                    "an atomic move must not leave a .part behind");
        }
    }

    @Test
    @DisplayName("the same bug twice is one entry counting two")
    void repeatsAccumulate() {
        CrashReport first = report("layout loop", Instant.now());
        store.store(first);
        CrashReport again = report("layout loop", Instant.now().plusSeconds(3));
        CrashStore.Stored stored = store.store(again);
        assertTrue(stored.duplicate());
        assertEquals(2, stored.occurrences());
        assertEquals(1, store.list().size(), "one bug is one entry, seen twice");
        assertTrue(stored.markdown().getFileName().toString().equals("report.md"),
                "the file name the first entry chose is kept for the replies that quoted it");
    }

    @Test
    @DisplayName("a session's own count is added to the archive's, not maxed")
    void countsAcrossSessions() {
        CrashReport report = report("socket closed", Instant.now());
        store.store(report);
        CrashStore.Stored ninth = store.store(CrashReport.builder()
                .signature(report.signature())
                .message("socket closed")
                .occurrences(8)
                .environment(ReportEnvironment.collect(Instant.now(), name -> null,
                        Runtime.getRuntime(), "UTC"))
                .build());
        assertEquals(9, ninth.occurrences());
        assertTrue(store.find(report.signature()).orElseThrow().body()
                .contains("seen 9\u00d7"), "the document the maintainer reads carries the count");
    }

    @Test
    @DisplayName("a report read back keeps identity, kind and trace")
    void roundTripIsFaithful() {
        CrashReport report = CrashReport.of(new IllegalStateException("printer offline"),
                CrashReport.Kind.SEVERE, false);
        store.store(report);
        CrashReport reread = store.find(report.signature()).orElseThrow();
        assertEquals(report.signature(), reread.signature());
        assertEquals(report.kind(), reread.kind());
        assertEquals(report.severity(), reread.severity());
        assertEquals(report.message(), reread.message());
        assertEquals(report.title(), reread.title(),
                "re-filing an archived crash must not open a second issue");
        assertTrue(reread.stack().contains("IllegalStateException"), reread.stack());
        assertEquals("spool", reread.reporter());
    }

    @Test
    @DisplayName("a hand-written report keeps its narrative through the archive")
    void manualNarrativeSurvivesTheArchive() {
        // The Bug Reporter's product is the text a person typed. An archive that
        // rebuilds a trace but drops the repro steps re-files the same bug under an
        // empty body and a subject the reporter never wrote - which is a duplicate
        // issue with none of the information the first one had.
        CrashReport draft = CrashReport.builder()
                .kind(CrashReport.Kind.MANUAL)
                .severity(CrashReport.Severity.TRIVIAL)
                // The identity the panel gives a draft with no throwable: the words
                // themselves, so two sightings of one bug are one archive entry.
                .signature(CrashSignature.ofNarrative("the task bar wraps at 1280 px"))
                .summary("the task bar wraps at 1280 px")
                .steps("resize the desktop\nlook at the task bar")
                .expected("one row of buttons")
                .actual("two rows, the second clipped")
                .addAttachment("taskbar.png")
                .reporter("BugReporterPanel")
                .occurredAt(Instant.parse("2026-10-12T12:00:00Z"))
                .environment(ReportEnvironment.collect(Instant.now(), name -> null,
                        Runtime.getRuntime(), "UTC"))
                .build();
        store.store(draft);
        CrashReport reread = store.find(draft.signature()).orElseThrow();
        assertEquals("", reread.message(),
                "the body's placeholder for an empty message is not itself a message");
        assertEquals(draft.title(), reread.title(),
                "the subject a person wrote is kept, not re-derived from a trace there is none of");
        assertEquals(draft.steps(), reread.steps());
        assertEquals(draft.expected(), reread.expected());
        assertEquals(draft.actual(), reread.actual());
        assertEquals(draft.attachments(), reread.attachments());
        assertEquals(draft.signature(), reread.signature(),
                "a manual report signs on its narrative, so the narrative must survive");
        assertTrue(reread.body().contains("1. resize the desktop"), reread.body());
        assertFalse(reread.body().contains("no manual steps recorded"),
                "a report a person wrote is never announced as an automatic one");
    }

    @Test
    @DisplayName("the archive keeps which component caught the failure")
    void reporterSurvivesTheArchive() throws IOException {
        // A list of stored crashes that all read "spool" cannot tell an uncaught
        // exception from a SEVERE log record, and that is the first question a
        // maintainer asks about a crash with no manual steps.
        CrashReport captured = CrashReport.of(new IllegalStateException("printer offline"),
                CrashReport.Kind.CRASH, false, "CrashWatcher");
        store.store(captured);
        assertEquals("CrashWatcher",
                store.find(captured.signature()).orElseThrow().reporter());
        // A record from an older build, or a report that named nobody, is only known
        // to have come out of the spool - it must not fail to read.
        Path legacy = root.resolve("legacy0000");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("record.json"),
                "{\"signature\": \"legacy0000\", \"occurrences\": \"2\","
                        + " \"kind\": \"SEVERE\"}",
                StandardCharsets.UTF_8);
        Files.writeString(legacy.resolve("report.md"), "---\n---\n\nbody\n",
                StandardCharsets.UTF_8);
        CrashReport reread = store.find("legacy0000").orElseThrow();
        assertEquals("spool", reread.reporter());
        assertEquals(2, reread.occurrences());
    }

    @Test
    @DisplayName("the archive lists newest first")
    void listIsOrderedByTime() {
        Instant now = Instant.now();
        store.store(report("old", now.minusSeconds(600)));
        store.store(report("middle", now.minusSeconds(60)));
        store.store(report("new", now));
        List<CrashReport> listed = store.list();
        assertEquals(3, listed.size());
        assertEquals("new", listed.get(0).message());
        assertEquals("old", listed.get(2).message());
    }

    @Test
    @DisplayName("the file-count ceiling evicts the oldest entries")
    void countCeilingEvicts() {
        // Five distinct bugs against a ceiling of two: a single prune pass has to
        // evict more than one entry, which is where an eviction loop written as a
        // for-each over the list it mutates used to die.
        CrashStore small = new CrashStore(root, 2, CrashStore.DEFAULT_MAX_BYTES);
        for (int i = 0; i < 5; i++) {
            small.store(report("bug " + i, Instant.now().plusSeconds(i)));
        }
        assertEquals(2, small.list().size(), "a bounded spool is the point of a spool");
        assertTrue(Files.exists(root), "eviction trims entries, it does not delete the store");
    }

    @Test
    @DisplayName("the byte ceiling evicts even below the file count")
    void byteCeilingEvicts() throws IOException {
        CrashStore tiny = new CrashStore(root, 100, 1024);
        for (int i = 0; i < 6; i++) {
            tiny.store(report("wide failure number " + i, Instant.now().plusSeconds(i)));
        }
        long used = 0;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                used += Files.size(path);
            }
        }
        assertTrue(used < 6 * 1024, "the archive must not grow past the ceiling: " + used);
        assertTrue(tiny.list().size() < 6);
    }

    @Test
    @DisplayName("a ceiling smaller than one report still keeps that report")
    void theNewestEntryIsNeverEvicted() {
        // A budget that discards the file it has just written is a delete, not a
        // budget: the dialog quotes the path, and a kiosk image with a 1 KiB
        // allowance would then archive nothing at all.
        CrashStore tiny = new CrashStore(root, 1, 1L);
        CrashReport report = report("only one fits", Instant.now());
        CrashStore.Stored stored = tiny.store(report);
        assertNotNull(stored.markdown());
        assertTrue(Files.exists(stored.markdown()), "the newest report must survive the prune");
        assertEquals(1, tiny.list().size());
    }

    @Test
    @DisplayName("a signature that tries to name a path outside the spool is neutralised")
    void pathEscapeIsRefused() throws IOException {
        CrashReport escaped = CrashReport.builder()
                .signature("../../escape")
                .message("escape attempt")
                .environment(ReportEnvironment.collect(Instant.now(), name -> null,
                        Runtime.getRuntime(), "UTC"))
                .build();
        CrashStore.Stored stored = store.store(escaped);
        Path written = stored.markdown().getParent();
        assertTrue(written.startsWith(root.toAbsolutePath())
                        || written.toAbsolutePath().startsWith(root.toAbsolutePath()),
                "the entry escaped the spool: " + written);
        assertFalse(Files.exists(root.resolve("..").resolve("escape")),
                "no directory may be created above the spool root");
    }

    @Test
    @DisplayName("a corrupt record is skipped, not fatal to the archive")
    void unreadableEntryIsSkipped() throws IOException {
        store.store(report("good", Instant.now()));
        Path broken = root.resolve("deadbeefdead");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("record.json"), "{ this is not json",
                StandardCharsets.UTF_8);
        List<CrashReport> listed = store.list();
        assertEquals(1, listed.size(), "one broken entry cannot blank the archive");
    }

    @Test
    @DisplayName("a missing record leaves the entry invisible but never throws")
    void missingRecordIsInvisible() throws IOException {
        Path halfWritten = root.resolve("abcabcabcabc");
        Files.createDirectories(halfWritten);
        Files.writeString(halfWritten.resolve("report.md"), "no record here",
                StandardCharsets.UTF_8);
        assertEquals(0, store.list().size());
        assertFalse(store.find("abcabcabcabc").isPresent());
    }

    @Test
    @DisplayName("delete removes the entry and reports what it did")
    void deleteRemovesTheDirectory() {
        CrashReport report = report("gone soon", Instant.now());
        store.store(report);
        assertTrue(store.delete(report.signature()));
        assertFalse(store.find(report.signature()).isPresent());
        assertFalse(store.delete(report.signature()), "a second delete finds nothing");
        assertFalse(store.delete(null));
        assertFalse(store.delete("  "));
        assertFalse(store.find(null).isPresent());
        assertFalse(store.find("").isPresent());
    }

    @Test
    @DisplayName("an unwritable spool costs the archive, never the caller")
    void unwritableSpoolDegrades() throws IOException {
        // A regular file sitting where the spool directory wants to be, so the
        // mkdirs inside store() cannot win. The store must still answer with an
        // outcome saying "nothing was kept": the dialog has to be shown either
        // way, and this is the error path - it cannot afford to throw a second
        // exception on top of the crash it was called to record.
        Path blocked = root.resolve("spool");
        Files.writeString(blocked, "not a directory", StandardCharsets.UTF_8);
        CrashStore broken = new CrashStore(blocked, 5, 4096L);
        CrashReport report = report("spool is broken", Instant.now());
        CrashStore.Stored stored = assertDoesNotThrow(() -> broken.store(report));
        assertNull(stored.markdown(), "no path may be quoted to the dialog");
        assertEquals(1, stored.occurrences());
        assertEquals(blocked, broken.root());
        assertTrue(broken.list().isEmpty());
        assertFalse(broken.find(report.signature()).isPresent());
        assertFalse(broken.delete(report.signature()));
    }

    @Test
    @DisplayName("the default store honours the directory and ceiling overrides")
    void defaultStoreReadsProperties() {
        System.setProperty("lg.crashreport.dir", root.toString());
        System.setProperty("lg.crashreport.maxReports", "3");
        System.setProperty("lg.crashreport.maxBytes", "4096");
        CrashStore configured = CrashStore.defaultStore();
        assertEquals(Path.of(root.toString()), configured.root());
        assertNotNull(configured.store(report("via properties", Instant.now())).markdown());
    }

    @Test
    @DisplayName("a garbage ceiling property falls back to the default")
    void defaultStoreFallsBack() {
        System.setProperty("lg.crashreport.dir", "  ");
        System.setProperty("lg.crashreport.maxReports", "not-a-number");
        System.setProperty("lg.crashreport.maxBytes", "-1");
        CrashStore fallback = CrashStore.defaultStore();
        assertNotNull(fallback.root());
        assertTrue(fallback.root().toString().contains("crash-reports"), fallback.root().toString());
    }

    @Test
    @DisplayName("the store refuses a ceiling below one small report")
    void ceilingsAreFloored() {
        CrashStore floored = new CrashStore(root, 0, 1L);
        assertNotNull(floored.store(report("floored", Instant.now())).markdown());
        assertEquals(root, floored.root());
    }
}
