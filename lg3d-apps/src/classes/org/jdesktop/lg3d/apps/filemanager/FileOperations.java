/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.function.LongConsumer;
import org.jdesktop.lg3d.utils.system.Opener;

/**
 * Filesystem operations for the file manager, built on {@code java.nio.file}
 * and the shared {@link Opener} (for trash / xdg-open).
 *
 * <p>Every operation reports success/failure rather than throwing, so the UI
 * can show a message. Copy and move recurse into directories and report byte
 * progress through an optional callback (driven from a {@code SwingWorker}).</p>
 */
public final class FileOperations {

    private FileOperations() {
        // no instances
    }

    /** Renames {@code path} to {@code newName} within its parent directory. */
    public static boolean rename(Path path, String newName) {
        if (path == null || newName == null || newName.isBlank()) {
            return false;
        }
        try {
            Path target = path.resolveSibling(newName);
            if (Files.exists(target)) {
                return false;
            }
            Files.move(path, target);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Creates a new directory {@code name} inside {@code parent}. */
    public static boolean newFolder(Path parent, String name) {
        if (parent == null || name == null || name.isBlank()) {
            return false;
        }
        try {
            Path target = unique(parent.resolve(name));
            Files.createDirectories(target);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Moves the given paths to the trash (via {@link Opener#trash(Path)}). */
    public static boolean trash(List<Path> paths) {
        boolean all = true;
        for (Path p : paths) {
            if (!Opener.trash(p)) {
                all = false;
            }
        }
        return all;
    }

    /**
     * Copies each source into {@code destDir} (recursing into directories).
     *
     * @param progress receives cumulative bytes copied (may be null)
     */
    public static boolean copy(List<Path> sources, Path destDir, LongConsumer progress) {
        boolean all = true;
        long[] done = {0L};
        for (Path src : sources) {
            Path dest = unique(destDir.resolve(fileName(src)));
            if (!copyOne(src, dest, progress, done)) {
                all = false;
            }
        }
        return all;
    }

    /**
     * Moves each source into {@code destDir} (recursing into directories).
     *
     * @param progress receives cumulative bytes moved (may be null)
     */
    public static boolean move(List<Path> sources, Path destDir, LongConsumer progress) {
        boolean all = true;
        for (Path src : sources) {
            Path dest = unique(destDir.resolve(fileName(src)));
            if (!moveOne(src, dest, progress)) {
                all = false;
            }
        }
        return all;
    }

    /** Total size in bytes of the given paths (recursing into directories). */
    public static long totalSize(List<Path> paths) {
        long[] total = {0L};
        for (Path p : paths) {
            walk(p, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return total[0];
    }

    // ------------------------------------------------------------------

    private static boolean copyOne(Path src, Path dest, LongConsumer progress, long[] done) {
        try {
            Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    Files.createDirectories(resolve(src, dir, dest));
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.copy(file, resolve(src, file, dest),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                    done[0] += attrs.size();
                    if (progress != null) {
                        progress.accept(done[0]);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean moveOne(Path src, Path dest, LongConsumer progress) {
        try {
            Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
            if (progress != null) {
                progress.accept(totalSize(List.of(dest)));
            }
            return true;
        } catch (IOException | RuntimeException e) {
            // Cross-device or non-atomic: fall back to copy + delete.
            long[] done = {0L};
            if (copyOne(src, dest, progress, done)) {
                deleteRecursively(src);
                return !Files.exists(src);
            }
            return false;
        }
    }

    private static void deleteRecursively(Path root) {
        walk(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void walk(Path root, SimpleFileVisitor<Path> visitor) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, visitor);
        } catch (IOException | RuntimeException e) {
            // best effort
        }
    }

    /** Maps a path under {@code src} to the corresponding path under {@code dest}. */
    private static Path resolve(Path src, Path current, Path dest) {
        if (current.equals(src)) {
            return dest;
        }
        return dest.resolve(src.relativize(current).toString());
    }

    private static String fileName(Path p) {
        Path name = p.getFileName();
        return (name != null) ? name.toString() : p.toString();
    }

    /** Returns {@code p}, or {@code p} with a " (n)" suffix if it already exists. */
    private static Path unique(Path p) {
        if (!Files.exists(p)) {
            return p;
        }
        String name = fileName(p);
        Path parent = p.getParent();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 1;
        Path candidate = p;
        while (Files.exists(candidate)) {
            String nn = base + " (" + i + ")" + ext;
            candidate = (parent != null) ? parent.resolve(nn) : p.resolveSibling(nn);
            i++;
        }
        return candidate;
    }
}
