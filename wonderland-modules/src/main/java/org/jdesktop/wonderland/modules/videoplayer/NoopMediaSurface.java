/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7a (videoplayer): the silent sink.
 *
 * The headless default the VideoPlayerPlugin builds a transport with when no
 * live MediaSurface factory has been installed — so the transport's state
 * machine still runs (and its policy stays exercisable) in any JVM, while the
 * desktop installs the OpenJFX-backed surface at boot. Not a test double: it
 * ships, it is just silent.
 */
package org.jdesktop.wonderland.modules.videoplayer;

/** A {@link MediaSurface} that discards every command. */
final class NoopMediaSurface implements MediaSurface {

    static final NoopMediaSurface INSTANCE = new NoopMediaSurface();

    @Override
    public void open(String mediaUri, long durationHintMs) {
    }

    @Override
    public void play() {
    }

    @Override
    public void pause() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void seekTo(long positionMs) {
    }

    @Override
    public void setVolume(double volume) {
    }

    @Override
    public void release() {
    }
}
