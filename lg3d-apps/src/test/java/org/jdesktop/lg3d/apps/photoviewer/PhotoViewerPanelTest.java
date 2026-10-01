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
package org.jdesktop.lg3d.apps.photoviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link PhotoViewerPanel}'s construction and library editing without
 * ever opening a file chooser or decoding a real photo during build: the panel
 * is created over a {@link TempDir} store, then asserted on through its
 * package-private test hooks. Swing widgets construct headless, so this runs in
 * CI.
 */
class PhotoViewerPanelTest {

    @Test
    @DisplayName("a fresh panel starts empty, ready and unfiltered")
    void defaultsAreIdle(@TempDir Path dir) {
        PhotoViewerPanel panel = new PhotoViewerPanel(new PhotoViewerStore(dir));
        assertEquals(0, panel.librarySize());
        assertEquals(0, panel.visibleCount());
        assertEquals("Ready", panel.statusText());
        assertNull(panel.currentPhoto());
        assertEquals(java.util.List.of("All tags"), panel.tagFilterOptions());
    }

    @Test
    @DisplayName("addFile grows both the library and the visible gallery")
    void addFileGrowsLibrary(@TempDir Path dir) {
        PhotoViewerPanel panel = new PhotoViewerPanel(new PhotoViewerStore(dir));
        panel.addFile("/pics/Alpha.png");
        panel.addFile("/pics/Beta.jpg");
        assertEquals(2, panel.librarySize());
        assertEquals(2, panel.visibleCount());
        assertEquals("Alpha", panel.library().get(0).getTitle());
        assertEquals("Beta", panel.library().get(1).getTitle());
    }

    @Test
    @DisplayName("blank, null and duplicate paths are ignored")
    void addFileIgnoresJunk(@TempDir Path dir) {
        PhotoViewerPanel panel = new PhotoViewerPanel(new PhotoViewerStore(dir));
        panel.addFile("   ");
        panel.addFile(null);
        panel.addFile("/pics/One.png");
        panel.addFile("/pics/One.png");
        assertEquals(1, panel.librarySize());
    }

    @Test
    @DisplayName("selecting a gallery row makes it the current photo")
    void selectsPhoto(@TempDir Path dir) {
        PhotoViewerPanel panel = new PhotoViewerPanel(new PhotoViewerStore(dir));
        panel.addFile("/pics/Alpha.png");
        panel.addFile("/pics/Beta.png");
        panel.selectIndex(1);
        assertNotNull(panel.currentPhoto());
        assertEquals("/pics/Beta.png", panel.currentPhoto().getPath());
        panel.selectIndex(99);
        assertEquals("/pics/Beta.png", panel.currentPhoto().getPath(),
                "an out-of-range selection is ignored");
    }

    @Test
    @DisplayName("a persisted library is reloaded on the next open")
    void reloadsPersistedLibrary(@TempDir Path dir) {
        PhotoViewerStore store = new PhotoViewerStore(dir);
        PhotoViewerPanel first = new PhotoViewerPanel(store);
        first.addFile("/pics/Keep.png");

        PhotoViewerPanel second = new PhotoViewerPanel(new PhotoViewerStore(dir));
        assertEquals(1, second.librarySize());
        assertEquals("Keep", second.library().get(0).getTitle());
    }

    @Test
    @DisplayName("scanFolder adds only recognisable images, not text files")
    void scanFolderFiltersByExtension(@TempDir Path dir) throws Exception {
        File photo = dir.resolve("real.png").toFile();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", photo);
        dir.resolve("notes.txt").toFile().createNewFile();
        dir.resolve("archive.zip").toFile().createNewFile();

        PhotoViewerPanel panel = new PhotoViewerPanel(new PhotoViewerStore(dir));
        int added = panel.scanFolder(dir.toFile());
        assertEquals(1, added);
        assertEquals(1, panel.librarySize());
        assertEquals("real", panel.library().get(0).getTitle());
        assertTrue(panel.library().get(0).getPath().endsWith("real.png"));
    }

    @Test
    @DisplayName("scanFolder tolerates a null or missing directory")
    void scanFolderIsSafe(@TempDir Path dir) {
        PhotoViewerPanel panel = new PhotoViewerPanel(new PhotoViewerStore(dir));
        assertEquals(0, panel.scanFolder(null));
        assertEquals(0, panel.scanFolder(dir.resolve("nope").toFile()));
        assertEquals(0, panel.librarySize());
    }

    @Test
    @DisplayName("the exposed model reflects tags added through it")
    void exposesModel(@TempDir Path dir) {
        PhotoViewerPanel panel = new PhotoViewerPanel(new PhotoViewerStore(dir));
        panel.addFile("/pics/Alpha.png");
        panel.model().get(0).addTag("beach");
        assertTrue(panel.library().get(0).hasTag("beach"));
    }
}
