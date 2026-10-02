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
import java.util.Collections;
import java.util.List;

/**
 * An audio CD's table of contents: the first track number and the ordered list
 * of audio tracks, each described by its start and length in CD frames
 * (sectors, 1/75&nbsp;s). A frame count is used - not milliseconds - because it
 * is what {@code cdparanoia}/{@code cd-info} report and what the MusicBrainz
 * discid algorithm ({@link MusicBrainzDiscId}) consumes.
 *
 * <p>Immutable and AWT-free, so it is safe to build headless from parsed tool
 * output.</p>
 */
public final class Toc {

    /** One audio track: its number and its start / length in CD frames. */
    public static final class Track {
        /** The 1-based track number. */
        public final int number;
        /** The track's start offset in frames (LBA sectors). */
        public final long startLba;
        /** The track's length in frames. */
        public final long lengthLba;

        public Track(int number, long startLba, long lengthLba) {
            this.number = number;
            this.startLba = startLba;
            this.lengthLba = lengthLba;
        }

        @Override
        public String toString() {
            return "Track " + number + " @" + startLba + " +" + lengthLba;
        }
    }

    private final int firstTrack;
    private final List<Track> tracks;

    /**
     * Creates a TOC.
     *
     * @param firstTrack the first audio track number (usually 1)
     * @param tracks     the ordered audio tracks; copied defensively
     */
    public Toc(int firstTrack, List<Track> tracks) {
        this.firstTrack = firstTrack;
        this.tracks = (tracks == null)
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(tracks));
    }

    /** @return the first audio track number. */
    public int getFirstTrack() {
        return firstTrack;
    }

    /** @return the ordered, unmodifiable track list (never null). */
    public List<Track> getTracks() {
        return tracks;
    }

    /** @return the number of audio tracks. */
    public int trackCount() {
        return tracks.size();
    }

    /** @return true when the TOC holds no tracks. */
    public boolean isEmpty() {
        return tracks.isEmpty();
    }

    /** @return the last audio track number, or {@code firstTrack - 1} when empty. */
    public int lastTrack() {
        return tracks.isEmpty() ? firstTrack - 1 : tracks.get(tracks.size() - 1).number;
    }

    /**
     * The lead-out position in frames: the end of the last audio track. This is
     * the value the MusicBrainz discid uses for track 100 (0xAA).
     *
     * @return the lead-out LBA, or 0 when the TOC is empty
     */
    public long leadOutLba() {
        if (tracks.isEmpty()) {
            return 0L;
        }
        Track last = tracks.get(tracks.size() - 1);
        return last.startLba + last.lengthLba;
    }
}
