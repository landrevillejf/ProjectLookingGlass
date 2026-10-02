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
package org.jdesktop.lg3d.apps.audioplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link AlbumCoverFlow}'s model wiring headless: construction without a
 * peer, {@code setAlbums} / revolve / setCenter selection, the click and
 * double-click callbacks (dispatched directly, no paint), and the lazy cover
 * image resolution (a real file is scaled, a missing cover falls back to a
 * generated placeholder). Nothing is painted, so it runs under
 * {@code java.awt.headless=true}.
 */
class AlbumCoverFlowTest {

    private static AlbumIndex.Album album(String title, String cover) {
        MediaItem item = MediaItem.file("/m/" + title + ".mp3", title,
                "Artist", title, 1, "", cover);
        return AlbumIndex.albums(List.of(item)).get(0);
    }

    @Test
    @DisplayName("an empty flow constructs headless with no selection")
    void emptyConstruction() {
        AlbumCoverFlow flow = new AlbumCoverFlow();
        assertTrue(flow.isEmpty());
        assertEquals(0, flow.albumCount());
        assertEquals(-1, flow.getCenter());
        assertNull(flow.selectedAlbum());
        assertTrue(flow.getAlbums().isEmpty());
    }

    @Test
    @DisplayName("setAlbums installs the albums and selects the first")
    void setAlbums() {
        AlbumCoverFlow flow = new AlbumCoverFlow();
        AlbumIndex.Album a = album("One", "");
        AlbumIndex.Album b = album("Two", "");
        flow.setAlbums(List.of(a, b));
        assertEquals(2, flow.albumCount());
        assertEquals(0, flow.getCenter());
        assertSame(a, flow.selectedAlbum());
        assertEquals(List.of(a, b), flow.getAlbums());
    }

    @Test
    @DisplayName("setAlbums(null) clears the strip; nulls are skipped")
    void clearAndSkipNulls() {
        AlbumCoverFlow flow = new AlbumCoverFlow();
        flow.setAlbums(List.of(album("One", "")));
        flow.setAlbums(null);
        assertTrue(flow.isEmpty());
        java.util.ArrayList<AlbumIndex.Album> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        withNull.add(album("Two", ""));
        flow.setAlbums(withNull);
        assertEquals(1, flow.albumCount());
    }

    @Test
    @DisplayName("revolve wraps both ways and setCenter clamps")
    void revolveAndSetCenter() {
        AlbumCoverFlow flow = new AlbumCoverFlow();
        AlbumIndex.Album a = album("A", "");
        AlbumIndex.Album b = album("B", "");
        AlbumIndex.Album c = album("C", "");
        flow.setAlbums(List.of(a, b, c));
        flow.revolve(1);
        assertSame(b, flow.selectedAlbum());
        flow.revolve(-2);
        assertSame(c, flow.selectedAlbum(), "wraps backwards past the start");
        flow.revolve(1);
        assertSame(a, flow.selectedAlbum());
        flow.setCenter(99);
        assertEquals(2, flow.getCenter(), "clamped to the last index");
        flow.setCenter(-5);
        assertEquals(0, flow.getCenter(), "clamped to the first index");
    }

    @Test
    @DisplayName("a single click selects and a double click plays")
    void clickCallbacks() {
        AlbumCoverFlow flow = new AlbumCoverFlow();
        flow.setSize(640, 240);
        AlbumIndex.Album a = album("Centre", "");
        flow.setAlbums(List.of(a));
        AlbumIndex.Album[] selected = new AlbumIndex.Album[1];
        AlbumIndex.Album[] played = new AlbumIndex.Album[1];
        flow.setOnSelect(sel -> selected[0] = sel);
        flow.setOnPlay(p -> played[0] = p);

        int cx = flow.getWidth() / 2;
        int cy = flow.getHeight() / 2;
        flow.dispatchEvent(new MouseEvent(flow, MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, cx, cy, 1, false));
        assertSame(a, selected[0]);
        assertNull(played[0], "a single click does not play");

        flow.dispatchEvent(new MouseEvent(flow, MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, cx, cy, 2, false));
        assertSame(a, played[0]);
    }

    @Test
    @DisplayName("a click away from any sleeve selects nothing")
    void clickMisses() {
        AlbumCoverFlow flow = new AlbumCoverFlow();
        flow.setSize(640, 240);
        flow.setAlbums(List.of(album("Centre", "")));
        AlbumIndex.Album[] selected = new AlbumIndex.Album[1];
        flow.setOnSelect(sel -> selected[0] = sel);
        flow.dispatchEvent(new MouseEvent(flow, MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, 2, 2, 1, false));
        assertNull(selected[0]);
    }

    @Test
    @DisplayName("albumAt hit-tests the centre sleeve")
    void albumAt() {
        AlbumCoverFlow flow = new AlbumCoverFlow();
        flow.setSize(640, 240);
        AlbumIndex.Album a = album("Centre", "");
        flow.setAlbums(List.of(a));
        assertSame(a, flow.albumAt(320, 120));
        assertNull(flow.albumAt(1, 1));
    }

    @Test
    @DisplayName("a missing cover resolves to a generated placeholder image")
    void placeholderImage() {
        AlbumCoverFlow flow = new AlbumCoverFlow();
        BufferedImage img = flow.imageFor(album("No Cover", ""), 60);
        assertNotNull(img);
        assertEquals(60, img.getWidth());
        assertEquals(60, img.getHeight());
    }

    @Test
    @DisplayName("a real cover file is read and scaled, then cached")
    void readsCoverFile(@TempDir Path dir) throws Exception {
        File cover = dir.resolve("cover.png").toFile();
        BufferedImage src = new BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB);
        src.setRGB(150, 150, 0xFF2244);
        ImageIO.write(src, "png", cover);

        AlbumCoverFlow flow = new AlbumCoverFlow();
        AlbumIndex.Album a = album("Covered", cover.getAbsolutePath());
        BufferedImage img = flow.imageFor(a, 80);
        assertNotNull(img);
        assertEquals(80, img.getWidth());
        assertEquals(80, img.getHeight());
        // Second call is served from the cache (same instance).
        assertSame(img, flow.imageFor(a, 80));
    }

    @Test
    @DisplayName("a cover path that is not a readable image falls back to a placeholder")
    void unreadableCoverFallsBack() {
        AlbumCoverFlow flow = new AlbumCoverFlow();
        BufferedImage img = flow.imageFor(album("Bad", "/nope/missing.jpg"), 50);
        assertNotNull(img);
        assertEquals(50, img.getWidth());
    }
}
