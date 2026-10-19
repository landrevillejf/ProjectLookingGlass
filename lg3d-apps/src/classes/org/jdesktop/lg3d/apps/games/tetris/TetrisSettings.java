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
package org.jdesktop.lg3d.apps.games.tetris;

import java.util.prefs.Preferences;
import org.jdesktop.lg3d.apps.games.tetris.audio.TetrisAudio;

/**
 * The player's own Tetris preferences, kept in {@code ~/.lg3d} through the
 * JDK's {@link Preferences} node (the same store the desktop's other apps
 * use), so a run of the game is remembered between sessions and between the
 * 2D and 3D surfaces.
 *
 * <p>Three settings only: whether effects play, how loud they are, and how
 * fast a fresh stack falls. Each is clamped on read as well as on write,
 * because a preference file is edited by hand sometimes and a negative
 * fall delay is a game that spins the piece at full CPU.</p>
 */
public final class TetrisSettings {

    /** Milliseconds between falls at level 1, and the slider's two ends. */
    public static final int MIN_START_DELAY_MS = 100;
    public static final int MAX_START_DELAY_MS = 1000;
    public static final int DEFAULT_START_DELAY_MS = 500;

    private static final String KEY_SOUND = "soundEnabled";
    private static final String KEY_VOLUME = "volumePercent";
    private static final String KEY_DELAY = "startDelayMs";

    private final Preferences prefs;

    private boolean soundEnabled;
    private int volumePercent;
    private int startDelayMs;

    public TetrisSettings() {
        this(Preferences.userNodeForPackage(TetrisSettings.class));
    }

    /** The seam a test uses to stay out of the real user's preference store. */
    TetrisSettings(Preferences prefs) {
        this.prefs = prefs;
        soundEnabled = prefs.getBoolean(KEY_SOUND, true);
        volumePercent = clamp(prefs.getInt(KEY_VOLUME, 80), 0, 100);
        startDelayMs = clamp(prefs.getInt(KEY_DELAY, DEFAULT_START_DELAY_MS),
                MIN_START_DELAY_MS, MAX_START_DELAY_MS);
    }

    public boolean isSoundEnabled() {
        return soundEnabled;
    }

    public void setSoundEnabled(boolean soundEnabled) {
        this.soundEnabled = soundEnabled;
    }

    /** The master level as 0..100, the unit a slider reads. */
    public int getVolumePercent() {
        return volumePercent;
    }

    public void setVolumePercent(int volumePercent) {
        this.volumePercent = clamp(volumePercent, 0, 100);
    }

    public float getVolume() {
        return volumePercent / 100f;
    }

    /** Milliseconds between falls at level 1; every level is faster than this. */
    public int getStartDelayMs() {
        return startDelayMs;
    }

    public void setStartDelayMs(int startDelayMs) {
        this.startDelayMs = clamp(startDelayMs, MIN_START_DELAY_MS, MAX_START_DELAY_MS);
    }

    /** Pushes the sound choices onto the live mixer of one window. */
    public void applyTo(TetrisAudio audio) {
        audio.setEnabled(soundEnabled);
        audio.setVolume(getVolume());
    }

    /**
     * Writes the three settings. A failure to persist -- a read-only home, a
     * security manager -- is not the player's problem to see: the choices still
     * hold for this window, they just do not survive it.
     */
    public void save() {
        try {
            prefs.putBoolean(KEY_SOUND, soundEnabled);
            prefs.putInt(KEY_VOLUME, volumePercent);
            prefs.putInt(KEY_DELAY, startDelayMs);
            prefs.flush();
        } catch (RuntimeException | java.util.prefs.BackingStoreException e) {
            // Deliberately quiet: closing a game window must not fail.
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
