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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.prefs.Preferences;
import org.jdesktop.lg3d.apps.games.tetris.audio.TetrisAudio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TetrisSettings} over a <em>unique, throwaway</em> preferences node,
 * removed in {@link #tearDown()} -- the test JVM does not isolate
 * {@code java.util.prefs}, so the store the game reads is confined to a
 * test-only path and never the production package node (the
 * {@code PrefsTaskStoreTest} precedent in lg3d-core).
 */
class TetrisSettingsTest {

    private Preferences node;

    @BeforeEach
    void setUp() {
        node = Preferences.userRoot().node("/lg3d/tetris-test/" + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() throws Exception {
        node.removeNode();
        Preferences.userRoot().flush();
    }

    @Test
    @DisplayName("an untouched player gets sound on, at four fifths, at the classic speed")
    void defaultsArePlayable() {
        TetrisSettings settings = new TetrisSettings(node);
        assertTrue(settings.isSoundEnabled());
        assertEquals(80, settings.getVolumePercent());
        assertEquals(0.8f, settings.getVolume(), 0.0001f);
        assertEquals(TetrisSettings.DEFAULT_START_DELAY_MS, settings.getStartDelayMs());
    }

    @Test
    @DisplayName("the three choices survive a save and a reload")
    void choicesRoundTrip() {
        TetrisSettings settings = new TetrisSettings(node);
        settings.setSoundEnabled(false);
        settings.setVolumePercent(35);
        settings.setStartDelayMs(250);
        settings.save();

        TetrisSettings reloaded = new TetrisSettings(node);
        assertFalse(reloaded.isSoundEnabled());
        assertEquals(35, reloaded.getVolumePercent());
        assertEquals(250, reloaded.getStartDelayMs());
    }

    @Test
    @DisplayName("a hand-edited store cannot ask for a broken game")
    void junkInTheStoreIsClampedOnRead() {
        node.putBoolean("soundEnabled", true);
        node.putInt("volumePercent", 5_000);
        node.putInt("startDelayMs", -7);
        TetrisSettings settings = new TetrisSettings(node);
        assertEquals(100, settings.getVolumePercent(), "a slider cannot read past its end");
        assertEquals(TetrisSettings.MIN_START_DELAY_MS, settings.getStartDelayMs(),
                "a negative fall delay is a timer that spins the event dispatch thread");

        node.putInt("volumePercent", -40);
        node.putInt("startDelayMs", 900_000);
        TetrisSettings other = new TetrisSettings(node);
        assertEquals(0, other.getVolumePercent());
        assertEquals(TetrisSettings.MAX_START_DELAY_MS, other.getStartDelayMs());
    }

    @Test
    @DisplayName("writing out of range clamps at once, not only on the next read")
    void settersClamp() {
        TetrisSettings settings = new TetrisSettings(node);
        settings.setVolumePercent(-1);
        assertEquals(0, settings.getVolumePercent());
        assertEquals(0f, settings.getVolume(), 0f);
        settings.setVolumePercent(101);
        assertEquals(100, settings.getVolumePercent());
        settings.setStartDelayMs(1);
        assertEquals(TetrisSettings.MIN_START_DELAY_MS, settings.getStartDelayMs());
    }

    @Test
    @DisplayName("applyTo pushes both sound choices onto a live mixer")
    void applyToReachesTheAudio() {
        TetrisSettings settings = new TetrisSettings(node);
        TetrisAudio audio = new TetrisAudio();
        settings.setSoundEnabled(false);
        settings.setVolumePercent(0);
        settings.applyTo(audio);
        assertFalse(audio.isEnabled());
        assertEquals(0f, audio.getVolume(), 0f);

        settings.setSoundEnabled(true);
        settings.setVolumePercent(60);
        settings.applyTo(audio);
        assertTrue(audio.isEnabled());
        assertEquals(0.6f, audio.getVolume(), 0.0001f);
    }

    @Test
    @DisplayName("a save that the store refuses is nobody's problem but the session's")
    void saveNeverThrows() {
        TetrisSettings settings = new TetrisSettings(node);
        settings.setVolumePercent(42);
        settings.save();
        assertEquals(42, new TetrisSettings(node).getVolumePercent());
    }
}
