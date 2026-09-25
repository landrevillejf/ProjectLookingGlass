/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.scenemanager.utils.snap;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Timer;
import org.jdesktop.lg3d.displayserver.desktop2d.WindowSnap;
import org.jdesktop.lg3d.scenemanager.utils.SceneControl;
import org.jdesktop.lg3d.scenemanager.utils.event.ScreenResolutionChangedEvent;
import org.jdesktop.lg3d.scenemanager.utils.hud.DesktopHudLayer;
import org.jdesktop.lg3d.scenemanager.utils.hud.DesktopHudPlugin;
import org.jdesktop.lg3d.scenemanager.utils.plugin.SceneManagerPlugin;
import org.jdesktop.lg3d.scenemanager.utils.taskbar.Taskbar;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.HostedWindowResizer;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jdesktop.lg3d.wg.event.Component3DManualMoveEvent;
import org.jdesktop.lg3d.wg.event.Component3DToFrontEvent;
import org.jdesktop.lg3d.wg.event.LgEvent;
import org.jdesktop.lg3d.wg.event.LgEventConnector;
import org.jdesktop.lg3d.wg.event.LgEventListener;
import org.jdesktop.lg3d.wg.event.LgEventSource;
import org.jogamp.vecmath.Vector3f;

/**
 * Edge window snapping for the native 3D desktop: drag a {@link Frame3D} so its
 * left / right / top edge reaches the matching screen edge and, on release, it
 * fills that half of the usable area (or the whole area at the top). While the
 * drag is over a target, a translucent {@link SnapPreview3D} quad on the HUD
 * shows where the window will land. This is the {@code Frame3D} counterpart of
 * the 2D desktop's {@code SnappingDesktopManager} + {@code SnapPreview} pair;
 * {@code GlassyTaskbar} is not touched.
 *
 * <p>The snap model — the zone vocabulary, the half/maximise split and the
 * usable-region geometry — is not re-invented here. The pure world-space
 * arithmetic lives in {@link WindowSnap3D}, which reaches the shared
 * {@link WindowSnap} model the 2D desktop already uses; this class is only the
 * scene-graph and event glue.</p>
 *
 * <h2>Why a poll timer</h2>
 * <p>{@link Component3DMover} posts a {@link Component3DManualMoveEvent} only on
 * drag <em>start</em> and <em>release</em>; during the drag it calls
 * {@code setTranslation} directly and fires no per-move event. To light the
 * preview under the moving window the plugin therefore starts a short
 * {@link Timer} on drag start that samples the frame's current translation and
 * scale, and stops it on release. This is the same poll idiom the workspace
 * pager uses to wait for its {@code SwingNode} size.</p>
 *
 * <h2>Committing the snap</h2>
 * <p>The release mirrors {@code Frame3DWindowDecoration.toggleMaximized}: a
 * hosted Swing window ({@link HostedWindowResizer#isResizable}) is resized in
 * native pixels so its text stays crisp, a pure-3D window is uniformly scaled
 * with {@link WindowSnap3D#fitScale} to preserve its aspect ratio; either way
 * the window is re-centred on the target and brought to the front.</p>
 *
 * <p>{@link #getPluginRoot()} returns null: the preview is parented to the HUD
 * layer so it inherits the layer's perspective-compensated front pose. When
 * {@link DesktopHudPlugin} is not running the preview is skipped with a warning,
 * but snapping on release still works.</p>
 */
public class WindowSnapPlugin implements SceneManagerPlugin {

    private static final Logger logger = Logger.getLogger("lg.scenemanager.snap");

    /** Poll interval while a window is being dragged, to track the preview. */
    static final int PREVIEW_POLL_MS = 60;

    /** Animation duration of the snap on release; snappier than a maximise. */
    static final int SNAP_DURATION_MS = 200;

    private DesktopHudLayer layer;
    private SnapPreview3D preview;
    private Timer previewTimer;

    private LgEventListener moveListener;
    private LgEventListener resolutionListener;

    /** The frame currently being dragged, or null between drags. */
    private volatile Frame3D draggedFrame;
    /** The zone the last poll resolved for the dragged frame. */
    private volatile WindowSnap.Zone pendingZone = WindowSnap.Zone.NONE;

    public WindowSnapPlugin() {
    }

    @Override
    public void initialize(SceneControl sceneControl) {
        installMoveListener();
        installResolutionListener();

        layer = DesktopHudPlugin.layer();
        if (layer == null) {
            logger.warning("DesktopHudPlugin is not running; the snap preview is "
                    + "disabled and windows snap without a highlight. Register "
                    + "WindowSnapPlugin after DesktopHudPlugin in glassy.lgcfg.");
            return;
        }
        try {
            preview = new SnapPreview3D();
            layer.addChild(preview);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "could not mount the snap preview", t);
            preview = null;
        }
    }

    @Override
    public Component3D getPluginRoot() {
        // The preview is attached to the HUD layer, not the scene root.
        return null;
    }

    @Override
    public void destroy() {
        stopPreviewPoll();
        LgEventConnector connector = LgEventConnector.getLgEventConnector();
        removeQuietly(connector, Frame3D.class, moveListener);
        removeQuietly(connector, LgEventSource.ALL_SOURCES, resolutionListener);
        moveListener = null;
        resolutionListener = null;

        SnapPreview3D p = preview;
        if (p != null) {
            p.hide();
            if (layer != null) {
                layer.removeChild(p);
            }
            preview = null;
        }
        draggedFrame = null;
        pendingZone = WindowSnap.Zone.NONE;
        layer = null;
    }

    @Override
    public boolean isRemovable() {
        return true;
    }

    // ------------------------------------------------------------------- drag

    private void installMoveListener() {
        moveListener = new LgEventListener() {
            @Override
            public void processEvent(LgEvent evt) {
                if (!(evt instanceof Component3DManualMoveEvent move)) {
                    return;
                }
                LgEventSource source = evt.getSource();
                if (!(source instanceof Frame3D frame)) {
                    return;
                }
                if (move.isStarted()) {
                    dragStarted(frame);
                } else {
                    dragReleased(frame);
                }
            }

            @Override
            public Class[] getTargetEventClasses() {
                return new Class[] { Component3DManualMoveEvent.class };
            }
        };
        LgEventConnector.getLgEventConnector().addListener(Frame3D.class, moveListener);
    }

    private void dragStarted(Frame3D frame) {
        draggedFrame = frame;
        pendingZone = WindowSnap.Zone.NONE;
        startPreviewPoll();
    }

    private void dragReleased(Frame3D frame) {
        stopPreviewPoll();
        hidePreview();
        draggedFrame = null;
        // Recompute from the frame's resting transform rather than trusting the
        // last poll, so the snap matches exactly where the user let go.
        WindowSnap.Zone zone = resolveZone(frame);
        pendingZone = WindowSnap.Zone.NONE;
        if (zone != WindowSnap.Zone.NONE) {
            applySnap(frame, zone);
        }
    }

    private void startPreviewPoll() {
        stopPreviewPoll();
        previewTimer = new Timer(PREVIEW_POLL_MS, e -> updatePreview());
        previewTimer.setRepeats(true);
        previewTimer.start();
        updatePreview();
    }

    private void stopPreviewPoll() {
        Timer t = previewTimer;
        if (t != null) {
            t.stop();
            previewTimer = null;
        }
    }

    /** Samples the dragged frame and lights the preview over its snap target. */
    void updatePreview() {
        Frame3D frame = draggedFrame;
        if (frame == null) {
            return;
        }
        WindowSnap.Zone zone = resolveZone(frame);
        pendingZone = zone;
        if (zone == WindowSnap.Zone.NONE) {
            hidePreview();
            return;
        }
        float[] screen = screenRect();
        if (screen == null) {
            return;
        }
        float[] centre = WindowSnap3D.targetCentre(zone, screen);
        float[] size = WindowSnap3D.targetSize(zone, screen);
        SnapPreview3D p = preview;
        if (p != null && centre != null && size != null) {
            p.showSnap(centre[0], centre[1], size[0], size[1]);
        }
    }

    private void hidePreview() {
        SnapPreview3D p = preview;
        if (p != null) {
            p.hide();
        }
    }

    // ------------------------------------------------------------------- snap

    /** The snap zone {@code frame}'s current transform maps to, or NONE. */
    WindowSnap.Zone resolveZone(Frame3D frame) {
        if (frame == null) {
            return WindowSnap.Zone.NONE;
        }
        float[] screen = screenRect();
        if (screen == null) {
            return WindowSnap.Zone.NONE;
        }
        Vector3f trans = frame.getFinalTranslation(new Vector3f());
        Vector3f pref = frame.getPreferredSize(new Vector3f());
        float scale = frame.getFinalScale();
        float[] rect = WindowSnap3D.frameRect(
                trans.x, trans.y, pref.x, pref.y, scale);
        return WindowSnap3D.zone(rect, screen);
    }

    /**
     * Moves/resizes {@code frame} to fill {@code zone}'s target region, exactly
     * the way the decoration's maximise does: pixel-resize a hosted Swing
     * window, uniform aspect-preserving scale for a pure-3D one, then re-centre
     * and bring to front.
     */
    void applySnap(Frame3D frame, WindowSnap.Zone zone) {
        if (frame == null || zone == WindowSnap.Zone.NONE) {
            return;
        }
        float[] screen = screenRect();
        if (screen == null) {
            return;
        }
        float[] centre = WindowSnap3D.targetCentre(zone, screen);
        float[] size = WindowSnap3D.targetSize(zone, screen);
        if (centre == null || size == null) {
            return;
        }
        try {
            Toolkit3D tk = Toolkit3D.getToolkit3D();
            float z = frame.getFinalTranslation(new Vector3f()).z;
            if (HostedWindowResizer.isResizable(frame)) {
                HostedWindowResizer.resize(frame,
                        tk.widthPhysicalToNative(size[0]),
                        tk.heightPhysicalToNative(size[1]));
                frame.changeTranslation(
                        new Vector3f(centre[0], centre[1], z), SNAP_DURATION_MS);
            } else {
                Vector3f pref = frame.getPreferredSize(new Vector3f());
                float scale = WindowSnap3D.fitScale(
                        pref.x, pref.y, size[0], size[1], WindowSnap3D.FIT_MARGIN);
                if (scale > 0f) {
                    frame.changeScale(scale, SNAP_DURATION_MS);
                }
                frame.changeTranslation(
                        new Vector3f(centre[0], centre[1], z), SNAP_DURATION_MS);
            }
            frame.postEvent(new Component3DToFrontEvent());
        } catch (Throwable t) {
            logger.log(Level.WARNING, "snap commit failed", t);
        }
    }

    /**
     * The usable screen rectangle in world units (screen minus the taskbar's
     * reserved strips and the title-bar headroom), or null before the toolkit is
     * ready. Recomputed on demand so a resolution change is picked up.
     */
    private float[] screenRect() {
        try {
            Toolkit3D tk = Toolkit3D.getToolkit3D();
            float w = tk.getScreenWidth();
            float h = tk.getScreenHeight();
            if (!(w > 0f) || !(h > 0f)) {
                return null;
            }
            return WindowSnap3D.screenRect(w, h,
                    Taskbar.getReservedBottomHeight(), Taskbar.getReservedTopHeight());
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------- resolution

    private void installResolutionListener() {
        resolutionListener = new LgEventListener() {
            @Override
            public void processEvent(LgEvent evt) {
                // The screen rect is recomputed on demand; hide a stale preview.
                hidePreview();
            }

            @Override
            public Class[] getTargetEventClasses() {
                return new Class[] { ScreenResolutionChangedEvent.class };
            }
        };
        LgEventConnector.getLgEventConnector()
                .addListener(LgEventSource.ALL_SOURCES, resolutionListener);
    }

    private static void removeQuietly(LgEventConnector connector, Class sourceClass,
            LgEventListener listener) {
        if (listener == null) {
            return;
        }
        try {
            connector.removeListener(sourceClass, listener);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "could not remove a snap listener", t);
        }
    }
}
