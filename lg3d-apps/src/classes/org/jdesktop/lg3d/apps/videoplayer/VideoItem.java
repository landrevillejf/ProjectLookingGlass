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
package org.jdesktop.lg3d.apps.videoplayer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One entry of the video player's library: a local movie file, a network stream
 * URL, or an optical disc device. Items are plain Jackson beans persisted by
 * {@link VideoPlayerStore}, so the class stays serialisable (no-arg constructor
 * plus getters/setters, no live handles).
 *
 * <p>{@code location} is an absolute file path, a URL, or a disc device such as
 * {@code /dev/sr0}; the {@link Kind} records how the entry was added so the UI
 * can show the right glyph and the backend can build the launch command.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VideoItem {

    /** What an entry points at. */
    public enum Kind {
        /** A local video file on disk. */
        FILE,
        /** A network stream URL (http/rtsp/rtmp/...). */
        STREAM,
        /** An optical disc device (DVD / Blu-ray). */
        DISC
    }

    private String name = "";
    private String location = "";
    private Kind kind = Kind.FILE;
    private long addedMillis = System.currentTimeMillis();

    /** No-arg constructor for Jackson. */
    public VideoItem() {
    }

    /**
     * Convenience constructor.
     *
     * @param name     the display name (a blank name falls back to the
     *                 location's last path segment)
     * @param location the file path, stream URL or disc device
     * @param kind     how the entry should be treated
     */
    public VideoItem(String name, String location, Kind kind) {
        this.location = (location == null) ? "" : location;
        this.kind = (kind == null) ? Kind.FILE : kind;
        this.name = (name == null || name.isBlank())
                ? deriveName(this.location) : name;
    }

    /** Builds a {@link Kind#FILE} item from a path. */
    public static VideoItem file(String path) {
        return new VideoItem(null, path, Kind.FILE);
    }

    /** Builds a stream or disc item from a name and location. */
    public static VideoItem of(String name, String location, Kind kind) {
        return new VideoItem(name, location, kind);
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
