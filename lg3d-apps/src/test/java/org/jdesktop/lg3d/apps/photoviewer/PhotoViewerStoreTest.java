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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers {@link PhotoViewerStore}'s defensive JSON round-trip. */
class PhotoViewerStoreTest {

    @Test
    @DisplayName("a library survives a save / load round-trip with tags intact")
    void roundTrips(@TempDir Path dir) {
        PhotoViewerStore store = new PhotoViewerStore(dir);
        PhotoItem item = PhotoItem.of("/tmp/sunset.jpg");
        item.setTitle("Sunset");
        item.addTag("beach");
        item.setRating(4);
        store.saveLibrary(List.of(item));

        List<PhotoItem> loaded = new PhotoViewerStore(dir).loadLibrary();
        assertEquals(1, loaded.size());
        PhotoItem back = loaded.get(0);
        assertEquals("/tmp/sunset.jpg", back.getPath());
        assertEquals("Sunset", back.getTitle());
        assertTrue(back.hasTag("beach"));
        assertEquals(4, back.getRating());
    }

    @Test
    @DisplayName("a missing file loads as an empty library, never throws")
    void missingIsEmpty(@TempDir Path dir) {
        assertTrue(new PhotoViewerStore(dir).loadLibrary().isEmpty());
    }

    @Test
    @DisplayName("a corrupt file loads as an empty library, never throws")
    void corruptIsEmpty(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(PhotoViewerStore.LIBRARY_FILE), "{not json]");
        assertTrue(new PhotoViewerStore(dir).loadLibrary().isEmpty());
    }

    @Test
    @DisplayName("saving null persists an empty list without error")
    void saveNullIsSafe(@TempDir Path dir) {
        PhotoViewerStore store = new PhotoViewerStore(dir);
        store.saveLibrary(null);
        assertTrue(store.loadLibrary().isEmpty());
    }

    @Test
    @DisplayName("the DIR_PROPERTY override resolves the default directory")
    void honoursOverride() {
        String previous = System.getProperty(PhotoViewerStore.DIR_PROPERTY);
        try {
            System.setProperty(PhotoViewerStore.DIR_PROPERTY, "/tmp/pv-override");
            assertEquals(Path.of("/tmp/pv-override"), PhotoViewerStore.defaultConfigDir());
            assertEquals(Path.of("/tmp/pv-override"),
                    new PhotoViewerStore().getConfigDir());
        } finally {
            if (previous == null) {
                System.clearProperty(PhotoViewerStore.DIR_PROPERTY);
            } else {
                System.setProperty(PhotoViewerStore.DIR_PROPERTY, previous);
            }
        }
    }
}
