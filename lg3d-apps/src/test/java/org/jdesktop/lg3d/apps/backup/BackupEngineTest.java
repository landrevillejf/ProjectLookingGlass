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
package org.jdesktop.lg3d.apps.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link BackupEngine}: the backup/restore round-trip, glob
 * excludes, hidden-file handling, the Zip-Slip path-traversal guard, directory
 * preservation, cancellation, compression, archive listing and progress
 * reporting. Every test runs entirely inside a JUnit {@link TempDir}, so it is
 * hermetic and CI-safe (no network, no fixed paths).
 */
class BackupEngineTest {

    private final BackupEngine engine = new BackupEngine();

    @TempDir
    Path temp;

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** A profile backing up {@code source} into {@code destDir}, no timestamp. */
    private BackupProfile profile(Path source, Path destDir, String baseName) {
        BackupProfile p = new BackupProfile("Test", destDir.toString(), baseName);
        p.setTimestampArchiveName(false);
        p.getSources().add(source.toString());
        return p;
    }

    /** Builds a folder tree: data/a.txt, data/sub/b.txt, data/note.log. */
    private Path sampleTree() throws IOException {
        Path data = temp.resolve("data");
        write(data.resolve("a.txt"), "alpha");
        write(data.resolve("sub").resolve("b.txt"), "beta");
        write(data.resolve("note.log"), "transient");
        return data;
    }

    private List<String> entryNames(Path archive) throws IOException {
        List<String> names = new ArrayList<>();
        for (ArchiveEntryInfo e : engine.list(archive)) {
            names.add(e.getName());
        }
        return names;
    }

    /** Writes a hostile ZIP containing a single entry with the given raw name. */
    private Path evilZip(String entryName, String content) throws IOException {
        Path zip = temp.resolve("evil.zip");
        try (OutputStream fos = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return zip;
    }

    // ------------------------------------------------------------------
    // Backup
    // ------------------------------------------------------------------

    @Test
    @DisplayName("backup creates a ZIP holding every source entry")
    void backupCreatesArchive() throws Exception {
        Path data = sampleTree();
        Path dest = temp.resolve("out");
        BackupResult r = engine.backup(profile(data, dest, "arc"), null, null);

        assertTrue(r.isSuccess(), r.getMessage());
        Path archive = dest.resolve("arc.zip");
        assertTrue(Files.isRegularFile(archive), "archive should exist");
        assertEquals(archive, r.getArchive());
        assertTrue(r.getEntryCount() >= 3, "at least the 3 files are archived");
        List<String> names = entryNames(archive);
        assertTrue(names.contains("data/a.txt"), names.toString());
        assertTrue(names.contains("data/sub/b.txt"), names.toString());
        assertTrue(names.contains("data/note.log"), names.toString());
    }

    @Test
    @DisplayName("no .part temp file survives a successful backup")
    void atomicWriteLeavesNoTemp() throws Exception {
        Path data = sampleTree();
        Path dest = temp.resolve("out");
        engine.backup(profile(data, dest, "arc"), null, null);
        assertFalse(Files.exists(dest.resolve("arc.zip.part")),
                "the streaming temp file must be moved onto the target");
        assertTrue(Files.isRegularFile(dest.resolve("arc.zip")));
    }

    @Test
    @DisplayName("a timestamped archive name matches base-<stamp>.zip")
    void timestampedArchiveName() throws Exception {
        Path data = sampleTree();
        Path dest = temp.resolve("out");
        BackupProfile p = profile(data, dest, "snap");
        p.setTimestampArchiveName(true);
        BackupResult r = engine.backup(p, null, null);
        assertTrue(r.isSuccess(), r.getMessage());
        String fileName = r.getArchive().getFileName().toString();
        assertTrue(fileName.startsWith("snap-") && fileName.endsWith(".zip"), fileName);
        assertTrue(fileName.matches("snap-\\d{8}-\\d{6}\\.zip"), fileName);
    }

    @Test
    @DisplayName("backing up a single file archives just that file name")
    void backupSingleFile() throws Exception {
        Path file = write(temp.resolve("loose").resolve("report.txt"), "hello");
        Path dest = temp.resolve("out");
        BackupResult r = engine.backup(profile(file, dest, "one"), null, null);
        assertTrue(r.isSuccess(), r.getMessage());
        List<String> names = entryNames(dest.resolve("one.zip"));
        assertTrue(names.contains("report.txt"), names.toString());
    }

    @Test
    @DisplayName("exclude globs drop matching files but keep the rest")
    void excludesSkipMatchingFiles() throws Exception {
        Path data = sampleTree();
        Path dest = temp.resolve("out");
        BackupProfile p = profile(data, dest, "arc");
        p.setExcludePatternsFromCsv("*.log");
        BackupResult r = engine.backup(p, null, null);
        assertTrue(r.isSuccess(), r.getMessage());
        List<String> names = entryNames(dest.resolve("arc.zip"));
        assertFalse(names.contains("data/note.log"), "*.log must be excluded: " + names);
        assertTrue(names.contains("data/a.txt"), names.toString());
        assertTrue(names.contains("data/sub/b.txt"), names.toString());
    }

    @Test
    @DisplayName("hidden files are skipped by default and kept when opted in")
    void hiddenFileHandling() throws Exception {
        Path data = temp.resolve("data");
        write(data.resolve(".secret"), "hidden");
        write(data.resolve("visible.txt"), "shown");
        Path dest = temp.resolve("out");

        BackupResult def = engine.backup(profile(data, dest, "def"), null, null);
        assertTrue(def.isSuccess(), def.getMessage());
        List<String> defNames = entryNames(dest.resolve("def.zip"));
        assertFalse(defNames.contains("data/.secret"), "hidden skipped by default: " + defNames);
        assertTrue(defNames.contains("data/visible.txt"), defNames.toString());

        BackupProfile inc = profile(data, dest, "inc");
        inc.setIncludeHidden(true);
        BackupResult r2 = engine.backup(inc, null, null);
        assertTrue(r2.isSuccess(), r2.getMessage());
        assertTrue(entryNames(dest.resolve("inc.zip")).contains("data/.secret"),
                "hidden kept when includeHidden is set");
    }

    @Test
    @DisplayName("a missing source is reported as a warning, not a crash")
    void missingSourceIsWarned() throws Exception {
        Path dest = temp.resolve("out");
        BackupProfile p = new BackupProfile("Ghost", dest.toString(), "ghost");
        p.setTimestampArchiveName(false);
        p.getSources().add(temp.resolve("does-not-exist").toString());
        BackupResult r = engine.backup(p, null, null);
        assertTrue(r.isSuccess(), "an all-missing backup still yields an empty archive");
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("does not exist")),
                r.getWarnings().toString());
    }

    @Test
    @DisplayName("a higher compression level yields a smaller archive")
    void compressionLevelAffectsSize() throws Exception {
        Path data = temp.resolve("big");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            sb.append("AAAAAAAAAA");
        }
        write(data.resolve("repetitive.txt"), sb.toString());

        Path dest = temp.resolve("out");
        BackupProfile none = profile(data, dest, "lvl0");
        none.setCompressionLevel(0);
        BackupProfile best = profile(data, dest, "lvl9");
        best.setCompressionLevel(9);
        assertTrue(engine.backup(none, null, null).isSuccess());
        assertTrue(engine.backup(best, null, null).isSuccess());

        long size0 = Files.size(dest.resolve("lvl0.zip"));
        long size9 = Files.size(dest.resolve("lvl9.zip"));
        assertTrue(size9 < size0, "level 9 (" + size9 + ") should beat level 0 (" + size0 + ")");
    }

    @Test
    @DisplayName("cancelling before the first entry aborts and leaves no archive")
    void cancelAbortsBackup() throws Exception {
        Path data = sampleTree();
        Path dest = temp.resolve("out");
        BackupResult r = engine.backup(profile(data, dest, "arc"), null, () -> true);
        assertTrue(r.isCancelled(), "run should report cancelled");
        assertFalse(r.isSuccess());
        assertFalse(Files.exists(dest.resolve("arc.zip")), "no archive on cancel");
        assertFalse(Files.exists(dest.resolve("arc.zip.part")), "temp cleaned up on cancel");
    }

    @Test
    @DisplayName("the progress listener fires once per entry with growing counts")
    void progressListenerFires() throws Exception {
        Path data = sampleTree();
        Path dest = temp.resolve("out");
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger lastDone = new AtomicInteger();
        BackupProgressListener pl = (entry, done, total, bd, bt) -> {
            calls.incrementAndGet();
            assertTrue(done >= lastDone.get(), "entriesDone must be monotonic");
            lastDone.set((int) done);
            assertNotNull(entry);
        };
        BackupResult r = engine.backup(profile(data, dest, "arc"), pl, null);
        assertTrue(r.isSuccess(), r.getMessage());
        assertTrue(calls.get() >= 3, "at least one callback per file: " + calls.get());
        assertTrue(lastDone.get() > 0);
    }

    // ------------------------------------------------------------------
    // Restore / round-trip
    // ------------------------------------------------------------------

    @Test
    @DisplayName("backup then restore recreates the original files and content")
    void roundTripRestoresContent() throws Exception {
        Path data = sampleTree();
        Path dest = temp.resolve("out");
        BackupResult b = engine.backup(profile(data, dest, "arc"), null, null);
        assertTrue(b.isSuccess(), b.getMessage());

        Path restoreInto = temp.resolve("restored");
        BackupResult r = engine.restore(dest.resolve("arc.zip"), restoreInto, null, null);
        assertTrue(r.isSuccess(), r.getMessage());

        Path root = restoreInto.resolve("data");
        assertEquals("alpha", read(root.resolve("a.txt")));
        assertEquals("beta", read(root.resolve("sub").resolve("b.txt")));
        assertEquals("transient", read(root.resolve("note.log")));
    }

    @Test
    @DisplayName("restore recreates nested directory structure")
    void restoreRecreatesDirectories() throws Exception {
        Path data = temp.resolve("data");
        write(data.resolve("x/y/z/deep.txt"), "deep");
        Path dest = temp.resolve("out");
        engine.backup(profile(data, dest, "arc"), null, null);

        Path restoreInto = temp.resolve("restored");
        BackupResult r = engine.restore(dest.resolve("arc.zip"), restoreInto, null, null);
        assertTrue(r.isSuccess(), r.getMessage());
        assertTrue(Files.isDirectory(restoreInto.resolve("data/x/y/z")));
        assertEquals("deep", read(restoreInto.resolve("data/x/y/z/deep.txt")));
    }

    @Test
    @DisplayName("restore preserves an empty directory")
    void restorePreservesEmptyDir() throws Exception {
        Path data = temp.resolve("data");
        Files.createDirectories(data.resolve("emptydir"));
        write(data.resolve("keep.txt"), "k");
        Path dest = temp.resolve("out");
        engine.backup(profile(data, dest, "arc"), null, null);

        Path restoreInto = temp.resolve("restored");
        engine.restore(dest.resolve("arc.zip"), restoreInto, null, null);
        assertTrue(Files.isDirectory(restoreInto.resolve("data/emptydir")),
                "the empty folder must survive the round-trip");
    }

    @Test
    @DisplayName("Zip Slip: a ../ traversal entry is rejected and cannot escape")
    void zipSlipTraversalIsRejected() throws Exception {
        Path evil = evilZip("../escaped.txt", "pwned");
        Path restoreInto = temp.resolve("sandbox");
        Files.createDirectories(restoreInto);

        BackupResult r = engine.restore(evil, restoreInto, null, null);
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("path traversal")),
                r.getWarnings().toString());
        // The file must NOT have been written outside the sandbox (nor inside).
        assertFalse(Files.exists(temp.resolve("escaped.txt")),
                "Zip Slip must be neutralised: no escape into the temp root");
        assertFalse(Files.exists(restoreInto.resolve("escaped.txt")),
                "and nothing written inside the sandbox either");
    }

    @Test
    @DisplayName("Zip Slip: an absolute entry name is rejected")
    void zipSlipAbsoluteEntryIsRejected() throws Exception {
        Path target = temp.resolve("elsewhere").resolve("abs.txt");
        Path evil = evilZip(target.toAbsolutePath().toString(), "pwned");
        Path restoreInto = temp.resolve("sandbox");

        BackupResult r = engine.restore(evil, restoreInto, null, null);
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("path traversal")),
                r.getWarnings().toString());
        assertFalse(Files.exists(target), "an absolute entry must not be written");
    }

    @Test
    @DisplayName("restore mixes safe entries with a rejected traversal entry")
    void restoreSkipsOnlyUnsafeEntries() throws Exception {
        // A zip with one good entry and one traversal entry: the good one lands,
        // the bad one is refused.
        Path zip = temp.resolve("mixed.zip");
        try (OutputStream fos = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            zos.putNextEntry(new ZipEntry("good/safe.txt"));
            zos.write("ok".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("../bad.txt"));
            zos.write("no".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        Path restoreInto = temp.resolve("sandbox");
        BackupResult r = engine.restore(zip, restoreInto, null, null);
        assertEquals("ok", read(restoreInto.resolve("good/safe.txt")));
        assertFalse(Files.exists(restoreInto.resolve("../bad.txt").normalize()));
        assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("path traversal")));
    }

    @Test
    @DisplayName("restore reports cancellation and stops early")
    void cancelAbortsRestore() throws Exception {
        Path data = sampleTree();
        Path dest = temp.resolve("out");
        engine.backup(profile(data, dest, "arc"), null, null);
        Path restoreInto = temp.resolve("restored");
        BackupResult r = engine.restore(dest.resolve("arc.zip"), restoreInto, null, () -> true);
        assertTrue(r.isCancelled());
        assertFalse(r.isSuccess());
    }

    // ------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("list reports entry names, sizes and directory flags")
    void listDescribesEntries() throws Exception {
        Path data = sampleTree();
        Path dest = temp.resolve("out");
        engine.backup(profile(data, dest, "arc"), null, null);

        List<ArchiveEntryInfo> entries = engine.list(dest.resolve("arc.zip"));
        assertFalse(entries.isEmpty());
        ArchiveEntryInfo a = entries.stream()
                .filter(e -> e.getName().equals("data/a.txt"))
                .findFirst().orElseThrow();
        assertEquals(5, a.getSize(), "alpha is 5 bytes");
        assertFalse(a.isDirectory());

        ArchiveEntryInfo dir = entries.stream()
                .filter(e -> e.getName().equals("data/sub/"))
                .findFirst().orElseThrow();
        assertTrue(dir.isDirectory(), "the nested folder is a directory entry");
    }

    @Test
    @DisplayName("backup with a null profile fails cleanly")
    void nullProfileFailsCleanly() {
        BackupResult r = engine.backup(null, null, null);
        assertFalse(r.isSuccess());
        assertNotNull(r.getMessage());
    }

    @Test
    @DisplayName("restore of a nonexistent archive fails cleanly")
    void restoreMissingArchiveFailsCleanly() {
        BackupResult r = engine.restore(temp.resolve("nope.zip"), temp.resolve("x"), null, null);
        assertFalse(r.isSuccess());
        assertTrue(r.getMessage().toLowerCase().contains("restore failed"), r.getMessage());
    }
}
