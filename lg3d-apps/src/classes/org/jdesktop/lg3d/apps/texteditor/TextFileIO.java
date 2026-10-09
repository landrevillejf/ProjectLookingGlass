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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * The Advanced Text Editor's safe file layer. Loading and saving are the two
 * places where an editor can hurt a user, so both ends are hardened:
 *
 * <ul>
 *   <li><b>Load guards</b> &mdash; a size ceiling ({@link #MAX_OPEN_BYTES})
 *       keeps multi-gigabyte files from exhausting the heap, and a NUL sniff
 *       over the first bytes rejects binaries with a clear message instead of
 *       showing mojibake.</li>
 *   <li><b>Charset handling</b> &mdash; a UTF-8 / UTF-16 byte-order mark wins
 *       when present; otherwise the bytes are decoded strictly as UTF-8 and,
 *       only if that fails, leniently as ISO-8859-1 with a user-visible
 *       warning. Nothing is ever decoded silently-lossily as UTF-8.</li>
 *   <li><b>Line endings</b> &mdash; detected per file (CRLF vs LF) and
 *       normalised to {@code '\n'} in memory; the file's own convention is
 *       restored on save so diffs stay clean.</li>
 *   <li><b>Atomic save</b> &mdash; bytes go to a sibling temp file which is
 *       then moved over the target with {@code ATOMIC_MOVE} (falling back to
 *       a plain replace on file systems that cannot do atomic moves), with
 *       the target's POSIX permissions copied onto the temp file first. A
 *       crash mid-save can therefore never leave a half-written original,
 *       and a saved file never becomes world-readable by accident.</li>
 * </ul>
 */
public final class TextFileIO {

    /** Files larger than this are refused (the editor would crawl anyway). */
    public static final long MAX_OPEN_BYTES = 10L * 1024 * 1024;

    /** Bytes sniffed for NUL when deciding text vs binary. */
    private static final int SNIFF_BYTES = 8192;

    /** The three line-ending conventions the editor round-trips. */
    public static final String EOL_LF = "\n";
    public static final String EOL_CRLF = "\r\n";

    /**
     * A loaded document: the normalised text (always {@code '\n'} endings),
     * the detected charset, the file's line-ending convention to restore on
     * save, and a non-null warning when the encoding was guessed.
     */
    public record LoadResult(String text, Charset charset, String eol,
            String warning) {
    }

    private TextFileIO() {
        // Static utility.
    }

    /**
     * Reads a text file under the guards described on the class javadoc.
     *
     * @throws IOException if the file is missing, unreadable, a directory,
     *                     binary, or larger than {@link #MAX_OPEN_BYTES}
     */
    public static LoadResult load(Path path) throws IOException {
        if (path == null) {
            throw new IOException("No file given");
        }
        if (Files.isDirectory(path)) {
            throw new IOException(path.getFileName() + " is a directory");
        }
        long size = Files.size(path);
        if (size > MAX_OPEN_BYTES) {
            throw new IOException(path.getFileName() + " is "
                    + (size / 1024 / 1024) + " MB, over the "
                    + (MAX_OPEN_BYTES / 1024 / 1024) + " MB editor limit");
        }
        byte[] bytes = Files.readAllBytes(path);

        // A BOM wins over the binary sniff: UTF-16 text is full of NUL
        // bytes yet is perfectly editable.
        Charset charset = StandardCharsets.UTF_8;
        String warning = null;
        int from = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            from = 3; // UTF-8 BOM
        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            from = 2;
        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE
                && (bytes[1] & 0xFF) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            from = 2;
        } else if (looksBinary(bytes)) {
            throw new IOException(path.getFileName()
                    + " is a binary file, not text");
        }

        String text = decodeStrict(bytes, from, charset);
        if (text == null) {
            // Not valid in the detected (or default UTF-8) charset: fall back
            // to the byte-preserving 8-bit encoding and tell the user.
            charset = StandardCharsets.ISO_8859_1;
            text = decodeStrict(bytes, from, charset);
            warning = "Not valid UTF-8; loaded as ISO-8859-1";
        }
        String eol = detectEol(text);
        return new LoadResult(normalizeEol(text), charset, eol, warning);
    }

    /**
     * Saves atomically: sibling temp file, permissions inherited from the
     * existing target, then an atomic move into place. Parent directories are
     * created as needed (Save As into a fresh folder).
     */
    public static void save(Path path, String text, Charset charset,
            String eol) throws IOException {
        if (path == null) {
            throw new IOException("No file given");
        }
        String endings = (EOL_CRLF.equals(eol)) ? EOL_CRLF : EOL_LF;
        byte[] bytes = denormalizeEol(text, endings).getBytes(charset);
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(
                (parent != null) ? parent : Path.of("."),
                "." + fileName(path) + ".", ".lg3d-tmp");
        try {
            Files.write(temp, bytes);
            copyPermissions(path, temp);
            move(temp, path);
        } catch (IOException ioe) {
            Files.deleteIfExists(temp);
            throw ioe;
        }
    }

    /** The platform's own line separator, for new files. */
    public static String platformEol() {
        String sep = System.lineSeparator();
        return EOL_CRLF.equals(sep) ? EOL_CRLF : EOL_LF;
    }

    /** True when the sniffed prefix contains a NUL byte (binary marker). */
    static boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /** Strict decode, or null when the bytes are not valid in the charset. */
    private static String decodeStrict(byte[] bytes, int from,
            Charset charset) {
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer out = decoder.decode(
                    ByteBuffer.wrap(bytes, from, bytes.length - from));
            return out.toString();
        } catch (CharacterCodingException cce) {
            return null;
        } catch (IllegalArgumentException iae) {
            return null; // from > length on a 2-byte BOM-only file
        }
    }

    /** The dominant line-ending convention in raw (not yet normalised) text. */
    static String detectEol(String text) {
        int crlf = 0;
        int lf = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                if (i > 0 && text.charAt(i - 1) == '\r') {
                    crlf++;
                } else {
                    lf++;
                }
            }
        }
        return (crlf > lf) ? EOL_CRLF : EOL_LF;
    }

    /** Collapses CRLF and lone CR to plain {@code '\n'}. */
    static String normalizeEol(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Expands every {@code '\n'} to the file's convention. */
    static String denormalizeEol(String text, String eol) {
        if (!EOL_CRLF.equals(eol)) {
            return text;
        }
        return text.replace("\n", EOL_CRLF);
    }

    private static void copyPermissions(Path target, Path temp) {
        try {
            if (Files.exists(target) && Files.isRegularFile(target)) {
                Files.setPosixFilePermissions(temp,
                        Files.getPosixFilePermissions(target));
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX file system, or the temp keeps its defaults.
        }
    }

    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException amne) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return (name != null) ? name.toString() : "file";
    }

    /** The extension of a file name in lower case without the dot, or "". */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
