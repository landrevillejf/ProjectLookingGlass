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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One entry of the audio player's library / playlist: a local file, a network
 * stream, an AM/FM or internet radio station, or a podcast episode. Items are
 * plain Jackson beans persisted by {@link AudioPlayerStore}, so the class stays
 * serialisable (no-arg constructor plus getters/setters, no live handles).
 *
 * <p>{@code location} is either an absolute file path or a URL; the
 * {@link Kind} records how the entry was added so the UI can show the right
 * glyph and the backend can pick native versus external playback.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MediaItem {

    /** What an entry points at. */
    public enum Kind {
        /** A local audio file on disk. */
        FILE,
        /** A generic network audio stream (URL). */
        STREAM,
        /** A saved radio station (AM/FM or internet). */
        RADIO,
        /** A podcast episode or feed URL. */
        PODCAST
    }

    private String name = "";
    private String location = "";
    private Kind kind = Kind.FILE;
    private long addedMillis = System.currentTimeMillis();

    /** No-arg constructor for Jackson. */
    public MediaItem() {
    }

    /**
     * Convenience constructor.
     *
     * @param name     the display name (a blank name falls back to the
     *                 location's last path segment)
     * @param location the file path or stream URL
     * @param kind     how the entry should be treated
     */
    public MediaItem(String name, String location, Kind kind) {
        this.location = (location == null) ? "" : location;
        this.kind = (kind == null) ? Kind.FILE : kind;
        this.name = (name == null || name.isBlank())
                ? deriveName(this.location) : name;
    }

    /** Builds a {@link Kind#FILE} item from a path. */
    public static MediaItem file(String path) {
        return new MediaItem(null, path, Kind.FILE);
    }

    /** Builds a stream / radio / podcast item from a name and URL. */
    public static MediaItem stream(String name, String url, Kind kind) {
        return new MediaItem(name, url, kind);
    }

    /** The last path segment (minus extension) of {@code location}. */
    static String deriveName(String location) {
        if (location == null || location.isBlank()) {
            return "Untitled";
        }
        String s = location.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0 && slash < s.length() - 1) {
            s = s.substring(slash + 1);
        }
        int dot = s.lastIndexOf('.');
        if (dot > 0) {
            s = s.substring(0, dot);
        }
        return s.isBlank() ? "Untitled" : s;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = (name == null || name.isBlank()) ? deriveName(location) : name;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = (location == null) ? "" : location;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = (kind == null) ? Kind.FILE : kind;
    }

    public long getAddedMillis() {
        return addedMillis;
    }

    public void setAddedMillis(long addedMillis) {
        this.addedMillis = addedMillis;
    }

    @Override
    public String toString() {
        return (name == null || name.isBlank()) ? deriveName(location) : name;
    }
}
