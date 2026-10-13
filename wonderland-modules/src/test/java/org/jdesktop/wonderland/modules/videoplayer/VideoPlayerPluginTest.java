/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7a: headless tests for the videoplayer plugin — the
 * ServiceLoader registration, the lifecycle, and the playlist auto-advance
 * that runs entirely on the installed surface factory.
 */
package org.jdesktop.wonderland.modules.videoplayer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import org.jdesktop.wonderland.modules.api.PluginContext;
import org.jdesktop.wonderland.modules.api.WonderlandPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VideoPlayerPluginTest {

    private static final PluginContext EMPTY_CONTEXT =
            new PluginContext(java.util.Collections.emptyMap());

    @AfterEach
    void uninstallFactory() {
        // keep the static slot clean for the rest of the suite
        VideoPlayerPlugin.installSurfaceFactory(null);
    }

    @Test
    void discoveredByServiceLoader() {
        boolean found = false;
        for (WonderlandPlugin plugin : ServiceLoader.load(WonderlandPlugin.class)) {
            if (VideoPlayerPlugin.MODULE_NAME.equals(plugin.getModuleInfo().getName())) {
                found = true;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void headlessInitializeBuildsTheModelOnTheNoopSurface() {
        VideoPlayerPlugin plugin = new VideoPlayerPlugin();
        plugin.initialize(EMPTY_CONTEXT);

        assertThat(plugin.getPlaylist()).isNotNull();
        assertThat(plugin.getTransport()).isNotNull();
        // the no-op transport still runs the full policy
        assertThat(plugin.playCurrent()).isFalse(); // empty playlist
        plugin.getPlaylist().add("a.mp4", 1000);
        assertThat(plugin.playCurrent()).isTrue();
        assertThat(plugin.getTransport().state()).isEqualTo(TransportState.PLAYING);

        plugin.dispose();
        assertThat(plugin.getTransport()).isNull();
        assertThat(plugin.getPlaylist()).isNull();
    }

    @Test
    void initializeIsIdempotent() {
        VideoPlayerPlugin plugin = new VideoPlayerPlugin();
        plugin.initialize(EMPTY_CONTEXT);
        Playlist first = plugin.getPlaylist();
        plugin.initialize(EMPTY_CONTEXT);
        assertThat(plugin.getPlaylist()).isSameAs(first);
    }

    @Test
    void disposeBeforeInitializeIsANoOp() {
        VideoPlayerPlugin plugin = new VideoPlayerPlugin();
        plugin.dispose();
        assertThat(plugin.getTransport()).isNull();
    }

    @Test
    void installedFactoryBacksTheTransport() {
        List<String> calls = new ArrayList<>();
        VideoPlayerPlugin.installSurfaceFactory(() -> new MediaSurface() {
            @Override
            public void open(String mediaUri, long durationHintMs) {
                calls.add("open:" + mediaUri);
            }

            @Override
            public void play() {
                calls.add("play");
            }

            @Override
            public void pause() {
            }

            @Override
            public void stop() {
                calls.add("stop");
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
        });

        VideoPlayerPlugin plugin = new VideoPlayerPlugin();
        plugin.initialize(EMPTY_CONTEXT);
        plugin.getPlaylist().add("live.mp4", 500);
        plugin.playCurrent();
        assertThat(calls).containsExactly("open:live.mp4", "play");
    }

    @Test
    void autoAdvanceMovesToTheNextItemAtTheEndOfOne() {
        VideoPlayerPlugin plugin = new VideoPlayerPlugin();
        plugin.initialize(EMPTY_CONTEXT);
        plugin.getPlaylist().add("a.mp4", 1000);
        plugin.getPlaylist().add("b.mp4", 1000);

        assertThat(plugin.playCurrent()).isTrue();
        // not at the end yet: the tick advances the clock only
        assertThat(plugin.tickAndAdvance(400)).isFalse();
        assertThat(plugin.getTransport().position()).isEqualTo(400);

        // reaching the duration ends a.mp4, moves the cursor, starts b.mp4
        assertThat(plugin.tickAndAdvance(700)).isTrue();
        assertThat(plugin.getTransport().mediaUri()).isEqualTo("b.mp4");
        assertThat(plugin.getTransport().state()).isEqualTo(TransportState.PLAYING);
        // the cursor moved 0 -> 1 BEFORE the load, so the ended item is never
        // replayed and its successor is never skipped
        assertThat(plugin.getPlaylist().cursor()).isEqualTo(1);
        assertThat(plugin.getTransport().position()).isZero();
    }

    @Test
    void advanceWithoutPlaylistIsRefused() {
        VideoPlayerPlugin plugin = new VideoPlayerPlugin();
        assertThat(plugin.advance()).isFalse();
        assertThat(plugin.tickAndAdvance(10)).isFalse(); // not initialized
        assertThat(plugin.playCurrent()).isFalse();      // not initialized
        plugin.initialize(EMPTY_CONTEXT);
        assertThat(plugin.advance()).isFalse(); // empty playlist
    }

    @Test
    void advanceWalksANonEmptyPlaylist() {
        VideoPlayerPlugin plugin = new VideoPlayerPlugin();
        plugin.initialize(EMPTY_CONTEXT);
        plugin.getPlaylist().add("a.mp4", 100);
        plugin.getPlaylist().add("b.mp4", 100);
        assertThat(plugin.advance()).isTrue();
        assertThat(plugin.getPlaylist().current().uri()).isEqualTo("b.mp4");
    }

    @Test
    void factorySlotReadsBackTheInstalledFactory() {
        VideoPlayerPlugin.MediaSurfaceFactory f = () -> NoopMediaSurfaceHolder.get();
        assertThat(VideoPlayerPlugin.surfaceFactory()).isNull(); // headless default
        VideoPlayerPlugin.installSurfaceFactory(f);
        assertThat(VideoPlayerPlugin.surfaceFactory()).isSameAs(f);
    }

    @Test
    void theShippedNoopSurfaceSwallowsTheWholeLifecycle() {
        // every command path of the shipped silent sink, through the plugin
        VideoPlayerPlugin plugin = new VideoPlayerPlugin();
        plugin.initialize(EMPTY_CONTEXT);
        plugin.getPlaylist().add("a.mp4", 1000);
        plugin.playCurrent();
        plugin.getTransport().pause();
        plugin.getTransport().seek(500);
        plugin.getTransport().setVolume(0.5);
        plugin.getTransport().stop();
        plugin.dispose(); // releases the noop surface
        assertThat(plugin.getTransport()).isNull();
    }

    @Test
    void theInterfaceDefaultDisposeDoesNothing() {
        // the API contract M4 shipped: a module with no resource to release
        // inherits a silent default; the videoplayer overrides it, a bare
        // provider must still dispose cleanly.
        WonderlandPlugin bare = new WonderlandPlugin() {
            @Override
            public org.jdesktop.wonderland.common.modules.ModuleInfo getModuleInfo() {
                return new org.jdesktop.wonderland.common.modules.ModuleInfo(
                        "bare", 1, 0, 0, "test-only default-dispose provider");
            }

            @Override
            public void initialize(PluginContext context) {
            }
        };
        bare.dispose();
        assertThat(bare.getRequires()).isNotNull(); // the other interface default
        assertThat(bare.getModuleInfo().getName()).isEqualTo("bare");
    }

    /** Indirection so the lambda factory stays readable. */
    static final class NoopMediaSurfaceHolder {
        static MediaSurface get() {
            return new MediaSurface() {
                @Override public void open(String u, long d) { }
                @Override public void play() { }
                @Override public void pause() { }
                @Override public void stop() { }
                @Override public void seekTo(long p) { }
                @Override public void setVolume(double v) { }
                @Override public void release() { }
            };
        }
    }
}
