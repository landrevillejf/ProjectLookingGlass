/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7a: headless tests for the VideoTransport state machine.
 *
 * Drives the transport with a recording MediaSurface stub and asserts BOTH the
 * resulting state and the exact commands that reached the surface -- the whole
 * point of the seam is that no JavaFX type is ever constructed here. Mirrors
 * the M6c1 CellFrameBridgeTest discipline.
 */
package org.jdesktop.wonderland.modules.videoplayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VideoTransportTest {

    private RecordingSurface surface;
    private VideoTransport transport;

    @BeforeEach
    void setUp() {
        surface = new RecordingSurface();
        transport = new VideoTransport(surface);
    }

    @Test
    void nullSurfaceIsRefused() {
        assertThatThrownBy(() -> new VideoTransport(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startsIdle() {
        assertThat(transport.state()).isEqualTo(TransportState.IDLE);
        assertThat(transport.mediaUri()).isNull();
        assertThat(transport.position()).isZero();
        assertThat(transport.volume()).isEqualTo(1.0);
    }

    @Test
    void loadOpensTheSurfaceAndResetsPosition() {
        transport.seek(0); // no-op before load
        boolean ok = transport.load("file://clip.mp4", 5000);
        assertThat(ok).isTrue();
        assertThat(transport.state()).isEqualTo(TransportState.IDLE);
        assertThat(transport.mediaUri()).isEqualTo("file://clip.mp4");
        assertThat(transport.duration()).isEqualTo(5000);
        assertThat(surface.log).containsExactly("open:file://clip.mp4:5000");
    }

    @Test
    void loadRefusesNullOrEmptyUri() {
        assertThat(transport.load(null, 1000)).isFalse();
        assertThat(transport.load("", 1000)).isFalse();
        assertThat(surface.log).isEmpty();
    }

    @Test
    void loadWhilePlayingStopsTheOldResourceFirst() {
        transport.load("a.mp4", 1000);
        transport.play();
        transport.load("b.mp4", 2000);
        assertThat(surface.log).containsExactly(
                "open:a.mp4:1000", "play", "stop", "open:b.mp4:2000");
    }

    @Test
    void playFromIdleStartsPlayback() {
        transport.load("a.mp4", 1000);
        transport.play();
        assertThat(transport.state()).isEqualTo(TransportState.PLAYING);
        assertThat(surface.log).containsExactly("open:a.mp4:1000", "play");
    }

    @Test
    void playWithoutLoadIsIgnored() {
        transport.play();
        assertThat(transport.state()).isEqualTo(TransportState.IDLE);
        assertThat(surface.log).isEmpty();
    }

    @Test
    void playWhilePlayingIsIdempotent() {
        transport.load("a.mp4", 1000);
        transport.play();
        transport.play();
        assertThat(surface.log).containsExactly("open:a.mp4:1000", "play");
    }

    @Test
    void pauseOnlyActsWhilePlaying() {
        transport.pause(); // idle -> ignored
        assertThat(surface.log).isEmpty();

        transport.load("a.mp4", 1000);
        transport.play();
        transport.pause();
        assertThat(transport.state()).isEqualTo(TransportState.PAUSED);
        transport.pause(); // already paused -> ignored
        assertThat(surface.log).containsExactly("open:a.mp4:1000", "play", "pause");
    }

    @Test
    void playResumesFromPaused() {
        transport.load("a.mp4", 1000);
        transport.play();
        transport.pause();
        transport.play();
        assertThat(transport.state()).isEqualTo(TransportState.PLAYING);
        assertThat(surface.log).containsExactly(
                "open:a.mp4:1000", "play", "pause", "play");
    }

    @Test
    void stopRewindsAndIsIgnoredWhenAlreadyStopped() {
        transport.load("a.mp4", 1000);
        transport.play();
        transport.tick(400);
        transport.stop();
        assertThat(transport.state()).isEqualTo(TransportState.STOPPED);
        assertThat(transport.position()).isZero();
        transport.stop(); // stopped -> ignored
        assertThat(surface.log).containsExactly(
                "open:a.mp4:1000", "play", "stop");
    }

    @Test
    void playFromStoppedRestartsAtZero() {
        transport.load("a.mp4", 1000);
        transport.play();
        transport.stop();
        transport.play();
        assertThat(transport.state()).isEqualTo(TransportState.PLAYING);
        assertThat(transport.position()).isZero();
    }

    @Test
    void seekClampsToTheDuration() {
        transport.load("a.mp4", 1000);
        transport.seek(5000);
        assertThat(transport.position()).isEqualTo(1000);
        assertThat(surface.log).containsExactly("open:a.mp4:1000", "seek:1000");
    }

    @Test
    void seekClampsNegativesToZero() {
        transport.load("a.mp4", 1000);
        transport.seek(-300);
        assertThat(transport.position()).isZero();
        assertThat(surface.log).containsExactly("open:a.mp4:1000", "seek:0");
    }

    @Test
    void seekBeforeLoadIsIgnored() {
        transport.seek(500);
        assertThat(surface.log).isEmpty();
    }

    @Test
    void seekKeepsTheStateUnchanged() {
        transport.load("a.mp4", 1000);
        transport.play();
        transport.pause();
        transport.seek(600);
        assertThat(transport.state()).isEqualTo(TransportState.PAUSED);
        assertThat(transport.position()).isEqualTo(600);
    }

    @Test
    void volumeClampsToUnitRange() {
        transport.setVolume(1.5);
        assertThat(transport.volume()).isEqualTo(1.0);
        assertThat(surface.log).containsExactly("volume:1.0");

        surface.log.clear();
        transport.setVolume(-0.2);
        assertThat(transport.volume()).isZero();
        assertThat(surface.log).containsExactly("volume:0.0");
    }

    @Test
    void volumeTreatsNaNAsMute() {
        transport.setVolume(Double.NaN);
        assertThat(transport.volume()).isZero();
        assertThat(surface.log).containsExactly("volume:0.0");
    }

    @Test
    void tickAdvancesOnlyWhilePlaying() {
        transport.load("a.mp4", 1000);
        transport.tick(300); // idle -> no advance
        assertThat(transport.position()).isZero();

        transport.play();
        transport.tick(300);
        assertThat(transport.position()).isEqualTo(300);
        assertThat(transport.state()).isEqualTo(TransportState.PLAYING);
    }

    @Test
    void tickWalksToEndedAndHaltsOnceAtTheEnd() {
        transport.load("a.mp4", 1000);
        transport.play();
        transport.tick(1200);
        assertThat(transport.state()).isEqualTo(TransportState.ENDED);
        assertThat(transport.position()).isEqualTo(1000);
        assertThat(surface.log).containsExactly("open:a.mp4:1000", "play", "stop");
    }

    @Test
    void tickAfterEndedDoesNotAdvance() {
        transport.load("a.mp4", 1000);
        transport.play();
        transport.tick(1500); // -> ENDED
        surface.log.clear();
        transport.tick(500);
        assertThat(transport.position()).isEqualTo(1000);
        assertThat(surface.log).isEmpty();
    }

    @Test
    void releaseDelegatesAndResetsToIdle() {
        transport.load("a.mp4", 1000);
        transport.play();
        transport.release();
        assertThat(transport.state()).isEqualTo(TransportState.IDLE);
        assertThat(transport.position()).isZero();
        assertThat(surface.log).contains("release");
    }

    @Test
    void isPlayingTracksThePlayingState() {
        transport.load("a.mp4", 1000);
        assertThat(transport.isPlaying()).isFalse();
        transport.play();
        assertThat(transport.isPlaying()).isTrue();
    }

    @Test
    void pauseOnIdleIsIgnored() {
        transport.load("a.mp4", 1000); // IDLE, never played
        transport.pause();
        assertThat(transport.state()).isEqualTo(TransportState.IDLE);
        assertThat(surface.log).containsExactly("open:a.mp4:1000");
    }

    @Test
    void stopOnIdleIsIgnored() {
        transport.load("a.mp4", 1000);
        transport.stop();
        assertThat(surface.log).containsExactly("open:a.mp4:1000");
    }

    @Test
    void playFromEndedRestartsAtZero() {
        transport.load("a.mp4", 1000);
        transport.play();
        transport.tick(1500); // -> ENDED
        surface.log.clear();
        transport.play();
        assertThat(transport.state()).isEqualTo(TransportState.PLAYING);
        assertThat(transport.position()).isZero();
        assertThat(surface.log).containsExactly("play");
    }

    @Test
    void seekIsUnclampedWhileTheDurationIsUnknown() {
        transport.load("live.stream", 0); // a live/unknown-length source
        transport.seek(5000);
        assertThat(transport.position()).isEqualTo(5000);
        assertThat(surface.log).containsExactly("open:live.stream:0", "seek:5000");
    }

    @Test
    void loadFloorsANegativeDurationToZero() {
        transport.load("a.mp4", -700);
        assertThat(transport.duration()).isZero();
        assertThat(surface.log).containsExactly("open:a.mp4:0");
    }

    @Test
    void tickNeverWalksToTheEndWhileTheDurationIsUnknown() {
        transport.load("live.stream", 0);
        transport.play();
        transport.tick(100000);
        assertThat(transport.state()).isEqualTo(TransportState.PLAYING);
        assertThat(transport.position()).isEqualTo(100000);
    }

    /** Records every command the transport issues, as readable tokens. */
    private static final class RecordingSurface implements MediaSurface {
        final List<String> log = new ArrayList<>();

        @Override
        public void open(String mediaUri, long durationHintMs) {
            log.add("open:" + mediaUri + ":" + durationHintMs);
        }

        @Override
        public void play() {
            log.add("play");
        }

        @Override
        public void pause() {
            log.add("pause");
        }

        @Override
        public void stop() {
            log.add("stop");
        }

        @Override
        public void seekTo(long positionMs) {
            log.add("seek:" + positionMs);
        }

        @Override
        public void setVolume(double volume) {
            log.add("volume:" + volume);
        }

        @Override
        public void release() {
            log.add("release");
        }
    }
}
