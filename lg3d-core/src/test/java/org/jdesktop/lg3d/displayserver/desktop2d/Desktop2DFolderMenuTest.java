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
package org.jdesktop.lg3d.displayserver.desktop2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.scenemanager.utils.taskbar.stack.FolderStackModel.StackItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the Documents/Downloads menu listing: it must show the most recently
 * modified entries newest-first, capped at {@link Desktop2DFolderMenu#MAX_ENTRIES},
 * exactly like the 3D folder stacks it mirrors, and degrade to an empty list for
 * a missing or empty directory. The label ellipsising rule is checked too.
 */
class Desktop2DFolderMenuTest {

    @TempDir
    Path tempDir;

    /** Creates {@code name} in the temp dir with a deterministic mtime. */
    private Path file(String name, long millis) throws Exception {
        Path path = tempDir.resolve(name);
        Files.write(path, name.getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(path, FileTime.fromMillis(millis));
        return path;
    }

    private List<String> names(List<StackItem> items) {
        List<String> out = new ArrayList<>(items.size());
        for (StackItem item : items) {
            out.add(item.getName());
        }
        return out;
    }

    @Test
    @DisplayName("entries are listed newest-first")
    void newestFirst() throws Exception {
        long base = 1_600_000_000_000L;
        file("oldest.txt", base);
        file("middle.txt", base + 60_000L);
        file("newest.txt", base + 120_000L);

        List<StackItem> entries = Desktop2DFolderMenu.entries(tempDir);
        assertEquals(List.of("newest.txt", "middle.txt", "oldest.txt"),
                names(entries));
    }

    @Test
    @DisplayName("the listing is capped at MAX_ENTRIES, keeping the newest")
    void cappedAtMaxEntries() throws Exception {
        long base = 1_600_000_000_000L;
        int total = Desktop2DFolderMenu.MAX_ENTRIES + 5;   // 17 files
        for (int i = 0; i < total; i++) {
            file(String.format("f%02d.txt", i), base + i * 1000L);
        }

        List<StackItem> entries = Desktop2DFolderMenu.entries(tempDir);
        assertEquals(Desktop2DFolderMenu.MAX_ENTRIES, entries.size());
        // The newest is f16; the oldest kept is f16 - (MAX_ENTRIES - 1) = f05.
        assertEquals("f16.txt", entries.get(0).getName());
        assertEquals(String.format("f%02d.txt",
                total - Desktop2DFolderMenu.MAX_ENTRIES), 
                entries.get(entries.size() - 1).getName());
    }

    @Test
    @DisplayName("an empty directory yields no entries")
    void emptyDirectory() throws Exception {
        Path empty = tempDir.resolve("empty");
        Files.createDirectories(empty);
        assertTrue(Desktop2DFolderMenu.entries(empty).isEmpty());
    }

    @Test
    @DisplayName("a missing directory yields no entries instead of throwing")
    void missingDirectory() {
        Path missing = tempDir.resolve("does-not-exist");
        assertTrue(Desktop2DFolderMenu.entries(missing).isEmpty());
    }

    @Test
    @DisplayName("stack items carry the folder/extension kind and size")
    void itemMetadata() throws Exception {
        file("report.pdf", 1_600_000_000_000L);
        Files.createDirectory(tempDir.resolve("subdir"));

        List<StackItem> entries = Desktop2DFolderMenu.entries(tempDir);
        assertEquals(2, entries.size());
        StackItem dir = entries.stream()
                .filter(StackItem::isDirectory).findFirst().orElseThrow();
        StackItem pdf = entries.stream()
                .filter(i -> !i.isDirectory()).findFirst().orElseThrow();
        assertEquals("Folder", dir.getTypeLabel());
        assertEquals("PDF", pdf.getTypeLabel());
        assertEquals("report.pdf".length(), pdf.getSize());
    }

    // ------------------------------------------------------------------
    // Label ellipsising
    // ------------------------------------------------------------------

    @Test
    @DisplayName("short names are left untouched")
    void ellipsiseKeepsShortNames() {
        assertEquals("report.pdf", Desktop2DFolderMenu.ellipsise("report.pdf"));
        String exact = "x".repeat(Desktop2DFolderMenu.MAX_LABEL);
        assertEquals(exact, Desktop2DFolderMenu.ellipsise(exact),
                "a name exactly at the limit is not truncated");
    }

    @Test
    @DisplayName("long names are truncated with an ellipsis at the limit")
    void ellipsiseTruncatesLongNames() {
        String longName = "x".repeat(Desktop2DFolderMenu.MAX_LABEL + 10);
        String result = Desktop2DFolderMenu.ellipsise(longName);
        assertEquals(Desktop2DFolderMenu.MAX_LABEL, result.length());
        assertTrue(result.endsWith("\u2026"));
        assertEquals("x".repeat(Desktop2DFolderMenu.MAX_LABEL - 1) + "\u2026",
                result);
    }

    @Test
    @DisplayName("a null name ellipsises to the empty string")
    void ellipsiseHandlesNull() {
        assertEquals("", Desktop2DFolderMenu.ellipsise(null));
    }
}
