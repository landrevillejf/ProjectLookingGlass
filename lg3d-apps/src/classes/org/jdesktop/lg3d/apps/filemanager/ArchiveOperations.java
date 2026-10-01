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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Archive (compression) operations for the file manager, built entirely on the
 * JDK's {@code java.util.zip} so a produced {@code .zip} opens in any unzip
 * tool and any standard zip can be extracted here. No third-party dependency.
 *
 * <p><b>Reliability.</b> {@link #createZip} streams into a sibling {@code .part}
 * file and atomically moves it onto the target only once the whole archive is
 * written, so an interrupted run never leaves a truncated {@code .zip} behind.
 * Unreadable files are skipped rather than aborting the archive.</p>
 *
 * <p><b>Security.</b> {@link #extract} is hardened against the <em>Zip Slip</em>
 * vulnerability: every entry is resolved against the destination and rejected if
 * it escapes it (absolute names, {@code ../} traversal), so a hostile archive
 * cannot overwrite files outside the chosen folder.</p>
 *
 * <p>Deliberately free of Swing/AWT imports so it can be exercised headless.
 * Long-running loops report cumulative byte progress through an optional
 * {@link LongConsumer} (driven from a {@code SwingWorker}).</p>
 */
public final class ArchiveOperations {

    private static final int BUFFER = 8192;

    /** Lower-case extensions {@code java.util.zip} can open as a zip container. */
    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of(
            "zip", "jar", "war", "ear", "apk", "cbz", "zipx");

    private ArchiveOperations() {
        // no instances
    }

    /** True when {@code path} looks like a zip-family archive (by extension). */
    public static boolean isArchive(Path path) {
        if (path == null) {
            return false;
        }
        Path name = path.getFileName();
        if (name == null) {
            return false;
        }
        String n = name.toString().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        if (dot < 0 || dot == n.length() - 1) {
            return false;
        }
        return ARCHIVE_EXTENSIONS.contains(n.substring(dot + 1));
    }

    /**
     * Creates a ZIP archive at {@code destZip} holding every source (recursing
     * into directories). Entry names are relative to each source's parent, so a
     * selected folder is archived under its own name.
     *
     * @param sources  the files/folders to archive
     * @param destZip  the target {@code .zip} (parent created if needed)
     * @param progress receives cumulative bytes written (may be null)
     * @return true if the archive was written and moved into place
     */
    public static boolean createZip(List<Path> sources, Path destZip, LongConsumer progress) {
        if (sources == null || sources.isEmpty() || destZip == null) {
            return false;
        }
        Path archive = destZip.toAbsolutePath().normalize();
        Path temp = archive.resolveSibling(archive.getFileName() + ".part");
        try {
            if (archive.getParent() != null) {
                Files.createDirectories(archive.getParent());
            }
            long[] done = {0L};
            Set<String> used = new HashSet<>();
            byte[] buf = new byte[BUFFER];
            try (OutputStream fos = Files.newOutputStream(temp);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {
                for (Path src : sources) {
                    if (src == null || !Files.exists(src)) {
                        continue;
                    }
                    Path abs = src.toAbsolutePath().normalize();
                    // Never archive the output into itself.
                    if (sameFile(abs, archive) || sameFile(abs, temp)) {
                        continue;
                    }
                    Path base = (abs.getParent() != null) ? abs.getParent() : abs;
                    addTree(zos, abs, base, archive, temp, used, buf, done, progress);
                }
            }
            moveAtomic(temp, archive);
            return true;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp);
            return false;
        }
    }

    /**
     * Extracts {@code archive} into {@code destDir} (created if absent),
     * guarding every entry against Zip-Slip path traversal.
     *
     * @param archive  the zip file to read
     * @param destDir  the folder to extract into
     * @param progress receives cumulative bytes written (may be null)
     * @return true if extraction completed without a fatal error
     */
    public static boolean extract(Path archive, Path destDir, LongConsumer progress) {
        if (archive == null || destDir == null || !Files.isRegularFile(archive)) {
            return false;
        }
        try (ZipFile zf = new ZipFile(archive.toFile())) {
            Path dest = destDir.toAbsolutePath().normalize();
            Files.createDirectories(dest);
            long[] done = {0L};
            byte[] buf = new byte[BUFFER];
            Enumeration<? extends ZipEntry> it = zf.entries();
            while (it.hasMoreElements()) {
                ZipEntry entry = it.nextElement();
                Path resolved = dest.resolve(entry.getName()).normalize();
                // Zip Slip guard: refuse any entry that escapes the destination.
                if (!resolved.startsWith(dest)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                    continue;
                }
                if (resolved.getParent() != null) {
                    Files.createDirectories(resolved.getParent());
                }
                try (InputStream in = zf.getInputStream(entry);
                     OutputStream out = Files.newOutputStream(resolved)) {
                    done[0] += copy(in, out, buf);
                    if (progress != null) {
                        progress.accept(done[0]);
                    }
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Lists an archive's entry names without extracting them.
     *
     * @param archive the zip file to read
     * @return the entry names, in archive order
     * @throws IOException if the archive cannot be read
     */
    public static List<String> list(Path archive) throws IOException {
        List<String> out = new ArrayList<>();
        if (archive == null || !Files.isRegularFile(archive)) {
            return out;
        }
        try (ZipFile zf = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                out.add(e.nextElement().getName());
            }
        }
        return out;
    }

    /** The file name {@code base} would archive to (its own name + ".zip"). */
    public static String defaultArchiveName(Path base) {
        String n = (base != null && base.getFileName() != null)
                ? base.getFileName().toString() : "archive";
        return n + ".zip";
    }

    /** Strips a trailing zip-family extension, for "extract to &lt;base&gt;/". */
    public static String baseName(Path archive) {
        String n = (archive != null && archive.getFileName() != null)
                ? archive.getFileName().toString() : "archive";
        int dot = n.lastIndexOf('.');
        return (dot > 0) ? n.substring(0, dot) : n;
    }

    // ------------------------------------------------------------------

    private static void addTree(ZipOutputStream zos, Path root, Path base, Path archive,
                                Path temp, Set<String> used, byte[] buf, long[] done,
                                LongConsumer progress) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Path abs = dir.toAbsolutePath().normalize();
                if (sameFile(abs, archive) || sameFile(abs, temp)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                String rel = slash(base.relativize(abs).toString());
                if (!rel.isEmpty()) {
                    // Directory entries must end in '/' so unzip tools and our
                    // own extract treat them as folders, not 0-byte files.
                    String name = uniqueName(rel + "/", used);
                    zos.putNextEntry(new ZipEntry(name));
                    zos.closeEntry();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Path abs = file.toAbsolutePath().normalize();
                if (sameFile(abs, archive) || sameFile(abs, temp)) {
                    return FileVisitResult.CONTINUE;
                }
                String rel = slash(base.relativize(abs).toString());
                if (rel.isEmpty()) {
                    return FileVisitResult.CONTINUE;
                }
                String name = uniqueName(rel, used);
                ZipEntry ze = new ZipEntry(name);
                ze.setTime(safeTime(abs));
                zos.putNextEntry(ze);
                try (InputStream in = Files.newInputStream(abs)) {
                    done[0] += copy(in, zos, buf);
                    if (progress != null) {
                        progress.accept(done[0]);
                    }
                } catch (IOException ioe) {
                    // Skip an unreadable file rather than aborting the archive.
                }
                zos.closeEntry();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static long copy(InputStream in, OutputStream out, byte[] buf) throws IOException {
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    private static String slash(String p) {
        return p.replace('\\', '/');
    }

    private static String uniqueName(String name, Set<String> used) {
        if (used.add(name)) {
            return name;
        }
        boolean dir = name.endsWith("/");
        String core = dir ? name.substring(0, name.length() - 1) : name;
        int dot = core.lastIndexOf('.');
        int slash = core.lastIndexOf('/');
        String stem = (dot > slash + 1) ? core.substring(0, dot) : core;
        String ext = (dot > slash + 1) ? core.substring(dot) : "";
        int i = 1;
        String candidate;
        do {
            candidate = stem + " (" + i++ + ")" + ext + (dir ? "/" : "");
        } while (!used.add(candidate));
        return candidate;
    }

    private static long safeTime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException | RuntimeException e) {
            return System.currentTimeMillis();
        }
    }

    private static boolean sameFile(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.equals(b);
        }
    }

    private static void moveAtomic(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException | RuntimeException ignored) {
            // best effort cleanup
        }
    }
}
