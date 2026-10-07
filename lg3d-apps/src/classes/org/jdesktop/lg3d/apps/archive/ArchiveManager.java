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

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.compress.utils.IOUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Multi-format archive engine for the Archive app, built on Apache Commons
 * Compress. It lists, extracts and creates archives of every common open format:
 *
 * <ul>
 *   <li><b>Read</b>: ZIP, 7-Zip, TAR, and TAR wrapped in any compressor Commons
 *       Compress auto-detects (gzip {@code .tar.gz}/{@code .tgz}, bzip2
 *       {@code .tar.bz2}, XZ {@code .tar.xz}, Z, LZMA, ...), plus CPIO, AR and
 *       single-file compressed streams ({@code .gz}/{@code .bz2}/{@code .xz}).</li>
 *   <li><b>Create</b>: ZIP, TAR, TAR.GZ, TAR.BZ2 and TAR.XZ (the target format is
 *       chosen from the output file name).</li>
 * </ul>
 *
 * <p>Extraction is hardened against Zip-Slip / path traversal. The class is
 * AWT-free and never touches the EDT (callers run it on a worker thread), so it
 * is headless-testable. RAR is proprietary and Zstandard / Brotli need extra
 * optional native codecs, so those are deliberately out of scope.</p>
 */
@Slf4j
public class ArchiveManager {

    /** Read-ahead used for magic-byte sniffing (must cover the tar ustar mark at 257). */
    private static final int SNIFF_SIZE = 512;

    /** 7-Zip container signature. */
    private static final byte[] SEVEN_Z_MAGIC = {
            0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C
    };

    /** Compressor suffixes stripped to name a single decompressed file. */
    private static final String[] COMPRESSOR_SUFFIXES = {
            ".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".tbz", ".txz",
            ".gz", ".bz2", ".xz", ".lzma", ".z",
    };

    /**
     * Consumes one archive entry. {@code content} is only valid for the duration
     * of the call and must not be closed by the handler.
     */
    @FunctionalInterface
    private interface EntryHandler {
        void handle(String name, boolean directory, InputStream content) throws IOException;
    }

    /**
     * Returns a short human-readable format label for the given archive, used
     * only for status text and logging.
     *
     * @param archive the archive file
     * @return a format label such as "ZIP", "7-Zip" or "TAR.GZ"
     */
    public String describe(File archive) {
        String name = archive.getName().toLowerCase(Locale.ROOT);
        try {
            byte[] head = head(archive, 6);
            if (isZip(head)) {
                return "ZIP";
            }
            if (startsWith(head, SEVEN_Z_MAGIC)) {
                return "7-Zip";
            }
        } catch (IOException e) {
            log.debug("Could not sniff {}: {}", archive.getName(), e.getMessage());
        }
        for (String suffix : COMPRESSOR_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return suffix.substring(1).toUpperCase(Locale.ROOT);
            }
        }
        if (name.endsWith(".tar")) {
            return "TAR";
        }
        return "archive";
    }

    /**
     * Lists the entry names of any supported archive.
     *
     * @param archive the archive file
     * @return entry names
     * @throws IOException when reading fails or the format is unsupported
     */
    public List<String> list(File archive) throws IOException {
        List<String> entries = new ArrayList<>();
        forEachEntry(archive, (name, directory, content) -> entries.add(name));
        log.info("Listed {} entries of {} ({})", entries.size(), archive.getName(), describe(archive));
        return entries;
    }

    /**
     * Extracts every entry below the target directory, guarding against Zip-Slip
     * path traversal.
     *
     * @param archive   the archive file
     * @param targetDir extraction root
     * @return number of regular files written
     * @throws IOException when reading or writing fails
     */
    public int extract(File archive, File targetDir) throws IOException {
        String rootPath = targetDir.getCanonicalPath();
        int[] count = {0};
        Files.createDirectories(targetDir.toPath());
        forEachEntry(archive, (name, directory, content) -> {
            File destination = new File(targetDir, name);
            if (!destination.getCanonicalPath().startsWith(rootPath + File.separator)) {
                log.warn("Skipping unsafe archive entry: {}", name);
                return;
            }
            if (directory) {
                Files.createDirectories(destination.toPath());
            } else {
                File parent = destination.getParentFile();
                if (parent != null) {
                    Files.createDirectories(parent.toPath());
                }
                try (OutputStream out = new FileOutputStream(destination)) {
                    IOUtils.copy(content, out);
                }
                count[0]++;
            }
        });
        log.info("Extracted {} files from {} ({})", count[0], archive.getName(), describe(archive));
        return count[0];
    }

    /**
     * Creates an archive of every file below the source directory. The format is
     * chosen from the target file name: {@code .zip} (default), {@code .tar},
     * {@code .tar.gz}/{@code .tgz}, {@code .tar.bz2} or {@code .tar.xz}/{@code .txz}.
     *
     * @param sourceDir directory whose content is archived
     * @param target    archive to create
     * @return number of files archived
     * @throws IOException when reading or writing fails
     */
    public int create(File sourceDir, File target) throws IOException {
        String name = target.getName().toLowerCase(Locale.ROOT);
        if (isTar(name)) {
            return createTar(sourceDir, target, name);
        }
        return createZip(sourceDir, target);
    }

    // ------------------------------------------------------------------
    // Read path
    // ------------------------------------------------------------------

    private void forEachEntry(File archive, EntryHandler handler) throws IOException {
        byte[] head = head(archive, 6);
        if (isZip(head)) {
            readZip(archive, handler);
        } else if (startsWith(head, SEVEN_Z_MAGIC)) {
            readSevenZ(archive, handler);
        } else {
            readStream(archive, handler);
        }
    }

    private void readZip(File archive, EntryHandler handler) throws IOException {
        try (ZipFile zip = ZipFile.builder().setFile(archive).get()) {
            for (ZipArchiveEntry entry : Collections.list(zip.getEntries())) {
                try (InputStream in = zip.getInputStream(entry)) {
                    handler.handle(entry.getName(), entry.isDirectory(), in);
                }
            }
        }
    }

    private void readSevenZ(File archive, EntryHandler handler) throws IOException {
        try (SevenZFile sz = SevenZFile.builder().setFile(archive).get()) {
            SevenZArchiveEntry entry;
            while ((entry = sz.getNextEntry()) != null) {
                InputStream in = entry.hasStream()
                        ? sz.getInputStream(entry)
                        : InputStream.nullInputStream();
                handler.handle(entry.getName(), entry.isDirectory(), in);
            }
        }
    }

    /**
     * Reads a (possibly compressed) streaming archive: tar / cpio / ar, with or
     * without a compressor wrapper, or a single-file compressed stream.
     */
    private void readStream(File archive, EntryHandler handler) throws IOException {
        try (InputStream fin = new BufferedInputStream(new FileInputStream(archive), 1 << 16)) {
            InputStream decompressed = decompressOrRaw(fin);
            BufferedInputStream bin = new BufferedInputStream(decompressed, 1 << 16);
            bin.mark(1 << 20);
            boolean isArchive = looksLikeArchive(bin);
            bin.reset();
            if (isArchive) {
                readArchiveStream(bin, archive, handler);
            } else {
                // A lone compressed file (e.g. notes.gz): decompress to one file.
                handler.handle(singleFileName(archive.getName()), false, bin);
            }
        }
    }

    private void readArchiveStream(InputStream in, File archive, EntryHandler handler)
            throws IOException {
        try (ArchiveInputStream<?> ais = new ArchiveStreamFactory().createArchiveInputStream(in)) {
            ArchiveEntry entry;
            while ((entry = ais.getNextEntry()) != null) {
                handler.handle(entry.getName(), entry.isDirectory(), ais);
            }
        } catch (ArchiveException e) {
            throw new IOException("Unsupported archive format: " + archive.getName(), e);
        }
    }

    private static InputStream decompressOrRaw(InputStream in) throws IOException {
        try {
            return new CompressorStreamFactory().createCompressorInputStream(in);
        } catch (CompressorException e) {
            // Not a recognised compressed stream: read it raw.
            return in;
        }
    }

    /** Sniffs tar / cpio / ar / zip signatures without consuming the stream. */
    private static boolean looksLikeArchive(BufferedInputStream in) throws IOException {
        byte[] header = new byte[SNIFF_SIZE];
        int read = readFully(in, header);
        if (read >= 262 && header[257] == 'u' && header[258] == 's'
                && header[259] == 't' && header[260] == 'a' && header[261] == 'r') {
            return true; // POSIX tar
        }
        if (read >= 8 && new String(header, 0, 8, StandardCharsets.US_ASCII).equals("!<arch>\n")) {
            return true; // ar
        }
        if (read >= 6) {
            String magic = new String(header, 0, 6, StandardCharsets.US_ASCII);
            if (magic.equals("070701") || magic.equals("070702")) {
                return true; // newc cpio
            }
        }
        return read >= 4 && isZip(header);
    }

    // ------------------------------------------------------------------
    // Write path
    // ------------------------------------------------------------------

    private int createZip(File sourceDir, File target) throws IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target));
             ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out)) {
            return addZipDirectory(zip, sourceDir, "");
        }
    }

    private int addZipDirectory(ZipArchiveOutputStream zip, File dir, String prefix)
            throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int count = 0;
        for (File child : children) {
            String name = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            if (child.isDirectory()) {
                count += addZipDirectory(zip, child, name);
            } else {
                zip.putArchiveEntry(new ZipArchiveEntry(name));
                try (InputStream in = new FileInputStream(child)) {
                    IOUtils.copy(in, zip);
                }
                zip.closeArchiveEntry();
                count++;
            }
        }
        return count;
    }

    private int createTar(File sourceDir, File target, String lowerName) throws IOException {
        OutputStream fileOut = new BufferedOutputStream(new FileOutputStream(target));
        OutputStream compressed = wrapCompressor(fileOut, lowerName);
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(compressed)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            return addTarDirectory(tar, sourceDir, "");
        }
    }

    private static OutputStream wrapCompressor(OutputStream out, String lowerName)
            throws IOException {
        if (lowerName.endsWith(".gz") || lowerName.endsWith(".tgz")) {
            return new GzipCompressorOutputStream(out);
        }
        if (lowerName.endsWith(".bz2") || lowerName.endsWith(".tbz") || lowerName.endsWith(".tbz2")) {
            return new BZip2CompressorOutputStream(out);
        }
        if (lowerName.endsWith(".xz") || lowerName.endsWith(".txz") || lowerName.endsWith(".lzma")) {
            return new XZCompressorOutputStream(out);
        }
        return out; // plain .tar
    }

    private int addTarDirectory(TarArchiveOutputStream tar, File dir, String prefix)
            throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int count = 0;
        for (File child : children) {
            String name = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            if (child.isDirectory()) {
                tar.putArchiveEntry(new TarArchiveEntry(child, name + "/"));
                tar.closeArchiveEntry();
                count += addTarDirectory(tar, child, name);
            } else {
                tar.putArchiveEntry(new TarArchiveEntry(child, name));
                try (InputStream in = new FileInputStream(child)) {
                    IOUtils.copy(in, tar);
                }
                tar.closeArchiveEntry();
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean isTar(String lowerName) {
        return lowerName.endsWith(".tar") || lowerName.endsWith(".tar.gz")
                || lowerName.endsWith(".tgz") || lowerName.endsWith(".tar.bz2")
                || lowerName.endsWith(".tbz") || lowerName.endsWith(".tbz2")
                || lowerName.endsWith(".tar.xz") || lowerName.endsWith(".txz")
                || lowerName.endsWith(".tar.lzma");
    }

    private static String singleFileName(String archiveName) {
        String lower = archiveName.toLowerCase(Locale.ROOT);
        for (String suffix : COMPRESSOR_SUFFIXES) {
            if (lower.endsWith(suffix) && archiveName.length() > suffix.length()) {
                return archiveName.substring(0, archiveName.length() - suffix.length());
            }
        }
        return archiveName + ".out";
    }

    private static byte[] head(File file, int n) throws IOException {
        byte[] buffer = new byte[n];
        try (InputStream in = new FileInputStream(file)) {
            readFully(in, buffer);
        }
        return buffer;
    }

    private static boolean isZip(byte[] head) {
        return head.length >= 4 && head[0] == 'P' && head[1] == 'K'
                && ((head[2] == 3 && head[3] == 4)
                || (head[2] == 5 && head[3] == 6)
                || (head[2] == 7 && head[3] == 8));
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
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
}
