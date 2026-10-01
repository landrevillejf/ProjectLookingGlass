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
 * The video player's persisted preferences: master volume, an optional
 * preferred external player (blank means auto-detect), and whether to start the
 * external player full-screen. A plain Jackson bean saved by
 * {@link VideoPlayerStore}; setters clamp so a hand-edited config can never hold
 * an out-of-range value.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class VideoSettings {

    /** Default master volume, percent. */
    public static final int DEFAULT_VOLUME = 80;

    private int volume = DEFAULT_VOLUME;
    private String preferredPlayer = "";
    private boolean fullscreen = false;

    /** No-arg constructor for Jackson. */
    public VideoSettings() {
    }

    /** @return the master volume, 0..100. */
    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = Math.max(0, Math.min(100, volume));
    }

    /** @return the preferred external player, or blank for auto-detect. */
    public String getPreferredPlayer() {
        return preferredPlayer;
    }

    public void setPreferredPlayer(String preferredPlayer) {
        this.preferredPlayer = (preferredPlayer == null) ? "" : preferredPlayer.trim();
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }
}
