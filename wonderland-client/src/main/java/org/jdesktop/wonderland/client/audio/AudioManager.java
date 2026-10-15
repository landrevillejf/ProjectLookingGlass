/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client P4.1: the owner a live shell hangs the audio on.
 *
 * Same shape as WorldPVS for the culler: the kernel is a function, the world
 * needs a thing that remembers. Sources register as cells attach and move as
 * cell transforms move (the 2006 AudioNode lived on exactly that seam); one
 * listener is the camera's ear; and update() is the per-frame call that turns
 * every registered source through SpatialAudio and pushes each verdict to the
 * backend -- starting the sounds that just became audible, posing the ones
 * that moved, stopping the ones that walked out of earshot.
 *
 * What it deliberately does NOT do: own threads, decode bytes, or guess a
 * frame clock. A shell that already runs a render loop calls update() from
 * it; the manager is arithmetic and bookkeeping, so the whole class runs and
 * is fully tested headless against a recording fake backend. The real
 * mixer -- JavaSound today, JOAL if the roadmap's audio ambitions grow --
 * lives behind AudioBackend and is the only half that needs a sound card.
 */
package org.jdesktop.wonderland.client.audio;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The lifecycle owner around {@link SpatialAudio} and one {@link AudioBackend}. */
public final class AudioManager {

    private final AudioBackend backend;
    private final Map<String, SpatialAudio.Source> sources = new LinkedHashMap<>();
    private final Set<String> playing = new LinkedHashSet<>();
    private SpatialAudio.Listener listener = new SpatialAudio.Listener(0, 0, 0, 0);

    public AudioManager(AudioBackend backend) {
        this.backend = Objects.requireNonNull(backend, "a manager without a mixer hears nothing");
    }

    /** Point the ear. The camera rig (P3.1) is this call's intended future caller. */
    public void setListener(SpatialAudio.Listener listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    /**
     * A cell attached with a sound. Re-registering a name REPLACES it -- the
     * same promise WorldPVS made for moved cells, for the same reason: a
     * teleport must not leave an audible ghost behind.
     */
    public void register(String id, SpatialAudio.Source source) {
        Objects.requireNonNull(id, "an audible source must be named");
        sources.put(id, Objects.requireNonNull(source));
    }

    /** A cell detached. An unknown name is a no-op, the way a frame sees it. */
    public void unregister(String id) {
        if (sources.remove(id) != null && playing.remove(id)) {
            backend.stop(id);
        }
    }

    /** How many sounds the world currently holds, audible this frame or not. */
    public int size() {
        return sources.size();
    }

    /**
     * One frame's verdict for every source: compute the mix, start what just
     * became audible, pose what is playing, stop what fell silent. Returns
     * the number of audible sources so a shell can log or skip work without
     * re-deriving the answer.
     */
    public int update() {
        int audible = 0;
        for (Map.Entry<String, SpatialAudio.Source> entry : sources.entrySet()) {
            String id = entry.getKey();
            SpatialAudio.Mix mix = SpatialAudio.mix(listener, entry.getValue());
            if (mix.audible()) {
                audible++;
                if (playing.add(id)) {
                    backend.start(id);
                }
                backend.pose(id, mix.gain(), mix.pan());
            } else if (playing.remove(id)) {
                backend.stop(id);
            }
        }
        return audible;
    }

    /** Silence every playing sound but keep the registrations (world pause). */
    public void silence() {
        for (String id : playing) {
            backend.stop(id);
        }
        playing.clear();
    }

    /** Detach everything; the way a world unload or an app quit leaves it. */
    public void clear() {
        silence();
        sources.clear();
    }
}
