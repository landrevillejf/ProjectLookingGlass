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
package org.jdesktop.lg3d.apps.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the Archive app's multi-format {@link ArchiveManager} engine:
 * create -> list -> extract round-trips across every format it can write (ZIP
 * and the whole TAR family), reading of a lone single-file compressor stream,
 * the {@code describe} format labels, and the Zip-Slip / tar path-traversal
 * guards. Every test runs inside a JUnit {@link TempDir}, so it is hermetic and
 * CI-safe (no network, no fixed paths, no display).
 */
class ArchiveManagerTest {

    private final ArchiveManager manager = new ArchiveManager();

    @TempDir
    Path temp;

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Creates a source tree holding {@code a.txt} and {@code nested/b.txt}. */
    private Path makeSource() throws IOException {
        Path source = temp.resolve("src");
        write(source.resolve("a.txt"), "alpha");
        write(source.resolve("nested/b.txt"), "beta");
        return source;
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Round-trips across every writable format
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "out.zip", "out.tar", "out.tar.gz", "out.tgz",
            "out.tar.bz2", "out.tbz2", "out.tar.xz", "out.txz",
    })
    @DisplayName("create -> list -> extract round-trips the folder content")
    void roundTrips(String fileName) throws IOException {
        Path source = makeSource();
        File target = temp.resolve(fileName).toFile();

        int created = manager.create(source.toFile(), target);
        assertEquals(2, created);

        List<String> listed = manager.list(target);
        assertTrue(listed.contains("a.txt"), listed.toString());
        assertTrue(listed.contains("nested/b.txt"), listed.toString());

        Path dest = temp.resolve("unpacked-" + fileName.replace('.', '_'));
        Files.createDirectories(dest);
        int extracted = manager.extract(target, dest.toFile());
        assertEquals(2, extracted, "only the regular files count");
        assertEquals("alpha", read(dest.resolve("a.txt")));
        assertEquals("beta", read(dest.resolve("nested/b.txt")));
    }

    // ------------------------------------------------------------------
    // Single-file compressor stream
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reads a lone single-file compressed stream (.gz)")
    void readsSingleCompressedFile() throws IOException {
        File gz = temp.resolve("notes.gz").toFile();
        try (OutputStream out = new GzipCompressorOutputStream(Files.newOutputStream(gz.toPath()))) {
            out.write("hello".getBytes(StandardCharsets.UTF_8));
        }

        List<String> listed = manager.list(gz);
        assertEquals(List.of("notes"), listed);

        Path dest = temp.resolve("gunzipped");
        Files.createDirectories(dest);
        int extracted = manager.extract(gz, dest.toFile());
        assertEquals(1, extracted);
        assertEquals("hello", read(dest.resolve("notes")));
    }

    // ------------------------------------------------------------------
    // describe()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("describe() labels the detected formats")
    void describeLabelsFormats() throws IOException {
        Path source = makeSource();
        File zip = temp.resolve("d.zip").toFile();
        File tar = temp.resolve("d.tar").toFile();
        File tgz = temp.resolve("d.tar.gz").toFile();
        manager.create(source.toFile(), zip);
        manager.create(source.toFile(), tar);
        manager.create(source.toFile(), tgz);

        assertEquals("ZIP", manager.describe(zip));
        assertEquals("TAR", manager.describe(tar));
        assertEquals("TAR.GZ", manager.describe(tgz));
    }

    // ------------------------------------------------------------------
    // Path-traversal guards
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ZIP extraction skips Zip-Slip entries that escape the target")
    void zipSlipIsRejected() throws IOException {
        File malicious = temp.resolve("slip.zip").toFile();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(malicious.toPath()))) {
            out.putNextEntry(new ZipEntry("../evil.txt"));
            out.write("pwned".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("safe.txt"));
            out.write("ok".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Path dest = temp.resolve("guarded");
        Files.createDirectories(dest);

        int extracted = manager.extract(malicious, dest.toFile());

        assertEquals(1, extracted, "only the safe entry is written");
        assertTrue(Files.exists(dest.resolve("safe.txt")));
        assertFalse(Files.exists(temp.resolve("evil.txt")), "escapee must not be written");
    }

    @Test
    @DisplayName("TAR extraction skips entries that escape the target")
    void tarSlipIsRejected() throws IOException {
        File tarFile = temp.resolve("slip.tar").toFile();
        try (TarArchiveOutputStream out =
                     new TarArchiveOutputStream(Files.newOutputStream(tarFile.toPath()))) {
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            byte[] data = "pwned".getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry entry = new TarArchiveEntry("../evil.txt");
            entry.setSize(data.length);
            out.putArchiveEntry(entry);
            out.write(data);
            out.closeArchiveEntry();
        }
        Path dest = temp.resolve("targuard");
        Files.createDirectories(dest);

        int extracted = manager.extract(tarFile, dest.toFile());

        assertEquals(0, extracted);
        assertFalse(Files.exists(temp.resolve("evil.txt")), "escapee must not be written");
    }
}
