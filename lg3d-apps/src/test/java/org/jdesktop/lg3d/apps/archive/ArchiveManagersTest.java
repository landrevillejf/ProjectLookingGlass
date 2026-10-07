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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the Archive app's AWT-free engines: the {@link ZipManager}
 * create/list/extract round-trip and its Zip-Slip guard, and the
 * {@link TarManager} list/extract of a hand-built POSIX tar plus its own
 * path-traversal guard. Every test runs inside a JUnit {@link TempDir}, so it
 * is hermetic and CI-safe (no network, no fixed paths, no display).
 */
class ArchiveManagersTest {

    private final ZipManager zip = new ZipManager();
    private final TarManager tar = new TarManager();

    @TempDir
    Path temp;

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** Builds a ZIP holding the given entry-name -> content pairs. */
    private File buildZip(String name, String[][] entries) throws IOException {
        File zipFile = temp.resolve(name).toFile();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zipFile.toPath()))) {
            for (String[] e : entries) {
                out.putNextEntry(new ZipEntry(e[0]));
                out.write(e[1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zipFile;
    }

    // ------------------------------------------------------------------
    // ZIP
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ZIP create -> list -> extract round-trips the folder content")
    void zipRoundTrip() throws IOException {
        Path source = temp.resolve("src");
        write(source.resolve("a.txt"), "alpha");
        write(source.resolve("nested/b.txt"), "beta");
        File target = temp.resolve("out.zip").toFile();

        int created = zip.create(source.toFile(), target);
        assertEquals(2, created);

        List<String> listed = zip.list(target);
        assertTrue(listed.contains("a.txt"), listed.toString());
        assertTrue(listed.contains("nested/b.txt"), listed.toString());

        Path dest = temp.resolve("unzipped");
        Files.createDirectories(dest);
        int extracted = zip.extract(target, dest.toFile());
        assertEquals(2, extracted);
        assertEquals("alpha", read(dest.resolve("a.txt")));
        assertEquals("beta", read(dest.resolve("nested/b.txt")));
    }

    @Test
    @DisplayName("ZIP extraction skips Zip-Slip entries that escape the target")
    void zipSlipIsRejected() throws IOException {
        File malicious = buildZip("slip.zip", new String[][] {
                {"../evil.txt", "pwned"},
                {"safe.txt", "ok"},
        });
        Path dest = temp.resolve("guarded");
        Files.createDirectories(dest);

        int extracted = zip.extract(malicious, dest.toFile());

        assertEquals(1, extracted, "only the safe entry is written");
        assertTrue(Files.exists(dest.resolve("safe.txt")));
        assertFalse(Files.exists(temp.resolve("evil.txt")), "escapee must not be written");
    }

    // ------------------------------------------------------------------
    // TAR
    // ------------------------------------------------------------------

    @Test
    @DisplayName("TAR list and extract a hand-built archive")
    void tarRoundTrip() throws IOException {
        File tarFile = temp.resolve("sample.tar").toFile();
        try (OutputStream out = Files.newOutputStream(tarFile.toPath())) {
            writeTarEntry(out, "hello.txt", "world".getBytes(StandardCharsets.UTF_8), '0');
            writeTarEntry(out, "dir", new byte[0], '5');
            out.write(new byte[1024]); // two empty blocks terminate the archive
        }

        List<String> listed = tar.list(tarFile);
        assertTrue(listed.contains("hello.txt"), listed.toString());
        assertTrue(listed.contains("dir"), listed.toString());

        Path dest = temp.resolve("untarred");
        Files.createDirectories(dest);
        int extracted = tar.extract(tarFile, dest.toFile());
        assertEquals(1, extracted, "only the regular file counts");
        assertEquals("world", read(dest.resolve("hello.txt")));
        assertTrue(Files.isDirectory(dest.resolve("dir")));
    }

    @Test
    @DisplayName("TAR extraction skips entries that escape the target")
    void tarSlipIsRejected() throws IOException {
        File tarFile = temp.resolve("slip.tar").toFile();
        try (OutputStream out = Files.newOutputStream(tarFile.toPath())) {
            writeTarEntry(out, "../evil.txt", "pwned".getBytes(StandardCharsets.UTF_8), '0');
            out.write(new byte[1024]);
        }
        Path dest = temp.resolve("targuard");
        Files.createDirectories(dest);

        int extracted = tar.extract(tarFile, dest.toFile());

        assertEquals(0, extracted);
        assertFalse(Files.exists(temp.resolve("evil.txt")));
    }

    /** Writes one 512-byte header + padded content block for a tar entry. */
    private static void writeTarEntry(OutputStream out, String name, byte[] content, char type)
            throws IOException {
        byte[] header = new byte[512];
        put(header, 0, name);
        put(header, 100, "0000644");   // mode
        put(header, 124, octal(content.length)); // size
        put(header, 136, "00000000000"); // mtime
        put(header, 156, String.valueOf(type));
        // Checksum field (148..155) is treated as spaces while summing.
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        int sum = 0;
        for (byte b : header) {
            sum += (b & 0xFF);
        }
        // A real checksum field is 8 bytes: six octal digits, NUL, space. Keep it
        // inside 148..155 so it never overruns the type flag at 156.
        put(header, 148, String.format("%06o", sum));
        header[154] = 0;
        header[155] = ' ';

        out.write(header);
        out.write(content);
        int pad = (512 - (content.length % 512)) % 512;
        out.write(new byte[pad]);
    }

    private static void put(byte[] header, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, header.length - offset));
    }

    /** Right-justified, zero-padded 11-digit octal field (NUL terminated). */
    private static String octal(long value) {
        String o = Long.toOctalString(value);
        StringBuilder sb = new StringBuilder();
        for (int i = o.length(); i < 11; i++) {
            sb.append('0');
        }
        return sb.append(o).toString();
    }
}
