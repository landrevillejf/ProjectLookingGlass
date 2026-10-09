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
package org.jdesktop.lg3d.apps.texteditor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link TextFileIO}: the load guards (directory, binary,
 * oversize), BOM and charset detection, EOL detect / normalise / denormalise
 * round-trips, and the atomic save (content, permission inheritance, parent
 * directory creation).
 */
class TextFileIOTest {

    @Test
    @DisplayName("a plain UTF-8 file round-trips with LF endings")
    void plainRoundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("hello.txt");
        Files.writeString(file, "line1\nline2\n", StandardCharsets.UTF_8);

        TextFileIO.LoadResult result = TextFileIO.load(file);
        assertEquals("line1\nline2\n", result.text());
        assertEquals(StandardCharsets.UTF_8, result.charset());
        assertEquals(TextFileIO.EOL_LF, result.eol());
        assertNull(result.warning());

        TextFileIO.save(file, result.text(), result.charset(), result.eol());
        assertEquals("line1\nline2\n",
                Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("CRLF files are detected, normalised and restored on save")
    void crlfRoundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("dos.txt");
        Files.write(file, "a\r\nb\r\nc\r\n".getBytes(StandardCharsets.UTF_8));

        TextFileIO.LoadResult result = TextFileIO.load(file);
        assertEquals("a\nb\nc\n", result.text());
        assertEquals(TextFileIO.EOL_CRLF, result.eol());

        TextFileIO.save(file, result.text(), result.charset(), result.eol());
        assertArrayEquals("a\r\nb\r\nc\r\n".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(file));
    }

    @Test
    @DisplayName("the dominant convention wins for mixed endings")
    void mixedEndings() {
        assertEquals(TextFileIO.EOL_CRLF,
                TextFileIO.detectEol("a\r\nb\r\nc\n"));
        assertEquals(TextFileIO.EOL_LF,
                TextFileIO.detectEol("a\r\nb\nc\n"));
        assertEquals("a\nb\nc", TextFileIO.normalizeEol("a\r\nb\rc"));
        assertEquals("a\r\nb", TextFileIO.denormalizeEol("a\nb",
                TextFileIO.EOL_CRLF));
        assertEquals("a\nb", TextFileIO.denormalizeEol("a\nb",
                TextFileIO.EOL_LF));
    }

    @Test
    @DisplayName("BOMs pick the charset and are stripped from the text")
    void bomDetection(@TempDir Path dir) throws IOException {
        Path utf8 = dir.resolve("bom8.txt");
        Files.write(utf8, concat(new byte[] {(byte) 0xEF, (byte) 0xBB,
                (byte) 0xBF}, "caf\u00E9".getBytes(StandardCharsets.UTF_8)));
        TextFileIO.LoadResult r8 = TextFileIO.load(utf8);
        assertEquals(StandardCharsets.UTF_8, r8.charset());
        assertEquals("caf\u00E9", r8.text());

        Path utf16le = dir.resolve("bom16.txt");
        Files.write(utf16le, concat(new byte[] {(byte) 0xFF, (byte) 0xFE},
                "hi".getBytes(StandardCharsets.UTF_16LE)));
        TextFileIO.LoadResult r16 = TextFileIO.load(utf16le);
        assertEquals(StandardCharsets.UTF_16LE, r16.charset());
        assertEquals("hi", r16.text());
    }

    @Test
    @DisplayName("invalid UTF-8 falls back to ISO-8859-1 with a warning")
    void charsetFallback(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("latin.txt");
        // 0xE9 is "e acute" in ISO-8859-1 but invalid standalone UTF-8.
        Files.write(file, new byte[] {'c', 'a', 'f', (byte) 0xE9});
        TextFileIO.LoadResult result = TextFileIO.load(file);
        assertEquals(StandardCharsets.ISO_8859_1, result.charset());
        assertEquals("caf\u00E9", result.text());
        assertNotNull(result.warning());
        assertTrue(result.warning().contains("ISO-8859-1"));
    }

    @Test
    @DisplayName("binary files (NUL bytes) are refused")
    void binaryRejected(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("blob.bin");
        Files.write(file, new byte[] {'E', 'L', 'F', 0, 1, 2});
        IOException ioe =
                assertThrows(IOException.class, () -> TextFileIO.load(file));
        assertTrue(ioe.getMessage().toLowerCase().contains("binary"));
        assertTrue(TextFileIO.looksBinary(new byte[] {'a', 0}));
        assertFalse(TextFileIO.looksBinary(new byte[] {'a', 'b'}));
    }

    @Test
    @DisplayName("directories, missing files and oversize files are refused")
    void loadGuards(@TempDir Path dir) throws IOException {
        assertThrows(IOException.class, () -> TextFileIO.load(dir));
        assertThrows(IOException.class,
                () -> TextFileIO.load(dir.resolve("nope.txt")));
        assertThrows(IOException.class, () -> TextFileIO.load(null));

        // Sparse file just over the cap: the size check fires before any
        // read, so this stays cheap.
        Path huge = dir.resolve("huge.txt");
        try (var raf = new java.io.RandomAccessFile(huge.toFile(), "rw")) {
            raf.setLength(TextFileIO.MAX_OPEN_BYTES + 1);
        }
        IOException ioe =
                assertThrows(IOException.class, () -> TextFileIO.load(huge));
        assertTrue(ioe.getMessage().contains("limit"));
    }

    @Test
    @DisplayName("save creates missing parent directories (Save As)")
    void saveCreatesParents(@TempDir Path dir) throws IOException {
        Path nested = dir.resolve("a/b/c/file.txt");
        TextFileIO.save(nested, "deep", StandardCharsets.UTF_8,
                TextFileIO.EOL_LF);
        assertEquals("deep", Files.readString(nested));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("an atomic save inherits the target's POSIX permissions")
    void savePreservesPermissions(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("secret.txt");
        Files.writeString(file, "old");
        Set<PosixFilePermission> perms =
                Set.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(file, perms);

        TextFileIO.save(file, "new", StandardCharsets.UTF_8,
                TextFileIO.EOL_LF);
        assertEquals("new", Files.readString(file));
        assertEquals(perms, Files.getPosixFilePermissions(file));
        // No leftover temp files in the directory.
        List<Path> left = Files.list(dir)
                .filter(p -> p.getFileName().toString().endsWith(".lg3d-tmp"))
                .toList();
        assertTrue(left.isEmpty(), "temp file must not survive a save");
    }

    @Test
    @DisplayName("save rejects a null path")
    void saveGuards(@TempDir Path dir) {
        assertThrows(IOException.class, () -> TextFileIO.save(null, "x",
                StandardCharsets.UTF_8, TextFileIO.EOL_LF));
    }

    @Test
    @DisplayName("platformEol reports LF or CRLF only")
    void platformEol() {
        String eol = TextFileIO.platformEol();
        assertTrue(TextFileIO.EOL_LF.equals(eol)
                || TextFileIO.EOL_CRLF.equals(eol));
    }

    @Test
    @DisplayName("extensionOf extracts the lower-case extension")
    void extensionOf() {
        assertEquals("txt", TextFileIO.extensionOf("notes.txt"));
        assertEquals("java", TextFileIO.extensionOf("Main.JAVA"));
        assertEquals("", TextFileIO.extensionOf("Makefile"));
        assertEquals("", TextFileIO.extensionOf("notes."));
        assertEquals("", TextFileIO.extensionOf(null));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
