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
package org.jdesktop.lg3d.scenemanager.utils.switcher;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Timer;
import org.jdesktop.lg3d.displayserver.desktop2d.WindowCycler;
import org.jdesktop.lg3d.scenemanager.utils.SceneControl;
import org.jdesktop.lg3d.scenemanager.utils.appcontainer.AppContainer;
import org.jdesktop.lg3d.scenemanager.utils.event.Frame3DAddedEvent;
import org.jdesktop.lg3d.scenemanager.utils.event.Frame3DRemovedEvent;
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
 * The Alt+Tab-style window switcher for the native 3D desktop: the plugin
 * counterpart of the 2D/Swing desktop's {@code WindowCyclerOverlay}, and the
 * third window-management feature ported off the taskbar (after
 * {@code WorkspacePlugin} and {@code WindowSnapPlugin}). {@code GlassyTaskbar}
 * is not touched.
 *
 * <p>The plugin keeps the live set of open {@link Frame3D}s through
 * {@link Frame3DAddedEvent}/{@link Frame3DRemovedEvent} and their MRU order
 * through {@link Component3DToFrontEvent}, feeding both into a
 * {@code WindowCycler<Frame3D>} &mdash; the very same pure MRU/cycle model the 2D
 * desktop uses, generalized over the window type rather than duplicated. A
 * trigger keystroke opens a cycle session and steps the highlight; a short idle
 * timer then commits the selection by posting a {@code Component3DToFrontEvent}
 * on the highlighted frame.</p>
 *
 * <h2>Why Ctrl+Alt+Tab, not Alt+Tab</h2>
 * <p>In development mode the desktop is an ordinary window under the host window
 * manager, which grabs Alt+Tab (and Super+Tab) before it ever reaches this JVM.
 * The Ctrl+Alt prefix is the plan's binding for the 3D switcher and is not taken
 * by the common host window managers. Committing is <em>not</em> a keystroke:
 * detecting the release of the modifiers is unreliable, so (exactly like the 2D
 * overlay) the selection commits on a 600&nbsp;ms idle timer once the user stops
 * pressing the trigger. See {@link WindowSwitcherKeys} for the full rationale.</p>
 *
 * <h2>Rows, not live thumbnails</h2>
 * <p>The overlay lists window titles (highlighted selected row), matching the 2D
 * overlay's icon+name rows. It does not reuse {@code Frame3D.getThumbnail()}:
 * that {@code Thumbnail} is a single-parented {@code Component3D} already owned by
 * the taskbar, so reparenting it onto the HUD would steal it from the bar. See
 * {@link WindowSwitcherPanel}.</p>
 *
 * <p>{@link #getPluginRoot()} returns null: the switcher is parented to the HUD
 * layer rather than the scene root, so it inherits the layer's
 * perspective-compensated front pose. When {@link DesktopHudPlugin} is not
 * running the overlay is skipped with a warning, but the MRU tracking and the
 * keystrokes still work (commit still brings the selected frame to front).</p>
 */
public class WindowSwitcherPlugin implements SceneManagerPlugin {

    private static final Logger logger =
            Logger.getLogger("lg.scenemanager.switcher");

    /** Idle delay before an open selection commits itself, in milliseconds. */
    static final int COMMIT_DELAY_MS = 600;

    /** Fractional HUD position of the card: centred, in the upper third. */
    private static final float CARD_FX = 0.5f;
    private static final float CARD_FY = 0.30f;

    /** The MRU + cycle state machine, shared with the 2D desktop's model. */
    private final WindowCycler<Frame3D> cycler = new WindowCycler<>();

    /** The frames currently on the desktop, in open order. */
    private final LinkedHashSet<Frame3D> frames = new LinkedHashSet<>();

    private WindowSwitcher3D switcher;
    private DesktopHudLayer layer;
    private Timer commitTimer;

    private LgEventListener frameListener;
    private LgEventListener toFrontListener;
    private LgEventListener keyListener;

    public WindowSwitcherPlugin() {
    }

    @Override
    public void initialize(SceneControl sceneControl) {
        frames.clear();

        commitTimer = new Timer(COMMIT_DELAY_MS, e -> commitSelection());
        commitTimer.setRepeats(false);

        installFrameListener();
        installToFrontListener();
        installKeyListener();

        layer = DesktopHudPlugin.layer();
        if (layer == null) {
            logger.warning("DesktopHudPlugin is not running; the window switcher "
                    + "overlay is disabled and cycling commits without a visible "
                    + "card. Register WindowSwitcherPlugin after DesktopHudPlugin "
                    + "in glassy.lgcfg.");
            return;
        }
        try {
            switcher = new WindowSwitcher3D();
            layer.addChild(switcher);
            layer.placeAt(switcher, CARD_FX, CARD_FY);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "could not mount the window switcher", t);
        }
    }

    @Override
    public Component3D getPluginRoot() {
        // The switcher is attached to the HUD layer, not the scene root.
        return null;
    }

    @Override
    public void destroy() {
        if (commitTimer != null) {
            commitTimer.stop();
            commitTimer = null;
        }
        LgEventConnector connector = LgEventConnector.getLgEventConnector();
        removeQuietly(connector, AppContainer.class, frameListener);
        removeQuietly(connector, Frame3D.class, toFrontListener);
        removeQuietly(connector, LgEventSource.ALL_SOURCES, keyListener);
        frameListener = null;
        toFrontListener = null;
        keyListener = null;

        WindowSwitcher3D s = switcher;
        if (s != null) {
            try {
                s.hide();
                s.dispose();
            } catch (Throwable t) {
                logger.log(Level.WARNING, "window switcher dispose failed", t);
            }
            if (layer != null) {
                layer.removeChild(s);
            }
            switcher = null;
        }

        cycler.cancel();
        frames.clear();
        layer = null;
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

    /** Tracks a newly opened frame as the most recently used. */
    void frameAdded(Frame3D frame) {
        if (frame == null) {
            return;
        }
        frames.add(frame);
        cycler.touch(frame);
    }

    /** Forgets a closed frame; abandons the session if it was cycling. */
    void frameRemoved(Frame3D frame) {
        if (frame == null) {
            return;
        }
        frames.remove(frame);
        cycler.forget(frame);
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

    /** Records the frame that just came forward as the most recently used. */
    void frameToFront(Frame3D frame) {
        if (frame == null) {
            return;
        }
        cycler.touch(frame);
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

    /** Applies one switcher keystroke. Package-private for tests. */
    void handleKey(KeyEvent e) {
        WindowSwitcherKeys.Kind kind = WindowSwitcherKeys.resolve(
                e.isControlDown() || e.isMetaDown(), e.isAltDown(),
                e.isShiftDown(), e.getKeyCode());
        switch (kind) {
            case NEXT -> trigger(true);
            case PREVIOUS -> trigger(false);
            case NONE -> {
                // Not a switcher keystroke; leave it to the application.
            }
        }
    }

    /**
     * One trigger press: opens the switcher if it is idle (giving up when there
     * are fewer than two windows), then steps the highlight forward or backward
     * and restarts the idle-commit timer. Mirrors the 2D overlay exactly.
     */
    void trigger(boolean forward) {
        if (!cycler.isActive() && !cycler.open(new ArrayList<>(frames))) {
            return;
        }
        if (forward) {
            cycler.advance();
        } else {
            cycler.advanceBack();
        }
        if (commitTimer != null) {
            commitTimer.restart();
        }
        refresh();
    }

    /** Commits the highlighted frame (if any) to front and hides the card. */
    void commitSelection() {
        if (commitTimer != null) {
            commitTimer.stop();
        }
        Frame3D frame = cycler.commit();
        if (frame != null) {
            try {
                frame.postEvent(new Component3DToFrontEvent());
            } catch (Throwable t) {
                logger.log(Level.WARNING, "could not bring the selected frame to front", t);
            }
        }
        refresh();
    }

    /** Abandons the current selection and hides the card. Package-private for tests. */
    void cancelSelection() {
        if (commitTimer != null) {
            commitTimer.stop();
        }
        cycler.cancel();
        refresh();
    }

    /** Syncs the overlay's visibility and rows to the cycle session. */
    private void refresh() {
        if (switcher == null) {
            return;
        }
        if (cycler.isActive()) {
            switcher.show(namesOf(cycler.items()), cycler.selectedIndex());
        } else {
            switcher.hide();
        }
    }

    private static List<String> namesOf(List<Frame3D> items) {
        List<String> names = new ArrayList<>(items.size());
        for (Frame3D frame : items) {
            names.add(frame.getName());
        }
        return names;
    }

    private static void removeQuietly(LgEventConnector connector, Class sourceClass,
            LgEventListener listener) {
        if (listener == null) {
            return;
        }
        try {
            connector.removeListener(sourceClass, listener);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "could not remove a window switcher listener", t);
        }
    }
}
