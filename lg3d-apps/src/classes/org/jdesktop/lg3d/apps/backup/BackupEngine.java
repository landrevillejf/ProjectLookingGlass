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
package org.jdesktop.lg3d.apps.backup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The AWT-free backup/restore engine. It writes and reads standard ZIP archives
 * with the JDK's built-in {@code java.util.zip} (no third-party dependency), so
 * an archive produced here opens in any unzip tool and vice-versa.
 *
 * <p><b>Reliability.</b> A backup streams into a sibling {@code .part} file and
 * is atomically moved onto the target only after the whole archive is written,
 * so an interrupted run never leaves a truncated {@code .zip} behind. Unreadable
 * files are skipped with a warning rather than aborting the run.</p>
 *
 * <p><b>Security.</b> Restore is hardened against the <em>Zip Slip</em>
 * vulnerability: every entry is resolved against the destination and rejected if
 * it escapes it (absolute names, {@code ../} traversal or a symlinked escape),
 * so a hostile archive cannot overwrite files outside the chosen folder.</p>
 *
 * <p>Entry names are relative to each source's parent, preserving the folder
 * structure. Long-running loops honour an optional {@link BooleanSupplier}
 * cancel signal so the UI can abort a job.</p>
 */
public final class BackupEngine {

    private static final int BUFFER = 8192;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Runs a backup of {@code profile}'s sources into a ZIP archive.
     *
     * @param profile  the configuration to run
     * @param listener progress sink (may be null for {@link BackupProgressListener#NULL})
     * @param cancel   optional cancellation signal polled per entry (may be null)
     * @return the outcome; {@link BackupResult#isSuccess()} is false on a fatal
     *         error or cancellation, with a message and any warnings
     */
    public BackupResult backup(BackupProfile profile, BackupProgressListener listener,
                               BooleanSupplier cancel) {
        long start = System.currentTimeMillis();
        BackupProgressListener pl = (listener == null) ? BackupProgressListener.NULL : listener;
        BackupResult.Builder result = BackupResult.builder();
        if (profile == null) {
            return result.success(false).message("No profile supplied").build();
        }
        Path archive = resolveArchiveName(profile);
        Path temp = archive.resolveSibling(archive.getFileName() + ".part");
        List<String> warnings = new ArrayList<>();
        try {
            if (archive.getParent() != null) {
                Files.createDirectories(archive.getParent());
            }
            List<PathMatcher> matchers = compileMatchers(profile.getExcludePatterns(), warnings);
            List<Path> entries = new ArrayList<>();
            long totalBytes = collectSources(profile, archive, temp, matchers, entries, warnings);
            long totalEntries = entries.size();

            boolean cancelled = false;
            long bytesDone = 0;
            int done = 0;
            try (OutputStream fos = Files.newOutputStream(temp);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {
                zos.setLevel(profile.getCompressionLevel());
                Set<String> used = new HashSet<>();
                byte[] buf = new byte[BUFFER];
                for (Path p : entries) {
                    if (isCancelled(cancel)) {
                        cancelled = true;
                        break;
                    }
                    String name;
                    if (Files.isDirectory(p)) {
                        // Directory entries must end in '/' so unzip tools and
                        // our own restore treat them as folders, not 0-byte
                        // files. A duplicate folder is structural, so skip it.
                        name = entryName(profile, p) + "/";
                        if (!used.add(name)) {
                            continue;
                        }
                        zos.putNextEntry(new ZipEntry(name));
                        zos.closeEntry();
                    } else {
                        name = uniqueName(entryName(profile, p), used);
                        ZipEntry ze = new ZipEntry(name);
                        ze.setTime(safeTime(p));
                        zos.putNextEntry(ze);
                        try (InputStream in = Files.newInputStream(p)) {
                            bytesDone += copy(in, zos, buf);
                        } catch (IOException ioe) {
                            warnings.add("Skipped unreadable file: " + p + " (" + ioe.getMessage() + ")");
                        }
                        zos.closeEntry();
                    }
                    done++;
                    pl.onProgress(name, done, totalEntries, bytesDone, totalBytes);
                }
            }
            if (cancelled) {
                deleteQuietly(temp);
                return result.success(false).cancelled(true).archive(archive)
                        .entryCount(done).totalBytes(bytesDone)
                        .durationMillis(System.currentTimeMillis() - start)
                        .addWarnings(warnings).message("Backup cancelled").build();
            }
            moveAtomic(temp, archive);
            return result.success(true).archive(archive).entryCount(done)
                    .totalBytes(bytesDone).durationMillis(System.currentTimeMillis() - start)
                    .addWarnings(warnings)
                    .message("Backed up " + done + " entries (" + bytesDone + " bytes) to "
                            + archive.getFileName()).build();
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp);
            return result.success(false).archive(archive)
                    .durationMillis(System.currentTimeMillis() - start)
                    .addWarnings(warnings)
                    .message("Backup failed: " + e.getMessage()).build();
        }
    }

    /**
     * Extracts {@code archive} into {@code destDir}, guarding every entry
     * against Zip-Slip path traversal.
     *
     * @param archive  the ZIP file to read
     * @param destDir  the folder to extract into (created if absent)
     * @param listener progress sink (may be null)
     * @param cancel   optional cancellation signal polled per entry (may be null)
     * @return the outcome
     */
    public BackupResult restore(Path archive, Path destDir, BackupProgressListener listener,
                                BooleanSupplier cancel) {
        long start = System.currentTimeMillis();
        BackupProgressListener pl = (listener == null) ? BackupProgressListener.NULL : listener;
        BackupResult.Builder result = BackupResult.builder();
        if (archive == null || destDir == null) {
            return result.success(false).message("Archive and destination are required").build();
        }
        List<String> warnings = new ArrayList<>();
        try (ZipFile zf = new ZipFile(archive.toFile())) {
            Path dest = destDir.toAbsolutePath().normalize();
            Files.createDirectories(dest);

            long totalBytes = 0;
            int totalEntries = 0;
            Enumeration<? extends ZipEntry> count = zf.entries();
            while (count.hasMoreElements()) {
                ZipEntry e = count.nextElement();
                totalEntries++;
                if (!e.isDirectory() && e.getSize() > 0) {
                    totalBytes += e.getSize();
                }
            }

            boolean cancelled = false;
            long bytesDone = 0;
            int done = 0;
            int rejected = 0;
            byte[] buf = new byte[BUFFER];
            Enumeration<? extends ZipEntry> it = zf.entries();
            while (it.hasMoreElements()) {
                if (isCancelled(cancel)) {
                    cancelled = true;
                    break;
                }
                ZipEntry entry = it.nextElement();
                String name = entry.getName();
                Path resolved = dest.resolve(name).normalize();
                // Zip Slip guard: refuse any entry that escapes the destination.
                if (!resolved.startsWith(dest)) {
                    rejected++;
                    warnings.add("Skipped unsafe entry (path traversal): " + name);
                    done++;
                    pl.onProgress(name, done, totalEntries, bytesDone, totalBytes);
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    if (resolved.getParent() != null) {
                        Files.createDirectories(resolved.getParent());
                    }
                    try (InputStream in = zf.getInputStream(entry);
                         OutputStream out = Files.newOutputStream(resolved)) {
                        bytesDone += copy(in, out, buf);
                    }
                }
                done++;
                pl.onProgress(name, done, totalEntries, bytesDone, totalBytes);
            }
            String msg = cancelled
                    ? "Restore cancelled"
                    : "Restored " + done + " entries to " + dest.getFileName()
                            + (rejected > 0 ? " (" + rejected + " unsafe skipped)" : "");
            return result.success(!cancelled).cancelled(cancelled).archive(archive)
                    .entryCount(done).totalBytes(bytesDone)
                    .durationMillis(System.currentTimeMillis() - start)
                    .addWarnings(warnings).message(msg).build();
        } catch (IOException | RuntimeException e) {
            return result.success(false).archive(archive)
                    .durationMillis(System.currentTimeMillis() - start)
                    .addWarnings(warnings)
                    .message("Restore failed: " + e.getMessage()).build();
        }
    }

    /**
     * Lists an archive's entries without extracting them.
     *
     * @param archive the ZIP file to read
     * @return the entries, in archive order
     * @throws IOException if the archive cannot be read
     */
    public List<ArchiveEntryInfo> list(Path archive) throws IOException {
        List<ArchiveEntryInfo> out = new ArrayList<>();
        try (ZipFile zf = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry en = e.nextElement();
                out.add(new ArchiveEntryInfo(en.getName(), en.getSize(), en.getCompressedSize(),
                        en.isDirectory(), en.getTime()));
            }
        }
        return out;
    }

    /**
     * Resolves the archive path a profile will write to, applying the optional
     * {@code yyyyMMdd-HHmmss} timestamp.
     *
     * @param profile the profile
     * @return the absolute target archive path (a {@code .zip})
     */
    public Path resolveArchiveName(BackupProfile profile) {
        String base = profile.getArchiveBaseName();
        String fileName = profile.isTimestampArchiveName()
                ? base + "-" + LocalDateTime.now().format(STAMP) + ".zip"
                : base + ".zip";
        return Paths.get(profile.getDestinationDir()).toAbsolutePath().normalize().resolve(fileName);
    }

    // ------------------------------------------------------------------
    // Collection
    // ------------------------------------------------------------------

    private long collectSources(BackupProfile profile, Path archive, Path temp,
                                List<PathMatcher> matchers, List<Path> out,
                                List<String> warnings) {
        long[] totalBytes = {0};
        for (String s : profile.getSources()) {
            if (s == null || s.isBlank()) {
                continue;
            }
            Path src = Paths.get(s).toAbsolutePath().normalize();
            if (!Files.exists(src)) {
                warnings.add("Source does not exist, skipped: " + s);
                continue;
            }
            Path base = (src.getParent() != null) ? src.getParent() : src;
            try {
                Files.walkFileTree(src, new CollectVisitor(base, profile.isIncludeHidden(),
                        matchers, archive, temp, out, warnings, totalBytes));
            } catch (IOException e) {
                warnings.add("Could not read source " + s + ": " + e.getMessage());
            }
        }
        return totalBytes[0];
    }

    /** Walks one source root, collecting included files/dirs and pruning excludes. */
    private static final class CollectVisitor extends SimpleFileVisitor<Path> {
        private final Path base;
        private final boolean includeHidden;
        private final List<PathMatcher> matchers;
        private final Path archive;
        private final Path temp;
        private final List<Path> out;
        private final List<String> warnings;
        private final long[] totalBytes;

        CollectVisitor(Path base, boolean includeHidden, List<PathMatcher> matchers,
                       Path archive, Path temp, List<Path> out, List<String> warnings,
                       long[] totalBytes) {
            this.base = base;
            this.includeHidden = includeHidden;
            this.matchers = matchers;
            this.archive = archive;
            this.temp = temp;
            this.out = out;
            this.warnings = warnings;
            this.totalBytes = totalBytes;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            Path rel = base.relativize(dir);
            if (rel.toString().isEmpty()) {
                return FileVisitResult.CONTINUE;
            }
            if (!includeHidden && isHidden(dir)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            if (matches(rel, matchers)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            out.add(dir);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (sameFile(file, archive) || sameFile(file, temp)) {
                return FileVisitResult.CONTINUE;
            }
            Path rel = base.relativize(file);
            if (!includeHidden && isHidden(file)) {
                return FileVisitResult.CONTINUE;
            }
            if (matches(rel, matchers)) {
                return FileVisitResult.CONTINUE;
            }
            out.add(file);
            totalBytes[0] += Math.max(0, attrs.size());
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            warnings.add("Skipped unreadable path: " + file
                    + (exc == null ? "" : " (" + exc.getMessage() + ")"));
            return FileVisitResult.CONTINUE;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String entryName(BackupProfile profile, Path file) {
        Path abs = file.toAbsolutePath().normalize();
        Path base = (abs.getParent() != null) ? abs.getParent() : abs;
        // Recompute the source base the same way collectSources did: the parent
        // of the source root that contains this file.
        for (String s : profile.getSources()) {
            if (s == null || s.isBlank()) {
                continue;
            }
            Path src = Paths.get(s).toAbsolutePath().normalize();
            Path srcBase = (src.getParent() != null) ? src.getParent() : src;
            if (abs.startsWith(src)) {
                return slash(srcBase.relativize(abs).toString());
            }
        }
        return slash(base.relativize(abs).toString());
    }

    private static String slash(String p) {
        return p.replace('\\', '/');
    }

    private static String uniqueName(String name, Set<String> used) {
        if (used.add(name)) {
            return name;
        }
        String stem = stripExtension(name);
        String ext = extensionOf(name);
        int i = 1;
        String candidate;
        do {
            candidate = stem + " (" + i++ + ")" + ext;
        } while (!used.add(candidate));
        return candidate;
    }

    private static String stripExtension(String name) {
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        return (dot > slash + 1) ? name.substring(0, dot) : name;
    }

    private static String extensionOf(String name) {
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        return (dot > slash + 1) ? name.substring(dot) : "";
    }

    private static List<PathMatcher> compileMatchers(List<String> patterns, List<String> warnings) {
        List<PathMatcher> out = new ArrayList<>();
        if (patterns == null) {
            return out;
        }
        FileSystem fs = FileSystems.getDefault();
        for (String p : patterns) {
            if (p == null || p.isBlank()) {
                continue;
            }
            try {
                out.add(fs.getPathMatcher("glob:" + p.trim()));
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                warnings.add("Ignoring invalid exclude pattern: " + p);
            }
        }
        return out;
    }

    private static boolean matches(Path rel, List<PathMatcher> matchers) {
        if (matchers.isEmpty()) {
            return false;
        }
        Path name = rel.getFileName();
        for (PathMatcher m : matchers) {
            if (m.matches(rel)) {
                return true;
            }
            if (name != null && m.matches(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHidden(Path p) {
        try {
            Path name = p.getFileName();
            return (name != null && name.toString().startsWith(".")) || Files.isHidden(p);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean sameFile(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    private static long safeTime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return System.currentTimeMillis();
        }
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

    private static boolean isCancelled(BooleanSupplier cancel) {
        return cancel != null && cancel.getAsBoolean();
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
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }
}
