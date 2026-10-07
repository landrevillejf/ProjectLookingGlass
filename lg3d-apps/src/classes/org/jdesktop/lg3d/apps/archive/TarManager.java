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

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal POSIX tar reader implemented without external dependencies:
 * lists entries and extracts regular files and directories, guarding against
 * path traversal. AWT-free so it is headless-testable.
 */
@Slf4j
public class TarManager {

    private static final int BLOCK_SIZE = 512;
    private static final int NAME_OFFSET = 0;
    private static final int NAME_LENGTH = 100;
    private static final int SIZE_OFFSET = 124;
    private static final int SIZE_LENGTH = 12;
    private static final int TYPE_OFFSET = 156;
    private static final int PREFIX_OFFSET = 345;
    private static final int PREFIX_LENGTH = 155;
    private static final int BUFFER_SIZE = 8192;

    /**
     * Lists the entries of a tar archive.
     *
     * @param archive the tar file
     * @return entry names
     * @throws IOException when reading fails
     */
    public List<String> list(File archive) throws IOException {
        List<String> entries = new ArrayList<>();
        try (InputStream in = new FileInputStream(archive)) {
            byte[] header = new byte[BLOCK_SIZE];
            while (readFully(in, header) == BLOCK_SIZE) {
                if (isEmptyBlock(header)) {
                    break;
                }
                entries.add(entryName(header));
                if (!skipContent(in, entrySize(header))) {
                    break;
                }
            }
        }
        log.info("Listed {} entries of {}", entries.size(), archive.getName());
        return entries;
    }

    /**
     * Extracts all entries below the target directory, guarding against
     * path traversal.
     *
     * @param archive   the tar file
     * @param targetDir extraction root
     * @return number of extracted files
     * @throws IOException when reading or writing fails
     */
    public int extract(File archive, File targetDir) throws IOException {
        String rootPath = targetDir.getCanonicalPath();
        int count = 0;
        try (InputStream in = new FileInputStream(archive)) {
            byte[] header = new byte[BLOCK_SIZE];
            while (readFully(in, header) == BLOCK_SIZE) {
                if (isEmptyBlock(header)) {
                    break;
                }
                String name = entryName(header);
                long size = entrySize(header);
                char type = (char) header[TYPE_OFFSET];
                File destination = new File(targetDir, name);
                boolean safe = destination.getCanonicalPath().startsWith(rootPath + File.separator);
                if (!safe) {
                    log.warn("Skipping unsafe tar entry: {}", name);
                } else if (type == '5') {
                    Files.createDirectories(destination.toPath());
                } else if (type == '0' || type == 0) {
                    Files.createDirectories(destination.getParentFile().toPath());
                    try (OutputStream out = new FileOutputStream(destination)) {
                        copyBytes(in, out, size);
                    }
                    count++;
                    size = 0;
                }
                if (size > 0 && !skipContent(in, size)) {
                    break;
                }
            }
        }
        log.info("Extracted {} files from {}", count, archive.getName());
        return count;
    }

    private static String entryName(byte[] header) {
        String name = trimmed(header, NAME_OFFSET, NAME_LENGTH);
        String prefix = trimmed(header, PREFIX_OFFSET, PREFIX_LENGTH);
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static long entrySize(byte[] header) {
        String octal = trimmed(header, SIZE_OFFSET, SIZE_LENGTH);
        return octal.isEmpty() ? 0L : Long.parseLong(octal, 8);
    }

    private static String trimmed(byte[] header, int offset, int length) {
        int end = offset;
        int limit = Math.min(offset + length, header.length);
        while (end < limit && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.US_ASCII).trim();
    }

    private static boolean isEmptyBlock(byte[] header) {
        for (byte value : header) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static boolean skipContent(InputStream in, long size) throws IOException {
        long padded = ((size + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE;
        long remaining = padded;
        while (remaining > 0) {
            long step = in.skip(remaining);
            if (step <= 0) {
                int toRead = (int) Math.min(BUFFER_SIZE, remaining);
                int read = in.read(new byte[toRead]);
                if (read < 0) {
                    return false;
                }
                step = read;
            }
            remaining -= step;
        }
        return true;
    }

    private static void copyBytes(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = size;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
        long padded = ((size + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE;
        long padding = padded - size;
        while (padding > 0) {
            long step = in.skip(padding);
            if (step <= 0) {
                break;
            }
            padding -= step;
        }
    }
}
