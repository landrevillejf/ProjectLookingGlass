/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * lg3d-apps Wonderland M7a: the live MediaSurface — the JMF → OpenJFX Media
 * replacement for the wonderland videoplayer module.
 *
 * The 2006 videoplayer drove javax.media (JMF), which has no JDK 21 build and
 * is not recoverable. The modern equivalent is OpenJFX Media — the engine
 * already in-tree behind the Web Browser — and like the browser it is booted
 * by constructing a JFXPanel on the EDT (javafx Platform.implicitExit is off
 * for the desktop; the browser panel owns that, see BrowserPanel) and every
 * mutating call marshals through Platform.runLater.
 *
 * Why it lives HERE and not in wonderland-modules: JavaFX may initialise only
 * in the 2D desktop JVM — never the Java 3D process — and wonderland-modules
 * keeps a hard headless floor with no toolkit types at all. The module owns
 * the transport policy and reaches this sink through the
 * VideoPlayerPlugin.installSurfaceFactory slot, the same installer-seam idiom
 * M6b used for the avatar renderer. CI compile-checks this class; live
 * playback is verified on the desktop, not claimed from a headless run.
 */
package org.jdesktop.lg3d.apps.wonderland;

import java.io.File;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import org.jdesktop.wonderland.modules.videoplayer.MediaSurface;

/**
 * The OpenJFX-backed {@link MediaSurface}. One instance drives one
 * {@link MediaPlayer}; {@link #open} replaces the media, {@link #release}
 * disposes the player.
 */
public final class JfxMediaSurface implements MediaSurface {

    /**
     * Boots the JavaFX toolkit on the EDT — the browser's proven pattern.
     * Held only for its boot side effect; the video itself is shown through
     * {@link #getMediaView()}.
     */
    private final JFXPanel fxPanel = new JFXPanel();

    private volatile MediaView mediaView;
    private volatile MediaPlayer player;

    /** The JavaFX view node, for embedding in a heavyweight host. */
    public MediaView getMediaView() {
        return mediaView;
    }

    @Override
    public void open(String mediaUri, long durationHintMs) {
        run(() -> {
            MediaPlayer old = player;
            if (old != null) {
                old.stop();
                old.dispose();
            }
            Media media = new Media(toLocation(mediaUri));
            player = new MediaPlayer(media);
            if (mediaView == null) {
                mediaView = new MediaView(player);
            } else {
                mediaView.setMediaPlayer(player);
            }
            // the transport's own clock is the stand-in until the real
            // duration is discovered; report the true one back through
            // currentTime/duration properties when the UI binds them.
            player.setOnEndOfMedia(() -> run(this::pauseAtEnd));
        });
    }

    @Override
    public void play() {
        run(() -> {
            MediaPlayer p = player;
            if (p != null) {
                p.play();
            }
        });
    }

    @Override
    public void pause() {
        run(() -> {
            MediaPlayer p = player;
            if (p != null) {
                p.pause();
            }
        });
    }

    @Override
    public void stop() {
        run(() -> {
            MediaPlayer p = player;
            if (p != null) {
                p.stop();
            }
        });
    }

    @Override
    public void seekTo(long positionMs) {
        run(() -> {
            MediaPlayer p = player;
            if (p != null) {
                p.seek(javafx.util.Duration.millis(positionMs));
            }
        });
    }

    @Override
    public void setVolume(double volume) {
        run(() -> {
            MediaPlayer p = player;
            if (p != null) {
                p.setVolume(volume); // the transport already clamped to [0,1]
            }
        });
    }

    @Override
    public void release() {
        run(() -> {
            MediaPlayer p = player;
            if (p != null) {
                p.stop();
                p.dispose();
            }
            player = null;
            if (mediaView != null) {
                mediaView.setMediaPlayer(null);
            }
        });
    }

    /** The end-of-media callback lands on the FX thread already. */
    private void pauseAtEnd() {
        MediaPlayer p = player;
        if (p != null) {
            p.pause(); // the transport's tick walk owns the ENDED transition
        }
    }

    private static void run(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /**
     * Accept both a bare path and any URI form JavaFX understands; a plain
     * file path is converted so local playlist items play (the 2006 module
     * went through a JMF MediaHandler for exactly this).
     */
    private static String toLocation(String mediaUri) {
        if (mediaUri.contains("://") || mediaUri.startsWith("file:")) {
            return mediaUri;
        }
        return new File(mediaUri).toURI().toString();
    }
}
