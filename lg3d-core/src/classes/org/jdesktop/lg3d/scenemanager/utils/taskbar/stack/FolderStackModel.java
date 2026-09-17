/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.scenemanager.utils.taskbar.stack;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Icon;
import javax.swing.filechooser.FileSystemView;

/**
 * Backs a dock folder stack from a real directory (e.g. {@code ~/Documents}).
 *
 * <p>{@link #refresh()} rescans the directory and keeps the
 * {@value #DEFAULT_MAX_ENTRIES} most-recently-modified entries, newest first.
 * Each entry carries its name, path, kind, size, modification time and the
 * desktop's own file-type icon (via {@link FileSystemView#getSystemIcon(File)}),
 * so the popup shows crisp, real MIME icons.</p>
 *
 * <p>Reading a directory that disappears or is unreadable yields an empty list
 * rather than throwing, so a stack for a missing folder degrades gracefully.</p>
 */
public class FolderStackModel {
    private static final Logger logger = Logger.getLogger("lg.scenemanager");

    /** How many of the most-recent entries a stack shows. */
    public static final int DEFAULT_MAX_ENTRIES = 20;

    private final Path directory;
    private final int maxEntries;

    private List<StackItem> items = Collections.emptyList();

    public FolderStackModel(Path directory) {
        this(directory, DEFAULT_MAX_ENTRIES);
    }

    public FolderStackModel(Path directory, int maxEntries) {
        this.directory = directory;
        this.maxEntries = (maxEntries > 0) ? maxEntries : DEFAULT_MAX_ENTRIES;
    }

    public Path getDirectory() {
        return directory;
    }

    /** Human-readable name for the stack (the directory's file name). */
    public String getDisplayName() {
        Path name = directory.getFileName();
        return (name != null) ? name.toString() : directory.toString();
    }

    /** True if the backing directory exists and is readable. */
    public boolean isAvailable() {
        return Files.isDirectory(directory) && Files.isReadable(directory);
    }

    /** Rescans the directory, replacing {@link #getItems()}. */
    public synchronized void refresh() {
        List<Path> entries = new ArrayList<>();
        if (isAvailable()) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(directory)) {
                for (Path p : ds) {
                    entries.add(p);
                }
            } catch (IOException | RuntimeException e) {
                logger.log(Level.FINE, "Could not list " + directory, e);
            }
        }
        entries.sort((a, b) -> Long.compare(lastModified(b), lastModified(a)));
        if (entries.size() > maxEntries) {
            entries = new ArrayList<>(entries.subList(0, maxEntries));
        }
        List<StackItem> built = new ArrayList<>(entries.size());
        for (Path p : entries) {
            built.add(new StackItem(p));
        }
        this.items = Collections.unmodifiableList(built);
    }

    /** The current (most-recent-first) entries; empty until {@link #refresh()}. */
    public synchronized List<StackItem> getItems() {
        return items;
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
    }

    /** One entry in the folder stack. */
    public static final class StackItem {
        private final String name;
        private final Path path;
        private final boolean directory;
        private final long size;
        private final long modified;
        private final Icon icon;

        StackItem(Path path) {
            this.path = path;
            this.name = path.getFileName() != null
                    ? path.getFileName().toString() : path.toString();
            this.directory = Files.isDirectory(path);
            long sz = 0L;
            try {
                if (!directory) {
                    sz = Files.size(path);
                }
            } catch (IOException | RuntimeException e) {
                sz = 0L;
            }
            this.size = sz;
            this.modified = lastModified(path);
            this.icon = systemIcon(path);
        }

        private static Icon systemIcon(Path path) {
            try {
                return FileSystemView.getFileSystemView().getSystemIcon(path.toFile());
            } catch (RuntimeException e) {
                return null;
            }
        }

        public String getName() { return name; }
        public Path getPath() { return path; }
        public boolean isDirectory() { return directory; }
        public long getSize() { return size; }
        public long getModified() { return modified; }
        /** The desktop file-type icon, or null if it could not be resolved. */
        public Icon getIcon() { return icon; }

        /** "Folder" for directories, otherwise the upper-cased extension. */
        public String getTypeLabel() {
            if (directory) {
                return "Folder";
            }
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot < name.length() - 1) {
                return name.substring(dot + 1).toUpperCase();
            }
            return "File";
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
