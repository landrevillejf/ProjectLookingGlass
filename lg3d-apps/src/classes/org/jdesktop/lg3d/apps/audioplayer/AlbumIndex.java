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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups a flat {@link MediaItem} library into albums so both cover carousels
 * (the 3D {@code CDViewer} and the Swing {@link AlbumCoverFlow}) can present one
 * sleeve per album. Grouping is by MusicBrainz release MBID when present, else by
 * a lower-cased {@code artist|album} key, so the same album ripped twice (or
 * tagged by hand) collapses to one entry while distinct albums never merge.
 *
 * <p>Items with no album tag fall into a per-item "single" album so they still
 * appear rather than vanishing. Pure and AWT-free, so {@code AlbumIndexTest}
 * covers the grouping headless.</p>
 */
public final class AlbumIndex {

    /** One album: its identity, cover path and ordered tracks. */
    public static final class Album {
        /** The MusicBrainz release MBID (blank when unknown). */
        public final String mbid;
        /** The album artist (blank when unknown). */
        public final String artist;
        /** The album title (never blank; falls back to a single's name). */
        public final String title;
        /** The cached cover image path (blank when there is none). */
        public final String coverPath;
        /** The album's tracks, ordered by track number then title. */
        public final List<MediaItem> tracks;

        Album(String mbid, String artist, String title, String coverPath,
              List<MediaItem> tracks) {
            this.mbid = (mbid == null) ? "" : mbid;
            this.artist = (artist == null) ? "" : artist;
            this.title = (title == null || title.isBlank()) ? "Unknown Album" : title;
            this.coverPath = (coverPath == null) ? "" : coverPath;
            this.tracks = List.copyOf(tracks);
        }

        /** @return true when a cover image file path is present. */
        public boolean hasCover() {
            return !coverPath.isBlank();
        }

        /** A display label: {@code Artist - Title}, or just the title. */
        public String label() {
            return artist.isBlank() ? title : artist + " \u2013 " + title;
        }

        @Override
        public String toString() {
            return label();
        }
    }

    private static final Comparator<MediaItem> TRACK_ORDER =
            Comparator.comparingInt(MediaItem::getTrackNumber)
                    .thenComparing(MediaItem::toString);

    private AlbumIndex() {
        // no instances
    }

    /**
     * Groups {@code items} into albums, preserving first-seen order.
     *
     * @param items the library items; may be null
     * @return the album list (never null; empty when there are no items)
     */
    public static List<Album> albums(List<MediaItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Map<String, List<MediaItem>> grouped = new LinkedHashMap<>();
        for (MediaItem item : items) {
            if (item == null) {
                continue;
            }
            grouped.computeIfAbsent(keyFor(item), k -> new ArrayList<>()).add(item);
        }
        List<Album> albums = new ArrayList<>(grouped.size());
        for (List<MediaItem> group : grouped.values()) {
            group.sort(TRACK_ORDER);
            albums.add(toAlbum(group));
        }
        return albums;
    }

    /** True when {@code items} contains no album-worthy entries. */
    public static boolean isEmpty(List<MediaItem> items) {
        return items == null || items.isEmpty();
    }

    private static String keyFor(MediaItem item) {
        String mbid = item.getMbid();
        if (mbid != null && !mbid.isBlank()) {
            return "mbid:" + mbid.trim().toLowerCase(java.util.Locale.ROOT);
        }
        String album = item.getAlbum();
        String artist = item.getArtist();
        if (album != null && !album.isBlank()) {
            return "aa:" + nz(artist).toLowerCase(java.util.Locale.ROOT)
                    + "|" + album.trim().toLowerCase(java.util.Locale.ROOT);
        }
        // Untagged: treat each item as its own single so nothing disappears.
        return "single:" + System.identityHashCode(item);
    }

    private static Album toAlbum(List<MediaItem> group) {
        MediaItem first = group.get(0);
        String mbid = "";
        String artist = "";
        String title = "";
        String cover = "";
        for (MediaItem m : group) {
            if (mbid.isBlank() && !m.getMbid().isBlank()) {
                mbid = m.getMbid();
            }
            if (artist.isBlank() && !m.getArtist().isBlank()) {
                artist = m.getArtist();
            }
            if (title.isBlank() && !m.getAlbum().isBlank()) {
                title = m.getAlbum();
            }
            if (cover.isBlank() && !m.getCoverFile().isBlank()) {
                cover = m.getCoverFile();
            }
        }
        if (title.isBlank()) {
            // A single: use the track's own name as the sleeve title.
            title = first.toString();
        }
        return new Album(mbid, artist, title, cover, group);
    }

    private static String nz(String s) {
        return (s == null) ? "" : s.trim();
    }
}
