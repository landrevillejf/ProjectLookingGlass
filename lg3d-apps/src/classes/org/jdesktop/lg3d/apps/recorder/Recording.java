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
package org.jdesktop.lg3d.apps.recorder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One finished recording in the history: an absolute file path, whether it is an
 * audio (WAV) or video (screen) capture, an approximate duration and a title.
 * Items are plain Jackson beans persisted by {@link RecorderStore}, so the class
 * stays serialisable (no-arg constructor plus getters/setters, no media handle).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Recording {

    /** Whether the capture is audio-only or a screen recording. */
    public enum Kind {
        /** A microphone capture written as WAV. */
        AUDIO,
        /** A screen capture written by the external recorder. */
        VIDEO
    }

    private String path = "";
    private String title = "";
    private Kind kind = Kind.AUDIO;
    private long durationMillis = 0L;
    private long addedMillis = System.currentTimeMillis();

    /** No-arg constructor for Jackson. */
    public Recording() {
    }

    /**
     * Convenience constructor.
     *
     * @param path  the absolute file path
     * @param kind  audio or video
     * @param title the display title (blank falls back to the file name)
     */
    public Recording(String path, Kind kind, String title) {
        this.path = (path == null) ? "" : path;
        this.kind = (kind == null) ? Kind.AUDIO : kind;
        this.title = (title == null || title.isBlank()) ? deriveTitle(this.path) : title;
    }

    /** Builds an audio recording from a path. */
    public static Recording audio(String path) {
        return new Recording(path, Kind.AUDIO, null);
    }

    /** Builds a video recording from a path. */
    public static Recording video(String path) {
        return new Recording(path, Kind.VIDEO, null);
    }

    /** The file name (minus extension) of {@code path}. */
    static String deriveTitle(String path) {
        if (path == null || path.isBlank()) {
            return "Recording";
        }
        String s = path.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0 && slash < s.length() - 1) {
            s = s.substring(slash + 1);
        }
        int dot = s.lastIndexOf('.');
        if (dot > 0) {
            s = s.substring(0, dot);
        }
        return s.isBlank() ? "Recording" : s;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = (path == null) ? "" : path;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = (title == null || title.isBlank()) ? deriveTitle(path) : title;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = (kind == null) ? Kind.AUDIO : kind;
    }

    /** @return the approximate capture duration in milliseconds. */
    public long getDurationMillis() {
        return durationMillis;
    }

    public void setDurationMillis(long durationMillis) {
        this.durationMillis = Math.max(0L, durationMillis);
    }

    public long getAddedMillis() {
        return addedMillis;
    }

    public void setAddedMillis(long addedMillis) {
        this.addedMillis = addedMillis;
    }

    @Override
    public String toString() {
        String label = (title == null || title.isBlank()) ? deriveTitle(path) : title;
        return (kind == Kind.VIDEO ? "[video] " : "[audio] ") + label;
    }
}
