/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7a (videoplayer): the module's WonderlandPlugin provider
 * plus the surface-factory seam, and the JMF → OpenJFX replacement anchor.
 *
 * The 2006 videoplayer module was built on JMF (javax.media), which has no
 * JDK 21 build and is not recoverable — the modern replacement is OpenJFX
 * Media, already in-tree behind the Web Browser. JavaFX may only initialise in
 * the 2D desktop JVM, so this module stays pure: it owns the transport policy
 * (VideoTransport, Playlist) and reaches the real player through this static
 * factory slot — the same installer-seam idiom M6b used for the avatar
 * renderer, which likewise cannot be imported from here without a cycle. The
 * lg3d-apps boot (the 2D JVM) installs the JavaFX-backed factory; a headless
 * JVM sees none and the transport runs on the shipped NoopMediaSurface, so the
 * playlist walk (including the end-of-item auto-advance) is CI-provable.
 */
package org.jdesktop.wonderland.modules.videoplayer;

import org.jdesktop.wonderland.common.modules.ModuleInfo;
import org.jdesktop.wonderland.modules.api.PluginContext;
import org.jdesktop.wonderland.modules.api.WonderlandPlugin;

/** The videoplayer feature module. */
public final class VideoPlayerPlugin implements WonderlandPlugin {

    /** The module name this plugin is registered under. */
    public static final String MODULE_NAME = "videoplayer";

    /**
     * Builds the playback sink for one player. The live implementation wraps
     * an OpenJFX {@code MediaPlayer}; installed from the 2D desktop JVM only.
     */
    @FunctionalInterface
    public interface MediaSurfaceFactory {
        MediaSurface create();
    }

    private static volatile MediaSurfaceFactory factory;

    /**
     * Install the live surface factory. Idempotent by last-writer-wins, like
     * the M6b installer slot.
     *
     * @param surfaceFactory the factory, or null to uninstall (test isolation)
     */
    public static void installSurfaceFactory(MediaSurfaceFactory surfaceFactory) {
        factory = surfaceFactory;
    }

    /** The currently-installed factory, or null in a headless JVM. */
    public static MediaSurfaceFactory surfaceFactory() {
        return factory;
    }

    private final ModuleInfo info =
            new ModuleInfo(MODULE_NAME, 1, 0, 0, "Video playback (OpenJFX Media)");

    private Playlist playlist;
    private VideoTransport transport;

    @Override
    public ModuleInfo getModuleInfo() {
        return info;
    }

    @Override
    public void initialize(PluginContext context) {
        if (playlist != null) {
            return; // idempotent, like every M4 module
        }
        MediaSurface surface = factory == null
                ? NoopMediaSurface.INSTANCE
                : factory.create();
        playlist = new Playlist();
        transport = new VideoTransport(surface);
    }

    @Override
    public void dispose() {
        if (transport != null) {
            transport.release();
        }
        transport = null;
        playlist = null;
    }

    /** The live playlist, or {@code null} before initialize()/after dispose(). */
    public Playlist getPlaylist() {
        return playlist;
    }

    /** The live transport, or {@code null} before initialize()/after dispose(). */
    public VideoTransport getTransport() {
        return transport;
    }

    /**
     * Load and start the current playlist item.
     *
     * @return true when the playlist has a current item and it was loaded
     */
    public boolean playCurrent() {
        Playlist.Item item = playlist == null ? null : playlist.current();
        if (item == null) {
            return false;
        }
        transport.load(item.uri(), item.durationMs());
        transport.play();
        return true;
    }

    /**
     * Advance the playlist clock and auto-advance at the end of an item: one
     * tick that lands the transport in {@link TransportState#ENDED} moves the
     * cursor to the next item and starts it (the playlist wraps, so this keeps
     * cycling until an explicit stop).
     *
     * @param deltaMs elapsed wall time since the last call
     * @return true when the tick moved the transport into a new item
     */
    public boolean tickAndAdvance(long deltaMs) {
        if (transport == null || playlist == null) {
            return false;
        }
        transport.tick(deltaMs);
        if (transport.state() != TransportState.ENDED) {
            return false;
        }
        // No empty-list guard is needed here: the transport can only reach
        // ENDED through a loaded item, and an item exists in the playlist
        // whenever anything has been loaded through playCurrent().
        // The cursor must move BEFORE the load, or the item that just ended
        // would be replayed and its successor skipped.
        playlist.next();
        return playCurrent();
    }

    /**
     * Move the cursor to the next item without playing it.
     *
     * @return true when the playlist is non-empty
     */
    public boolean advance() {
        if (playlist == null || playlist.isEmpty()) {
            return false;
        }
        playlist.next();
        return true;
    }
}
