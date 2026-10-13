/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7a (videoplayer): the transport state machine — the pure
 * decision layer of the rebuilt videoplayer.
 *
 * The 2006 module drove JMF's Player/GUI; this is the modern equivalent's
 * policy, rebuilt on OpenJFX Media behind the MediaSurface seam. The class owns
 * the legal transitions, the clamped position/volume, and the reached-the-end
 * walk; it forwards the HOW to the injected MediaSurface, never touching a
 * JavaFX type. Every branch here is therefore headless-tested with a recording
 * stub (the M6c1 FrameMounter precedent): a test drives load/play/pause/stop/
 * seek/setVolume/tick and asserts both the resulting state AND the exact calls
 * that reached the surface.
 */
package org.jdesktop.wonderland.modules.videoplayer;

/**
 * The playback state machine of one media item. All state transitions are
 * guarded so an illegal request (a pause while stopped, a seek before load)
 * is a no-op rather than an error, and the position/volume are clamped before
 * they reach the {@link MediaSurface}.
 */
public final class VideoTransport {

    private final MediaSurface surface;

    private TransportState state = TransportState.IDLE;
    private String mediaUri;
    private long durationMs;
    private long positionMs;
    private double volume = 1.0;

    /**
     * @param surface the playback sink; must not be null (the headless default
     *                is {@link NoopMediaSurface})
     */
    public VideoTransport(MediaSurface surface) {
        if (surface == null) {
            throw new IllegalArgumentException("surface must not be null");
        }
        this.surface = surface;
    }

    /**
     * Load a media resource, resetting the position. A null/empty uri is
     * refused (the state and surface are untouched) — a playlist item with no
     * file is a data defect, not a playback command.
     *
     * @param uri         the resource locator
     * @param durationMs  the known duration, or 0 when still unknown; never
     *                    negative
     * @return true when the resource was accepted and opened
     */
    public boolean load(String uri, long durationMs) {
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        // A playing item must be halted before its media is swapped, so the
        // surface never holds two live resources.
        if (state == TransportState.PLAYING || state == TransportState.PAUSED) {
            surface.stop();
        }
        this.mediaUri = uri;
        this.durationMs = Math.max(0, durationMs);
        this.positionMs = 0;
        this.state = TransportState.IDLE;
        surface.open(uri, this.durationMs);
        return true;
    }

    /**
     * Start or resume. Legal from IDLE / PAUSED / STOPPED; from ENDED it
     * restarts the same item at zero. Playing while already PLAYING is a no-op.
     */
    public void play() {
        if (state == TransportState.PLAYING) {
            return;
        }
        if (mediaUri == null) {
            return; // nothing loaded — play is meaningless
        }
        if (state == TransportState.ENDED || state == TransportState.STOPPED) {
            positionMs = 0;
        }
        state = TransportState.PLAYING;
        surface.play();
    }

    /**
     * Suspend at the current position. Only meaningful while PLAYING; every
     * other state ignores it.
     */
    public void pause() {
        if (state != TransportState.PLAYING) {
            return;
        }
        state = TransportState.PAUSED;
        surface.pause();
    }

    /**
     * Halt and rewind to zero. Legal from PLAYING / PAUSED / ENDED; a stop on
     * an already-stopped or unloaded transport is a no-op.
     */
    public void stop() {
        if (state != TransportState.PLAYING
                && state != TransportState.PAUSED
                && state != TransportState.ENDED) {
            return;
        }
        state = TransportState.STOPPED;
        positionMs = 0;
        surface.stop();
    }

    /**
     * Jump to an absolute position, clamped to {@code [0, duration]}. Legal in
     * any loaded state; it never changes the state (seeking a paused clip keeps
     * it paused). A transport with nothing loaded ignores it.
     *
     * @param targetMs the desired position; out-of-range values clamp
     */
    public void seek(long targetMs) {
        if (mediaUri == null) {
            return;
        }
        long clamped = clamp(targetMs);
        this.positionMs = clamped;
        surface.seekTo(clamped);
    }

    /**
     * Set the linear gain, clamped to {@code [0.0, 1.0]}.
     *
     * @param volume the desired volume; NaN and out-of-range values clamp
     */
    public void setVolume(double volume) {
        double clamped = Double.isNaN(volume) ? 0.0 : Math.max(0.0, Math.min(1.0, volume));
        this.volume = clamped;
        surface.setVolume(clamped);
    }

    /**
     * Advance the clock while playing — the pure stand-in for the surface's
     * own time pulse. When the position reaches the duration the transport
     * walks to ENDED and halts the surface once. A paused/stopped transport
     * does not advance.
     *
     * @param deltaMs  elapsed wall time since the last tick; negative is 0
     */
    public void tick(long deltaMs) {
        if (state != TransportState.PLAYING) {
            return;
        }
        positionMs += Math.max(0, deltaMs);
        if (durationMs > 0 && positionMs >= durationMs) {
            positionMs = durationMs;
            state = TransportState.ENDED;
            surface.stop();
        }
    }

    /** Release the underlying surface. After dispose the transport is IDLE. */
    public void release() {
        surface.release();
        state = TransportState.IDLE;
        positionMs = 0;
    }

    /** The current lifecycle state. */
    public TransportState state() {
        return state;
    }

    /** The loaded resource locator, or null before the first successful load. */
    public String mediaUri() {
        return mediaUri;
    }

    /** The known duration in ms (0 until discovered). */
    public long duration() {
        return durationMs;
    }

    /** The current position in ms, always within {@code [0, duration]}. */
    public long position() {
        return positionMs;
    }

    /** The current linear gain in {@code [0.0, 1.0]}. */
    public double volume() {
        return volume;
    }

    /** Whether media is advancing. */
    public boolean isPlaying() {
        return state == TransportState.PLAYING;
    }

    private long clamp(long target) {
        if (target < 0) {
            return 0;
        }
        return durationMs > 0 ? Math.min(target, durationMs) : target;
    }
}
