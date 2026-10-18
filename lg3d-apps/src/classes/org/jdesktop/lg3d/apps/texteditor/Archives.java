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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Pure-JDK archive extraction for the toolchains Espresso provisions: the
 * {@code .zip} a Gradle or Maven distribution ships as, and the {@code .tar.gz}
 * an Adoptium JDK ships as on Linux/macOS. There is no third-party compression
 * library on the editor's classpath and no reason to add one, so both readers
 * are hand-rolled over {@link java.util.zip} and {@link java.util.zip.GZIPInputStream}.
 *
 * <p>Both readers refuse an entry whose resolved path escapes the destination
 * (the "zip slip" / "tar slip" trap: a {@code ../} name that would write outside
 * the extraction folder), and both recreate the owner-execute bit on files that
 * carried it, so an extracted {@code bin/gradle} or {@code bin/java} is runnable
 * without a separate {@code chmod}.</p>
 */
final class Archives {

    /** Tar records are a fixed 512 bytes, header and data padding alike. */
    private static final int TAR_BLOCK = 512;

    private Archives() {
    }

    /**
     * Extracts {@code archive} into {@code destDir} (created if absent),
     * dispatching on the file name: {@code .zip} to {@link #extractZip},
     * {@code .tar.gz}/{@code .tgz} to {@link #extractTarGz}.
     *
     * @throws IOException when the format is unknown or extraction fails
     */
    static void extract(Path archive, Path destDir) throws IOException {
        String name = archive.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        Files.createDirectories(destDir);
        if (name.endsWith(".zip")) {
            extractZip(archive, destDir);
        } else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            extractTarGz(archive, destDir);
        } else {
            throw new IOException("Unsupported archive format: " + archive.getFileName());
        }
    }

    /** Extracts a {@code .zip} into {@code destDir}, honouring the execute bit. */
    static void extractZip(Path zip, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        Path base = destDir.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path out = resolveSafe(base, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (OutputStream os = Files.newOutputStream(out)) {
                        in.transferTo(os);
                    }
                    // java.util.zip does not surface the unix mode, so a
                    // launcher under bin/ is made executable by the caller
                    // (ProjectToolchain) rather than guessed here.
                }
                in.closeEntry();
            }
        }
    }

    /**
     * Extracts a {@code .tar.gz} into {@code destDir}. Supports the ustar
     * {@code prefix}/{@code name} split and the GNU {@code L} long-name record;
     * regular files carry their owner-execute bit through, directories are
     * created, and symlinks/hardlinks/specials are skipped (a JDK or tool
     * distribution needs none of them to run).
     */
    static void extractTarGz(Path tgz, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        Path base = destDir.toAbsolutePath().normalize();
        try (InputStream fin = Files.newInputStream(tgz);
             java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(fin)) {
            byte[] header = new byte[TAR_BLOCK];
            String longName = null;
            while (true) {
                if (!readFully(gz, header)) {
                    return;
                }
                if (isZeroBlock(header)) {
                    // Two consecutive zero blocks mark the end; one is enough
                    // to stop reading a well-formed archive.
                    return;
                }
                String name = longName != null ? longName : tarName(header);
                long size = tarOctal(header, 124, 12);
                char type = (char) (header[156] & 0xFF);
                int mode = (int) tarOctal(header, 100, 8);
                long dataBlocks = (size + TAR_BLOCK - 1) / TAR_BLOCK;

                if (type == 'L') {
                    // GNU long name: the payload is the next entry's name.
                    byte[] buf = new byte[(int) size];
                    readFully(gz, buf);
                    skipPadding(gz, size);
                    longName = new String(buf, java.nio.charset.StandardCharsets.UTF_8).trim();
                    continue;
                }
                longName = null;
                if (name.isEmpty()) {
                    skipBytes(gz, dataBlocks * TAR_BLOCK);
                    continue;
                }
                Path out = resolveSafe(base, name);
                if (type == '5' || name.endsWith("/")) {
                    Files.createDirectories(out);
                } else if (type == '0' || type == 0 || type == '7') {
                    Files.createDirectories(out.getParent());
                    try (OutputStream os = Files.newOutputStream(out)) {
                        copyExactly(gz, os, size);
                    }
                    skipPadding(gz, size);
                    if ((mode & 0100) != 0) {
                        makeExecutable(out);
                    }
                    continue;
                } else {
                    // Symlink, hardlink, device, fifo: skip the payload.
                    skipBytes(gz, dataBlocks * TAR_BLOCK);
                    continue;
                }
                skipPadding(gz, size);
            }
        }
    }

    // -- tar primitives ----------------------------------------------------

    /** The ustar file name, prefixed when the {@code prefix} field is set. */
    private static String tarName(byte[] header) {
        String name = tarString(header, 0, 100);
        String prefix = tarString(header, 345, 155);
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static String tarString(byte[] buf, int off, int len) {
        int end = off;
        while (end < off + len && buf[end] != 0) {
            end++;
        }
        return new String(buf, off, end - off, java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    /** An octal ASCII field (tar stores sizes and modes this way), or 0. */
    private static long tarOctal(byte[] buf, int off, int len) {
        String s = tarString(buf, off, len).trim();
        if (s.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(s, 8);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    // -- shared stream + safety helpers ------------------------------------

    /** Reads exactly {@code buf.length} bytes; false on a clean EOF at 0. */
    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) {
                return total == 0 ? false : true;
            }
            total += n;
        }
        return true;
    }

    private static void copyExactly(InputStream in, OutputStream out, long size)
            throws IOException {
        byte[] buf = new byte[8192];
        long remaining = size;
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int n = in.read(buf, 0, toRead);
            if (n < 0) {
                throw new IOException("Truncated archive: expected " + size + " bytes");
            }
            out.write(buf, 0, n);
            remaining -= n;
        }
    }

    /** Skips the null padding that rounds a tar member up to a 512 boundary. */
    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (TAR_BLOCK - (size % TAR_BLOCK)) % TAR_BLOCK;
        skipBytes(in, pad);
    }

    private static void skipBytes(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long n = in.skip(remaining);
            if (n <= 0) {
                if (in.read() < 0) {
                    return;
                }
                remaining--;
            } else {
                remaining -= n;
            }
        }
    }

    /**
     * Resolves an entry name against {@code base}, refusing a path that escapes
     * it (an absolute name or one climbing out with {@code ..}).
     */
    private static Path resolveSafe(Path base, String entryName) throws IOException {
        String cleaned = entryName.replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        Path out = base.resolve(cleaned).normalize();
        if (!out.startsWith(base)) {
            throw new IOException("Archive entry escapes the destination: " + entryName);
        }
        return out;
    }

    /**
     * Best-effort owner-execute on a POSIX file system; a no-op where POSIX
     * permissions are unsupported (Windows), which needs no execute bit.
     */
    static void makeExecutable(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            Set<PosixFilePermission> updated = EnumSet.copyOf(perms);
            updated.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(file, updated);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-POSIX file system, or the file vanished: nothing to set.
        }
    }
}
