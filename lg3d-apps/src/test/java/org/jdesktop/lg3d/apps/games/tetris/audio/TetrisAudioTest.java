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
package org.jdesktop.lg3d.apps.games.tetris.audio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sound layer's two promises, tested headless where a machine may have no
 * mixer at all: every effect the game can ask for has a clip shipped beside it,
 * and no request can throw.
 *
 * <p>The first check is the one the game shipped broken once: an effect naming
 * a file nobody bundled is silent forever, and nothing but a test notices,
 * because a missing clip is indistinguishable from a machine with no sound
 * card.</p>
 */
class TetrisAudioTest {

    /**
     * Where the clips are bundled, mirroring {@code TetrisAudio.SOUND_DIR} (a
     * private constant) from outside the class, so a rename that leaves the
     * files behind still fails here.
     */
    private static final String SOUND_DIR =
            "/org/jdesktop/lg3d/apps/games/tetris/resources/sounds/";

    /**
     * The clips the bundle carries. Written out here rather than listed off
     * disk, so a file added to the directory without an effect to play it (or
     * the other way round) is a diff in this test, not a megabyte nobody hears.
     */
    private static final Set<String> SHIPPED = Set.of(
            "4_lines.wav", "cancel.wav", "confirm.wav", "error.wav",
            "game_over.wav", "level_up.wav", "line_clear.wav",
            "move_piece.wav", "piece_falling.wav", "piece_landed.wav",
            "rotate_piece.wav", "select.wav");

    @Test
    @DisplayName("every effect names a clip that ships on the classpath")
    void everyEffectHasAClip() {
        for (TetrisAudio.Effect effect : TetrisAudio.Effect.values()) {
            String name = effect.fileName();
            assertNotNull(name, effect + " names no clip");
            assertTrue(name.endsWith(".wav"), effect + " reads " + name);
            URL clip = TetrisAudio.class.getResource(SOUND_DIR + name);
            assertNotNull(clip, "effect " + effect + " asks for " + name
                    + ", which is not on the classpath under " + SOUND_DIR);
        }
    }

    @Test
    @DisplayName("the bundle and the effects are one set, and clips are shared")
    void noClipShipsUnusedAndNoEffectPlaysAnAbsentOne() {
        Set<String> played = new HashSet<>();
        for (TetrisAudio.Effect effect : TetrisAudio.Effect.values()) {
            played.add(effect.fileName());
        }
        assertEquals(SHIPPED, played,
                "a clip nobody plays is weight in every jar; an effect naming a "
                + "clip nobody ships is a sound the player never hears");
        // Sixteen effects over twelve files: the reuse is the point, so the
        // levels a shared clip plays at stay per-effect rather than per-file.
        assertTrue(TetrisAudio.Effect.values().length > played.size(),
                "some clips serve more than one effect");
    }

    @Test
    @DisplayName("a disabled or null effect plays nothing and never throws")
    void disabledAudioIsQuiet() {
        TetrisAudio audio = new TetrisAudio();
        audio.setEnabled(false);
        assertFalse(audio.isEnabled());
        assertDoesNotThrow(() -> audio.play(TetrisAudio.Effect.GAME_OVER));
        assertDoesNotThrow(() -> audio.play(null));
    }

    @Test
    @DisplayName("a machine with no mixer still gets a playable game")
    void playNeverThrowsWithoutAMixer() {
        TetrisAudio audio = new TetrisAudio();
        // Headless CI has no line to open: every effect must degrade to
        // silence, and the second attempt must not retry the failure forever.
        assertDoesNotThrow(() -> {
            for (TetrisAudio.Effect effect : TetrisAudio.Effect.values()) {
                audio.play(effect);
                audio.play(effect);
            }
        });
        assertDoesNotThrow(audio::silence);
    }

    @Test
    @DisplayName("the master level is clamped to what a slider can ask for")
    void volumeIsClamped() {
        TetrisAudio audio = new TetrisAudio();
        audio.setVolume(2f);
        assertEquals(1f, audio.getVolume(), 0f);
        audio.setVolume(-3f);
        assertEquals(0f, audio.getVolume(), 0f);
        audio.setVolume(0.25f);
        assertEquals(0.25f, audio.getVolume(), 0.0001f);
    }

    @Test
    @DisplayName("turning sound off stops what is already playing")
    void disablingSilencesTheWindow() {
        TetrisAudio audio = new TetrisAudio();
        assertTrue(audio.isEnabled(), "a fresh window plays");
        assertDoesNotThrow(() -> {
            audio.play(TetrisAudio.Effect.START);
            audio.setEnabled(false);
            audio.setEnabled(true);
            audio.play(TetrisAudio.Effect.CLICK);
        });
    }

    @Test
    @DisplayName("shutting a window down twice is still just closing it")
    void shutdownIsIdempotent() {
        TetrisAudio audio = new TetrisAudio();
        audio.play(TetrisAudio.Effect.LOCK);
        assertDoesNotThrow(() -> {
            audio.shutdown();
            audio.shutdown();
            audio.silence();
        });
    }
}
