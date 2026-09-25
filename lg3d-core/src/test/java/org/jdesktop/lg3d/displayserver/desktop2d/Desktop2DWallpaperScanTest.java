/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link Desktop2D#scanFolder(File)}: the slideshow's source-directory
 * scan. The method is package-visible and static, so it is exercised here
 * against a temporary directory without a live shell.
 */
class Desktop2DWallpaperScanTest {

    @Test
    @DisplayName("only the images directly in the folder are returned, sorted by name")
    void scansSortsAndFilters(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("b.png"), "x");
        Files.writeString(dir.resolve("a.JPG"), "x");
        Files.writeString(dir.resolve("notes.txt"), "x");
        Files.writeString(dir.resolve("archive.zip"), "x");
        Files.createDirectory(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub").resolve("c.jpg"), "x");

        List<URL> urls = Desktop2D.scanFolder(dir.toFile());

        assertEquals(2, urls.size(), "non-images and the nested file are skipped");
        assertTrue(urls.get(0).toString().endsWith("a.JPG"), "sorted by name");
        assertTrue(urls.get(1).toString().endsWith("b.png"));
    }

    @Test
    @DisplayName("an empty directory yields no wallpapers")
    void emptyDirIsEmpty(@TempDir Path dir) {
        assertTrue(Desktop2D.scanFolder(dir.toFile()).isEmpty());
    }

    @Test
    @DisplayName("a null or absent directory yields no wallpapers")
    void absentDirIsEmpty() {
        assertTrue(Desktop2D.scanFolder(null).isEmpty());
        assertTrue(Desktop2D.scanFolder(new File("/no/such/wallpaper/dir")).isEmpty());
    }

    @Test
    @DisplayName("a file (not a directory) yields no wallpapers")
    void fileNotDirIsEmpty(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("a.jpg"), "x");
        assertTrue(Desktop2D.scanFolder(file.toFile()).isEmpty());
    }
}
