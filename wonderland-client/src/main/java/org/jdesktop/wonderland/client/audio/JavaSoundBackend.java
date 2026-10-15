/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client P4.1: the mixer that actually moves air.
 *
 * javax.sound.sampled is the one audio API that ships in the JDK, needs no
 * natives to compile, and (unlike JOAL) needs no module rules -- which is why
 * the roadmap's first backend is JavaSound, not OpenAL: the same reasoning
 * that put G.711 over Java Sound in the softphone (M7d) applies to the world.
 * A future JOAL backend can drop behind the same AudioBackend seam with real
 * EFX; nothing in the kernel would notice, which was the point of the seam.
 *
 * The honesty clause, load-bearing: THIS CLASS'S AIR IS NOT VERIFIED BY CI. Its
 * pure helpers (gainToDb, clamp) and its whole verb contract -- open, start, loop,
 * pose, stop, close, the re-load that replaces -- are now unit-tested headless
 * through the {@link ClipSource} seam below with a fake {@link Clip}, so a
 * headless runner proves the mixer logic without a sound card. What a fake cannot
 * prove is that a real Clip moves real air: "the pond splashes when you walk by
 * it" still belongs to a machine with speakers, not to a green suite.
 *
 * Failure policy: the mixer degrades to silence, never to an exception. The
 * audio path must not kill the shell any more than the crash reporter may;
 * a device that refuses is a world without sound, not a world without a view.
 */
package org.jdesktop.wonderland.client.audio;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;

/** The JavaSound {@link AudioBackend}: one {@link Clip} per registered id. */
public final class JavaSoundBackend implements AudioBackend {

    /** The floor JavaSound mixers accept, and the number -inf gain means here. */
    static final float MIN_DB = -80f;

    /**
     * A source of fresh {@link Clip}s. This is the seam that makes the mixer
     * testable without a sound card: production hands it {@link AudioSystem#getClip()}
     * -- exactly the call the no-arg constructor uses today -- while a headless
     * test injects a fake so the clip-present code path (open, start, loop, the
     * per-control pose, stop and close) runs identically on CI, where no device
     * exists. It is declared to throw the checked exceptions the real factory
     * throws, so a test can also reproduce "the device refused the line."
     */
    @FunctionalInterface
    interface ClipSource {
        Clip get() throws LineUnavailableException, IOException;
    }

    private final Map<String, Clip> clips = new LinkedHashMap<>();
    private final Map<String, Boolean> loopFlags = new LinkedHashMap<>();
    private final ClipSource clipSource;

    /** The production mixer: every clip comes from {@link AudioSystem#getClip()}. */
    public JavaSoundBackend() {
        this(AudioSystem::getClip);
    }

    /** The testable mixer: clips come from the injected {@code clipSource}. */
    JavaSoundBackend(ClipSource clipSource) {
        this.clipSource = clipSource;
    }

    /**
     * Hand the mixer the bytes one id sounds like, before any world registers
     * the cell. A stream the mixer cannot open is kept unloaded -- start()
     * will then stay silent for that id, the documented degradation. A null
     * stream is a programmer error, refused loudly; a refusal by the device
     * is a runtime fact, absorbed silently.
     */
    public void load(String id, AudioInputStream stream, boolean loop) {
        Objects.requireNonNull(stream, "load wants the bytes, not a promise of them");
        try {
            Clip clip = clipSource.get();
            clip.open(stream);
            Clip previous = clips.put(id, clip);
            loopFlags.put(id, loop);
            if (previous != null) {
                previous.close(); // a re-load replaces, never leaks the line
            }
        } catch (LineUnavailableException | IOException | IllegalArgumentException gone) {
            // no device, bad format, refused line: silence about this id only
        }
    }

    /** Whether the bytes for this id ever reached a clip. Test-visible truth. */
    public boolean isLoaded(String id) {
        return clips.containsKey(id);
    }

    @Override
    public void start(String id) {
        Clip clip = clips.get(id);
        if (clip == null || clip.isRunning()) {
            return;
        }
        clip.setFramePosition(0);
        if (Boolean.TRUE.equals(loopFlags.get(id))) {
            clip.loop(Clip.LOOP_CONTINUOUSLY);
        } else {
            clip.start();
        }
    }

    @Override
    public void pose(String id, float gain, float pan) {
        Clip clip = clips.get(id);
        if (clip == null) {
            return;
        }
        setControl(clip, FloatControl.Type.MASTER_GAIN, gainToDb(gain));
        setControl(clip, FloatControl.Type.PAN, clamp(pan, -1f, 1f));
    }

    @Override
    public void stop(String id) {
        Clip clip = clips.get(id);
        if (clip != null) {
            clip.stop();
        }
    }

    @Override
    public void stopAll() {
        clips.values().forEach(Clip::stop);
    }

    /** Release every clip's line; the way a world unload should leave them. */
    public void close() {
        clips.values().forEach(clip -> {
            clip.stop();
            clip.close();
        });
        clips.clear();
        loopFlags.clear();
    }

    /** Linear gain to decibels: the curve every JavaSound MASTER_GAIN expects. */
    static float gainToDb(float gain) {
        if (gain <= 0f) {
            return MIN_DB;
        }
        double db = 20.0 * Math.log10(gain);
        return (float) Math.max(MIN_DB, Math.min(0.0, db));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** A control a particular mixer lacks is skipped, not fatal (see policy above). */
    private static void setControl(Clip clip, FloatControl.Type type, float value) {
        if (!clip.isControlSupported(type)) {
            return;
        }
        FloatControl control = (FloatControl) clip.getControl(type);
        control.setValue(clamp(value, control.getMinimum(), control.getMaximum()));
    }
}
