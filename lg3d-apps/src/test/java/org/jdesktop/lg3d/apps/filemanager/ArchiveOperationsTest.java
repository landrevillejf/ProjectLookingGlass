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
package org.jdesktop.lg3d.apps.filemanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ArchiveOperations}: the zip create/extract round-trip,
 * directory-entry convention, the Zip-Slip path-traversal guard, archive
 * detection, atomic write and progress reporting. Every test runs inside a
 * JUnit {@link TempDir}, so it is hermetic and CI-safe.
 */
class ArchiveOperationsTest {

    @TempDir
    Path temp;

    private Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** data/a.txt, data/sub/b.txt. */
    private Path sampleTree() throws IOException {
        Path data = temp.resolve("data");
        write(data.resolve("a.txt"), "alpha");
        write(data.resolve("sub").resolve("b.txt"), "beta");
        return data;
    }

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

    @Test
    @DisplayName("createZip archives a folder under its own name")
    void createZipArchivesFolder() throws Exception {
        Path data = sampleTree();
        Path zip = temp.resolve("out.zip");
        assertTrue(ArchiveOperations.createZip(List.of(data), zip, null));
        assertTrue(Files.isRegularFile(zip));
        List<String> names = ArchiveOperations.list(zip);
        assertTrue(names.contains("data/a.txt"), names.toString());
        assertTrue(names.contains("data/sub/b.txt"), names.toString());
        assertTrue(names.contains("data/"), "folders are stored as '/'-terminated entries: " + names);
    }

    @Test
    @DisplayName("a single file archives under just its name")
    void createZipSingleFile() throws Exception {
        Path file = write(temp.resolve("loose").resolve("report.txt"), "hello");
        Path zip = temp.resolve("one.zip");
        assertTrue(ArchiveOperations.createZip(List.of(file), zip, null));
        assertTrue(ArchiveOperations.list(zip).contains("report.txt"));
    }

    @Test
    @DisplayName("no .part temp file survives a successful createZip")
    void atomicWriteLeavesNoTemp() throws Exception {
        Path data = sampleTree();
        Path zip = temp.resolve("arc.zip");
        ArchiveOperations.createZip(List.of(data), zip, null);
        assertFalse(Files.exists(temp.resolve("arc.zip.part")));
        assertTrue(Files.isRegularFile(zip));
    }

    @Test
    @DisplayName("createZip never archives its own output into itself")
    void createZipSkipsOwnOutput() throws Exception {
        Path data = sampleTree();
        Path zip = data.resolve("nested.zip"); // inside the archived tree
        assertTrue(ArchiveOperations.createZip(List.of(data), zip, null));
        List<String> names = ArchiveOperations.list(zip);
        assertFalse(names.contains("data/nested.zip"),
                "the archive must not contain itself: " + names);
        assertTrue(names.contains("data/a.txt"), names.toString());
    }

    @Test
    @DisplayName("create/extract round-trips content and structure")
    void roundTrip() throws Exception {
        Path data = sampleTree();
        Path zip = temp.resolve("rt.zip");
        assertTrue(ArchiveOperations.createZip(List.of(data), zip, null));

        Path dest = temp.resolve("extracted");
        assertTrue(ArchiveOperations.extract(zip, dest, null));
        assertEquals("alpha", read(dest.resolve("data/a.txt")));
        assertEquals("beta", read(dest.resolve("data/sub/b.txt")));
    }

    @Test
    @DisplayName("extract reports byte progress through the callback")
    void extractReportsProgress() throws Exception {
        Path data = sampleTree();
        Path zip = temp.resolve("prog.zip");
        ArchiveOperations.createZip(List.of(data), zip, null);
        AtomicLong last = new AtomicLong();
        ArchiveOperations.extract(zip, temp.resolve("p"), last::set);
        assertTrue(last.get() >= "alpha".length() + "beta".length(),
                "progress should reach at least the extracted byte count");
    }

    @Test
    @DisplayName("extract rejects a ../ traversal entry (Zip Slip)")
    void extractBlocksZipSlipTraversal() throws Exception {
        Path zip = evilZip("../../escaped.txt", "pwned");
        Path dest = temp.resolve("safe/sub");
        ArchiveOperations.extract(zip, dest, null);
        assertFalse(Files.exists(temp.resolve("escaped.txt")),
                "a traversal entry must never escape the destination");
        assertFalse(Files.exists(temp.resolve("safe/escaped.txt")));
    }

    @Test
    @DisplayName("extract rejects an absolute-path entry (Zip Slip)")
    void extractBlocksAbsolutePath() throws Exception {
        Path target = temp.resolve("absolute-evil.txt");
        Path zip = evilZip(target.toAbsolutePath().toString(), "pwned");
        Path dest = temp.resolve("safe2");
        ArchiveOperations.extract(zip, dest, null);
        assertFalse(Files.exists(target), "an absolute entry must not write outside dest");
    }

    @Test
    @DisplayName("isArchive recognises the zip family by extension")
    void isArchiveDetection() {
        assertTrue(ArchiveOperations.isArchive(Path.of("x.zip")));
        assertTrue(ArchiveOperations.isArchive(Path.of("x.JAR")));
        assertTrue(ArchiveOperations.isArchive(Path.of("x.war")));
        assertTrue(ArchiveOperations.isArchive(Path.of("x.apk")));
        assertFalse(ArchiveOperations.isArchive(Path.of("x.tar.gz")));
        assertFalse(ArchiveOperations.isArchive(Path.of("x.txt")));
        assertFalse(ArchiveOperations.isArchive(Path.of("noext")));
        assertFalse(ArchiveOperations.isArchive(null));
    }

    @Test
    @DisplayName("name helpers derive the archive and base names")
    void nameHelpers() {
        assertEquals("data.zip", ArchiveOperations.defaultArchiveName(Path.of("/tmp/data")));
        assertEquals("data", ArchiveOperations.baseName(Path.of("/tmp/data.zip")));
        assertEquals("archive.zip", ArchiveOperations.defaultArchiveName(Path.of("/")));
    }

    @Test
    @DisplayName("createZip on an empty selection returns false without writing")
    void createZipEmptySelection() {
        Path zip = temp.resolve("empty.zip");
        assertFalse(ArchiveOperations.createZip(new ArrayList<>(), zip, null));
        assertFalse(Files.exists(zip));
    }

    @Test
    @DisplayName("extract of a missing archive returns false")
    void extractMissingArchive() {
        assertFalse(ArchiveOperations.extract(temp.resolve("nope.zip"), temp.resolve("d"), null));
    }
}
