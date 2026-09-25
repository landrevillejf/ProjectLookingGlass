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
package org.jdesktop.lg3d.scenemanager.utils.workspace;

import java.awt.event.KeyEvent;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Timer;
import org.jdesktop.lg3d.displayserver.desktop2d.WorkspaceModel;
import org.jdesktop.lg3d.scenemanager.utils.SceneControl;
import org.jdesktop.lg3d.scenemanager.utils.appcontainer.AppContainer;
import org.jdesktop.lg3d.scenemanager.utils.event.Frame3DAddedEvent;
import org.jdesktop.lg3d.scenemanager.utils.event.Frame3DRemovedEvent;
import org.jdesktop.lg3d.scenemanager.utils.event.ScreenResolutionChangedEvent;
import org.jdesktop.lg3d.scenemanager.utils.hud.DesktopHudLayer;
import org.jdesktop.lg3d.scenemanager.utils.hud.DesktopHudPlugin;
import org.jdesktop.lg3d.scenemanager.utils.plugin.SceneManagerPlugin;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.event.Component3DToFrontEvent;
import org.jdesktop.lg3d.wg.event.KeyEvent3D;
import org.jdesktop.lg3d.wg.event.LgEvent;
import org.jdesktop.lg3d.wg.event.LgEventConnector;
import org.jdesktop.lg3d.wg.event.LgEventListener;
import org.jdesktop.lg3d.wg.event.LgEventSource;

/**
 * Multiple workspaces (virtual desktops) for the native 3D desktop: the plugin
 * counterpart of the 2D/Swing desktop's {@code WorkspaceModel} + {@code
 * WorkspacePager} pair, and the first window-management feature ported off the
 * taskbar. {@code GlassyTaskbar} is not touched.
 *
 * <p>A workspace here is a <em>partition of the open {@link Frame3D}s</em>: the
 * plugin tracks frames through {@link Frame3DAddedEvent}/{@link Frame3DRemovedEvent},
 * assigns each to the workspace that is current when it opens, and shows or hides
 * it through {@link Component3D#setVisible(boolean)} on every switch. The pure
 * bookkeeping lives in {@link WorkspaceRegistry} (headless-testable); this class is
 * only the scene-graph and event glue.</p>
 *
 * <h2>Why not the multi-{@code AppContainer} plumbing</h2>
 * <p>{@code SceneControl.setCurrentAppContainer} was evaluated and rejected. It
 * deliberately does not add a container to the scene graph (its own comment says
 * the caller must, and {@code SceneControl} exposes no scene root to do it with),
 * {@code AppContainer.setEnabled} only flips a boolean, {@code getAppContainer}
 * leaves {@code null} slots that {@code setCurrentAppContainer} would then
 * dereference, and — decisively — {@code StandardAppContainer.initialize()}
 * registers <em>global</em> {@code Frame3D} listeners, including a to-front
 * handler that migrates the frame into <em>that</em> container's main layout, with
 * a comment warning that instantiating the container more than once duplicates
 * those actions. A second workspace container would therefore pull frames back
 * into the first. Partitioning the single container's frames is both smaller and
 * correct; see {@link WorkspaceRegistry} for the full rationale.</p>
 *
 * <h2>Input</h2>
 * <p>Switching is offered two ways. The HUD pager (a {@link WorkspacePager3D} in
 * the top-left corner) is clickable — cell for direct switch, arrows to step. The
 * keystrokes mirror the 2D desktop's {@code ShortcutMap}: <em>Alt+Shift+Page
 * Down</em>/<em>Page Up</em> page through the workspaces and
 * <em>Alt+Shift+1..9</em> moves the front window. Ctrl+Alt+arrow is deliberately
 * not bound because the host window manager grabs it in dev mode.</p>
 *
 * <p>Keys arrive through a global {@code KeyEvent3D} listener on
 * {@link LgEventSource#ALL_SOURCES}. This relies on the scene root already being a
 * key event source — {@code SceneManagerBase} installs its own Ctrl+P handler
 * there, which is what marks it — and, as {@code PickEngine} documents, a key event
 * goes to the <em>deepest</em> key-event source under the pointer, so a keystroke
 * typed into a focused text field of an application is delivered to that
 * application. That is the same limitation the existing Ctrl+P binding has.</p>
 *
 * <h2>Following the front window</h2>
 * <p>The taskbar's thumbnails are not workspace-aware (and must not be modified),
 * so clicking one for a window on another workspace would otherwise "focus" an
 * invisible window. The plugin therefore listens for
 * {@link Component3DToFrontEvent} and switches to the workspace that window lives
 * on, which makes the taskbar behave as a user expects.</p>
 *
 * <p>{@link #getPluginRoot()} returns null: the pager is parented to the HUD layer
 * rather than the scene root, so it inherits the layer's perspective-compensated
 * front pose. When {@link DesktopHudPlugin} is not running the pager is skipped
 * with a warning, but the registry and the keystrokes still work.</p>
 */
public class WorkspacePlugin implements SceneManagerPlugin {

    private static final Logger logger = Logger.getLogger("lg.scenemanager.workspace");

    /** World-unit gap between the pager and the top-left screen corner. */
    static final float MARGIN = 0.012f;

    /** Poll interval while waiting for the pager's SwingNode to report its size. */
    static final int REPOSITION_POLL_MS = 200;

    /** The running registry, for sibling plugins and apps. Null when not running. */
    private static volatile WorkspaceRegistry active;

    private WorkspaceRegistry registry;
    private WorkspacePager3D pager;
    private DesktopHudLayer layer;
    private Timer repositionTimer;
    private boolean positioned;

    private LgEventListener frameListener;
    private LgEventListener toFrontListener;
    private LgEventListener keyListener;
    private LgEventListener resolutionListener;

    /** Frame → the window id it was registered under, so removal always matches. */
    private final Map<Frame3D, String> ids = new IdentityHashMap<>();
    private volatile Frame3D frontFrame;

    public WorkspacePlugin() {
    }

    /**
     * The running plugin's registry, or null when the plugin is not initialized.
     * Mirrors {@link DesktopHudPlugin#layer()}.
     */
    public static WorkspaceRegistry registry() {
        return active;
    }

    @Override
    public void initialize(SceneControl sceneControl) {
        registry = new WorkspaceRegistry(WorkspaceModel.DEFAULT_COUNT);
        active = registry;
        positioned = false;

        installFrameListener();
        installToFrontListener();
        installKeyListener();
        installResolutionListener();

        layer = DesktopHudPlugin.layer();
        if (layer == null) {
            logger.warning("DesktopHudPlugin is not running; the workspace pager is "
                    + "disabled and workspaces can only be switched with the keyboard. "
                    + "Register WorkspacePlugin after DesktopHudPlugin in glassy.lgcfg.");
            return;
        }
        try {
            pager = new WorkspacePager3D(registry);
            layer.addChild(pager);
            layer.placeAt(pager, 0f, 0f);
            startRepositionPoll();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "could not mount the workspace pager", t);
        }
    }

    @Override
    public Component3D getPluginRoot() {
        // The pager is attached to the HUD layer, not the scene root.
        return null;
    }

    @Override
    public void destroy() {
        stopRepositionPoll();
        LgEventConnector connector = LgEventConnector.getLgEventConnector();
        removeQuietly(connector, AppContainer.class, frameListener);
        removeQuietly(connector, Frame3D.class, toFrontListener);
        removeQuietly(connector, LgEventSource.ALL_SOURCES, keyListener);
        removeQuietly(connector, LgEventSource.ALL_SOURCES, resolutionListener);
        frameListener = null;
        toFrontListener = null;
        keyListener = null;
        resolutionListener = null;

        WorkspacePager3D p = pager;
        if (p != null) {
            try {
                p.dispose();
            } catch (Throwable t) {
                logger.log(Level.WARNING, "workspace pager dispose failed", t);
            }
            if (layer != null) {
                layer.removeChild(p);
            }
            pager = null;
        }

        // Never leave another workspace's windows hidden with no way back.
        WorkspaceRegistry r = registry;
        if (r != null) {
            try {
                r.releaseAll();
            } catch (Throwable t) {
                logger.log(Level.WARNING, "workspace teardown failed", t);
            }
        }
        ids.clear();
        frontFrame = null;
        registry = null;
        layer = null;
        positioned = false;
        if (active == r) {
            active = null;
        }
    }

    @Override
    public boolean isRemovable() {
        return true;
    }

    // ---------------------------------------------------------------- windows

    private void installFrameListener() {
        frameListener = new LgEventListener() {
            @Override
            public void processEvent(LgEvent evt) {
                if (evt instanceof Frame3DAddedEvent added) {
                    frameAdded(added.getFrame3D());
                } else if (evt instanceof Frame3DRemovedEvent removed) {
                    frameRemoved(removed.getFrame3D());
                }
            }

            @Override
            public Class[] getTargetEventClasses() {
                return new Class[] { Frame3DAddedEvent.class, Frame3DRemovedEvent.class };
            }
        };
        LgEventConnector.getLgEventConnector().addListener(AppContainer.class, frameListener);
    }

    /** Puts a newly opened frame on the current workspace and shows it. */
    void frameAdded(Frame3D frame) {
        if (frame == null || registry == null) {
            return;
        }
        String id = WorkspaceRegistry.windowId(frame.getName(), System.identityHashCode(frame));
        ids.put(frame, id);
        registry.register(id, frame::setVisible);
    }

    /** Forgets a closed frame, leaving its window's visibility alone. */
    void frameRemoved(Frame3D frame) {
        if (frame == null) {
            return;
        }
        String id = ids.remove(frame);
        if (id != null && registry != null) {
            registry.unregister(id);
        }
        if (frame == frontFrame) {
            frontFrame = null;
        }
    }

    // ------------------------------------------------------------ front window

    private void installToFrontListener() {
        toFrontListener = new LgEventListener() {
            @Override
            public void processEvent(LgEvent evt) {
                LgEventSource source = evt.getSource();
                if (source instanceof Frame3D frame) {
                    frameToFront(frame);
                }
            }

            @Override
            public Class[] getTargetEventClasses() {
                return new Class[] { Component3DToFrontEvent.class };
            }
        };
        LgEventConnector.getLgEventConnector().addListener(Frame3D.class, toFrontListener);
    }

    /**
     * Remembers the front window (the target of Alt+Shift+digit) and follows it:
     * activating a window that lives on another workspace shows that workspace
     * rather than leaving an invisible window in front.
     */
    void frameToFront(Frame3D frame) {
        if (frame == null || registry == null) {
            return;
        }
        frontFrame = frame;
        String id = ids.get(frame);
        if (id == null) {
            return;
        }
        int workspace = registry.workspaceOf(id);
        if (workspace >= 0 && workspace != registry.current()) {
            registry.switchTo(workspace);
        }
    }

    // ------------------------------------------------------------------- keys

    private void installKeyListener() {
        keyListener = new LgEventListener() {
            @Override
            public void processEvent(LgEvent evt) {
                if (evt instanceof KeyEvent3D ke && ke.isPressed()) {
                    KeyEvent awt = ke.getKeyEvent();
                    if (awt != null) {
                        handleKey(awt);
                    }
                }
            }

            @Override
            public Class[] getTargetEventClasses() {
                return new Class[] { KeyEvent3D.class };
            }
        };
        LgEventConnector.getLgEventConnector()
                .addListener(LgEventSource.ALL_SOURCES, keyListener);
    }

    /** Applies one workspace keystroke. Package-private for tests. */
    void handleKey(KeyEvent e) {
        if (registry == null) {
            return;
        }
        WorkspaceKeys.KeyCommand command = WorkspaceKeys.resolve(
                e.isAltDown(), e.isShiftDown(),
                e.isControlDown() || e.isMetaDown(), e.getKeyCode());
        switch (command.kind()) {
            case NEXT -> registry.next();
            case PREVIOUS -> registry.previous();
            case MOVE -> moveFrontTo(command.workspace());
            case NONE -> {
                // Not a workspace keystroke; leave it to the application.
            }
        }
    }

    /** Moves the front window to workspace {@code index}, hiding it from here. */
    private void moveFrontTo(int index) {
        Frame3D frame = frontFrame;
        if (frame == null || registry == null) {
            return;
        }
        String id = ids.get(frame);
        if (id != null) {
            registry.moveTo(id, index);
        }
    }

    // ------------------------------------------------------------------ pager

    private void installResolutionListener() {
        resolutionListener = new LgEventListener() {
            @Override
            public void processEvent(LgEvent evt) {
                if (layer != null) {
                    layer.updateScreenSize();
                }
                positioned = false;
                reposition();
            }

            @Override
            public Class[] getTargetEventClasses() {
                return new Class[] { ScreenResolutionChangedEvent.class };
            }
        };
        LgEventConnector.getLgEventConnector()
                .addListener(LgEventSource.ALL_SOURCES, resolutionListener);
    }

    private void startRepositionPoll() {
        repositionTimer = new Timer(REPOSITION_POLL_MS, e -> {
            reposition();
            if (positioned) {
                stopRepositionPoll();
            }
        });
        repositionTimer.setRepeats(true);
        repositionTimer.start();
        reposition();
    }

    private void stopRepositionPoll() {
        Timer t = repositionTimer;
        if (t != null) {
            t.stop();
            repositionTimer = null;
        }
    }

    /**
     * Anchors the pager in the top-left corner. A no-op until both the SwingNode's
     * world size and the layer's screen size are known, so the poll retries.
     */
    void reposition() {
        if (pager == null || layer == null) {
            return;
        }
        float[] fraction = WorkspacePager3D.topLeftFraction(
                pager.pagerWidth(), pager.pagerHeight(),
                layer.screenWidth(), layer.screenHeight(), MARGIN);
        if (fraction[0] <= 0f && fraction[1] <= 0f) {
            return;
        }
        layer.placeAt(pager, fraction[0], fraction[1]);
        positioned = true;
    }

    private static void removeQuietly(LgEventConnector connector, Class sourceClass,
            LgEventListener listener) {
        if (listener == null) {
            return;
        }
        try {
            connector.removeListener(sourceClass, listener);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "could not remove a workspace listener", t);
        }
    }
}
