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
 * The audio player's persisted preferences: master volume, an optional
 * preferred external player (blank means auto-detect), and the repeat / shuffle
 * toggles. A plain Jackson bean saved by {@link AudioPlayerStore}; setters clamp
 * so a hand-edited config can never hold an out-of-range value.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlayerSettings {

    /** Default master volume, percent. */
    public static final int DEFAULT_VOLUME = 80;

    private int volume = DEFAULT_VOLUME;
    private String preferredPlayer = "";
    private boolean repeat = false;
    private boolean shuffle = false;

    /** No-arg constructor for Jackson. */
    public PlayerSettings() {
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

    public boolean isRepeat() {
        return repeat;
    }

    public void setRepeat(boolean repeat) {
        this.repeat = repeat;
    }

    public boolean isShuffle() {
        return shuffle;
    }

    public void setShuffle(boolean shuffle) {
        this.shuffle = shuffle;
    }
}
