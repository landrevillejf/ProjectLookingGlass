/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7a (videoplayer): the playback seam the Java 3D /
 * headless core reaches through, so the transport state machine is provable in
 * CI while the real sink lives in the 2D desktop JVM.
 *
 * The 2006 videoplayer drove Java Media Framework (JMF), a dead stack with no
 * JDK 21 build. M7a rebuilds it on OpenJFX Media (javafx.scene.media), the
 * engine already in-tree behind the Web Browser. But JavaFX may only initialise
 * in the 2D desktop JVM / the browser's child process (never the Java 3D
 * process), and a headless test JVM has no toolkit at all — so the wonderland
 * module owns only this interface and the transport policy that calls it. The
 * concrete JavaFX surface is the lg3d-apps JfxMediaSurface, wired through the
 * VideoPlayerPlugin surface-factory seam. Every transport decision is therefore
 * testable against a recording stub, exactly the M6c1 FrameMounter split.
 */
package org.jdesktop.wonderland.modules.videoplayer;

/**
 * Performs the actual media playback a {@link VideoTransport} has decided on.
 * Implementations wrap a real player (the OpenJFX {@code MediaPlayer}); the
 * transport says nothing about HOW — only WHEN.
 */
public interface MediaSurface {

    /**
     * Load and prepare a media resource. Called before any play.
     *
     * @param mediaUri      the resource locator (a file path or URL)
     * @param durationHintMs the playlist's known duration in ms, or {@code 0}
     *                      when the duration is not yet known
     */
    void open(String mediaUri, long durationHintMs);

    /** Start or resume playback. */
    void play();

    /** Suspend playback, keeping the current position. */
    void pause();

    /** Halt playback and return to the start. */
    void stop();

    /**
     * Jump to an absolute position.
     *
     * @param positionMs the already-clamped target, always within
     *                   {@code [0, transport.duration()] }
     */
    void seekTo(long positionMs);

    /**
     * Set the playback volume.
     *
     * @param volume the already-clamped linear gain, always within
     *               {@code [0.0, 1.0]}
     */
    void setVolume(double volume);

    /** Release every native/media resource held by this surface. */
    void release();
}
