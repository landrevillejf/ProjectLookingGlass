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

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;

/**
 * The sound effects of one Tetris window, over Java Sound.
 *
 * <p>Two decisions carry the whole class. <b>Lazy</b>: a clip is decoded and
 * opened the first time its effect is asked for, and never at construction --
 * the game lives in the desktop's own JVM, so an eager loader would sit on
 * several megabytes of PCM for a session in which the player might never clear
 * a line, and might never open the game at all. <b>It must never throw</b>:
 * this runs on the event dispatch thread inside a game tick, and a machine
 * with no mixer (a headless JVM, a container, a desktop with the sound card
 * disabled) has to play silently rather than take the window down. Every
 * failure is therefore recorded once at {@code FINE} and turned into "no clip
 * for that effect", which {@link #play} already treats as a no-op.</p>
 *
 * <p>Not a singleton: the 3D desktop can hold two Tetris windows open, and one
 * shared mixer would let the second window's settings, and its close, act on
 * the first. Each {@code TetrisPanel} owns one instance and calls
 * {@link #shutdown()} when its window goes away, so the lines return to the
 * mixer instead of accumulating with every window the user opens.</p>
 */
public final class TetrisAudio {

    private static final Logger logger = Logger.getLogger("lg.apps.tetris");

    /** Classpath prefix of the shipped clips, beside the app's own sources. */
    private static final String SOUND_DIR =
            "/org/jdesktop/lg3d/apps/games/tetris/resources/sounds/";

    /**
     * Every effect the game plays, each with the clip it reads and the level it
     * plays at. The volumes are not decoration: the same clip serves several
     * effects (a row move and a spawn both use {@code select.wav}), so the
     * quieter uses ask for less than the one that announces the event.
     */
    public enum Effect {
        /** A one-cell slide that landed. */
        MOVE("select.wav", 0.3f),
        /** A slide that the wall or a settled cell refused. */
        BLOCKED("error.wav", 0.25f),
        /** A quarter turn. */
        ROTATE("rotate_piece.wav", 0.4f),
        /** A piece that soft-dropped one row. */
        SOFT_DROP("move_piece.wav", 0.2f),
        /** A piece that came to rest without clearing anything. */
        LOCK("piece_landed.wav", 0.5f),
        /** A row completed. */
        CLEAR_ONE("line_clear.wav", 0.6f),
        /** Two rows at once. */
        CLEAR_TWO("piece_falling.wav", 0.7f),
        /** Three rows at once. */
        CLEAR_THREE("4_lines.wav", 0.8f),
        /** Four rows at once -- the Tetris. */
        CLEAR_FOUR("4_lines.wav", 1.0f),
        /** The stack grew a level. */
        LEVEL_UP("level_up.wav", 1.0f),
        /** A new run began. */
        START("level_up.wav", 0.7f),
        /** The run ended. */
        GAME_OVER("game_over.wav", 1.0f),
        /** Pause. */
        PAUSE("cancel.wav", 1.0f),
        /** Resume. */
        RESUME("confirm.wav", 1.0f),
        /** A button the player pressed. */
        CLICK("confirm.wav", 0.5f),
        /** A settings dialog that was refused. */
        CANCEL("cancel.wav", 0.6f);

        private final String file;
        private final float volume;

        Effect(String file, float volume) {
            this.file = file;
            this.volume = volume;
        }

        /** The shipped clip this effect reads. */
        public String fileName() {
            return file;
        }
    }

    private final Map<Effect, Clip> clips = new EnumMap<>(Effect.class);
    /** Effects whose clip was tried and could not be opened: never retried. */
    private final Set<Effect> unloadable = EnumSet.noneOf(Effect.class);

    private boolean enabled = true;
    private float volume = 0.8f;

    /**
     * Plays an effect, if sound is on and the clip is loadable on this
     * machine. Safe from the EDT: {@code Clip.start()} only queues the buffer
     * with the mixer and returns.
     */
    public void play(Effect effect) {
        if (!enabled || effect == null) {
            return;
        }
        Clip clip = clips.get(effect);
        if (clip == null) {
            if (unloadable.contains(effect)) {
                return;  // already tried on this machine
            }
            clip = load(effect);
            if (clip == null) {
                unloadable.add(effect);
                return;
            }
            clips.put(effect, clip);
        }
        try {
            if (clip.isRunning()) {
                clip.stop();
            }
            clip.setFramePosition(0);
            applyGain(clip, effect.volume * volume);
            clip.start();
        } catch (RuntimeException e) {
            // A clip that dies mid-session (the mixer goes away, the line is
            // revoked) must not end the game: forget it and let the next
            // attempt reload it, or mark it unloadable if that fails too.
            logger.log(Level.FINE, "Tetris effect " + effect + " failed to play", e);
            clips.remove(effect);
        }
    }

    /** Stops everything playing now; the clips stay loaded for the next effect. */
    public void silence() {
        for (Clip clip : clips.values()) {
            try {
                if (clip.isRunning()) {
                    clip.stop();
                }
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "Tetris effect could not be stopped", e);
            }
        }
    }

    /**
     * Releases every line the mixer gave this instance. Called when the window
     * closes; after it the instance is dead, and a new window makes a new one.
     */
    public void shutdown() {
        silence();
        for (Clip clip : clips.values()) {
            try {
                clip.close();
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "Tetris clip could not be closed", e);
            }
        }
        clips.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Off stops new effects; what is already playing is silenced at once. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            silence();
        }
    }

    public float getVolume() {
        return volume;
    }

    /** @param volume 0..1, clamped; scales every effect's own level. */
    public void setVolume(float volume) {
        this.volume = Math.max(0f, Math.min(1f, volume));
    }

    /**
     * Decodes one clip. Returns {@code null} -- never an exception -- when the
     * resource is absent, the format is unreadable, or the machine has no line
     * to play it on.
     */
    private Clip load(Effect effect) {
        try (InputStream raw = TetrisAudio.class.getResourceAsStream(SOUND_DIR + effect.fileName())) {
            if (raw == null) {
                logger.log(Level.FINE, "Tetris sound missing from classpath: " + effect.fileName());
                return null;
            }
            // getResourceAsStream from a jar does not support mark/reset, which
            // AudioSystem.getAudioInputStream requires: wrap it so the codec
            // can rewind during format probing.
            BufferedInputStream in = new BufferedInputStream(raw);
            AudioInputStream stream = AudioSystem.getAudioInputStream(in);
            Clip clip = AudioSystem.getClip();
            clip.open(stream);
            return clip;
        } catch (Exception e) {
            // LineUnavailableException, unsupported audio format, a closed
            // stream: the game continues silently.
            logger.log(Level.FINE, "Tetris sound " + effect.fileName() + " could not be loaded", e);
            return null;
        }
    }

    /**
     * Sets one clip's gain. Mixers are not required to offer the control; a
     * clip without it simply plays at its own level, which is why the volume
     * is applied here and not baked into the buffers. Two effects share a clip
     * at different levels (a slide and a refused slide both read
     * {@code select.wav}), so the control is written on every play -- including
     * at silence, which has to mute the clip rather than leave the level the
     * previous effect set on it.
     */
    private void applyGain(Clip clip, float linear) {
        if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
        float min = gain.getMinimum();
        float max = gain.getMaximum();
        if (linear <= 0f) {
            // Silence is not a decibel the log can express: the control's own
            // floor (-80 dB on most drivers) is as quiet as a clip gets.
            gain.setValue(min);
            return;
        }
        float db = (float) (20.0 * Math.log10(linear));
        gain.setValue(Math.max(min, Math.min(max, db)));
    }
}
