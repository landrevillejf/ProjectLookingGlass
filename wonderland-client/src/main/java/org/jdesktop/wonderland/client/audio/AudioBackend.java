/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-client P4.1: the seam between the spatial maths and a mixer.
 *
 * The kernel decides what a frame means (gain, pan, audible); something else
 * has to actually move air. This interface is that something else, drawn so
 * the manager can be tested against a recording fake in CI and the real
 * JavaSound implementation can be exercised only where a machine has an
 * output device. The vocabulary is deliberately three verbs deep -- start,
 * stop, pose -- because every backend from javax.sound.Clip to a future JOAL
 * source can express exactly these, and nothing in the kernel may assume more.
 */
package org.jdesktop.wonderland.client.audio;

/** The mixer a {@link AudioManager} pushes its per-frame decisions to. */
public interface AudioBackend {

    /**
     * Begin playing a sound under this id; an already-started id is left
     * alone (re-triggering is a stop/start by the host, never a surprise).
     * What the id sounds like is the backend's own bookkeeping -- a host
     * hands a JavaSound backend its bytes before the world ever registers
     * the cell -- because the manager only ever knows the id the way a cell
     * name is known: a string the world chose.
     */
    void start(String id);

    /** Update one playing sound's live gain/pan; an unknown id is a no-op. */
    void pose(String id, float gain, float pan);

    /** Silence one sound; stopping an unknown or finished id is a no-op. */
    void stop(String id);

    /** Silence everything; called when the world detaches or the app quits. */
    void stopAll();
}
