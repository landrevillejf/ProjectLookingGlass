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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * ZIP archive operations implemented with {@code java.util.zip}: listing,
 * extraction and creation. Extraction and creation are hardened against
 * Zip-Slip path traversal and never touch the EDT (callers run them on a
 * worker thread). AWT-free so it is headless-testable.
 */
@Slf4j
public class ZipManager {

    private static final int BUFFER_SIZE = 8192;

    /**
     * Lists the entries of a ZIP archive.
     *
     * @param archive the ZIP file
     * @return entry names
     * @throws IOException when reading fails
     */
    public List<String> list(File archive) throws IOException {
        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        log.info("Listed {} entries of {}", entries.size(), archive.getName());
        return entries;
    }

    /**
     * Extracts all entries below the target directory, guarding against zip
     * slip path traversal.
     *
     * @param archive   the ZIP file
     * @param targetDir extraction root
     * @return number of extracted entries
     * @throws IOException when reading or writing fails
     */
    public int extract(File archive, File targetDir) throws IOException {
        String rootPath = targetDir.getCanonicalPath();
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File destination = new File(targetDir, entry.getName());
                if (!destination.getCanonicalPath().startsWith(rootPath + File.separator)) {
                    log.warn("Skipping unsafe archive entry: {}", entry.getName());
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination.toPath());
                } else {
                    Files.createDirectories(destination.getParentFile().toPath());
                    try (OutputStream out = new FileOutputStream(destination)) {
                        copy(zip, out);
                    }
                    count++;
                }
            }
        }
        log.info("Extracted {} files from {}", count, archive.getName());
        return count;
    }

    /**
     * Creates a ZIP archive from every file below the source directory.
     *
     * @param sourceDir directory whose content is zipped
     * @param targetZip archive to create
     * @return number of zipped files
     * @throws IOException when reading or writing fails
     */
    public int create(File sourceDir, File targetZip) throws IOException {
        int count;
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(targetZip))) {
            count = addDirectory(zip, sourceDir, "");
        }
        log.info("Created {} with {} entries", targetZip.getName(), count);
        return count;
    }

    private int addDirectory(ZipOutputStream zip, File dir, String prefix) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int count = 0;
        for (File child : children) {
            String name = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            if (child.isDirectory()) {
                count += addDirectory(zip, child, name);
            } else {
                zip.putNextEntry(new ZipEntry(name));
                try (InputStream in = new FileInputStream(child)) {
                    copy(in, zip);
                }
                zip.closeEntry();
                count++;
            }
        }
        return count;
    }

    private void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }
}
