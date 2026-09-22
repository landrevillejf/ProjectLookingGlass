/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
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

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import javax.swing.Timer;

import org.jdesktop.lg3d.scenemanager.utils.appcontainer.AppContainer;
import org.jdesktop.lg3d.scenemanager.utils.event.Frame3DAddedEvent;
import org.jdesktop.lg3d.scenemanager.utils.event.Frame3DRemovedEvent;
import org.jdesktop.lg3d.wg.Component3D;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.event.Component3DToFrontEvent;
import org.jdesktop.lg3d.wg.event.KeyEvent3D;
import org.jdesktop.lg3d.wg.event.LgEvent;
import org.jdesktop.lg3d.wg.event.LgEventConnector;
import org.jdesktop.lg3d.wg.event.LgEventListener;
import org.jdesktop.lg3d.wg.switcher.SwitcherController;
import org.jdesktop.lg3d.wg.switcher.SwitcherItem;

/**
 * Wires the desktop-independent switcher machinery
 * ({@link SwitcherController}, {@link Frame3DSwitcherModel}) to the 3D desktop:
 * it tracks the open {@link Frame3D} windows through the same
 * {@link Frame3DAddedEvent}/{@link Frame3DRemovedEvent}/{@link Component3DToFrontEvent}
 * stream the taskbar uses, drives a {@link SwitcherOverlay3D}, and binds the
 * Alt+Tab trigger on the scene root.
 *
 * <p>This is the Java 3D glue around the headless-testable
 * {@link Frame3DSwitcherModel}; it holds no ordering logic of its own. Install
 * it once with {@link #install(Component3D)} on the scene-manager root.</p>
 *
 * <p>Cycling mirrors the 2D/Swing switcher: Alt+Tab opens and steps forward,
 * Shift+Alt+Tab steps back, releasing Alt (or Enter) commits, Escape cancels.
 * Because a windowed dev-mode lg3d cannot always observe the Alt key release,
 * an idle {@link Timer} also commits the selection a beat after the last
 * keystroke, exactly as {@code SwitcherOverlay} does.</p>
 */
public final class ApplicationSwitcher3D {

    private static final Logger logger
            = Logger.getLogger("lg.scenemanager.switcher");

    /** Idle grace before the highlighted window is committed automatically. */
    private static final int AUTO_COMMIT_MS = 700;

    private final List<Frame3D> open = new ArrayList<>();
    private final Frame3DSwitcherModel model;
    private final SwitcherController controller;
    private final SwitcherOverlay3D overlay;
    private final Timer idleCommit;

    private Component3D sceneRoot;
    private LgEventListener addedListener;
    private LgEventListener removedListener;
    private LgEventListener toFrontListener;
    private LgEventListener keyListener;
    private boolean installed;

    public ApplicationSwitcher3D() {
        Frame3DSwitcherModel.WindowSource source = new Frame3DSwitcherModel.WindowSource() {
            @Override
            public List<SwitcherItem> openWindows() {
                List<SwitcherItem> items = new ArrayList<>(open.size());
                for (Frame3D frame : open) {
                    items.add(new SwitcherItem(frame, frame.getName(), null, null));
                }
                return items;
            }

            @Override
            public void focusWindow(Object window) {
                if (window instanceof Frame3D) {
                    ((Frame3D) window).postEvent(new Component3DToFrontEvent());
                }
            }
        };
        model = new Frame3DSwitcherModel(source);
        controller = new SwitcherController(model);
        overlay = new SwitcherOverlay3D();
        idleCommit = new Timer(AUTO_COMMIT_MS, e -> commit());
        idleCommit.setRepeats(false);
    }

    /**
     * Starts tracking windows and binds the trigger. Safe to call once; further
     * calls are ignored until {@link #uninstall()}.
     *
     * @param root the scene-manager root the overlay is added to and the Alt+Tab
     *             key listener is bound on
     */
    public synchronized void install(Component3D root) {
        if (installed || root == null) {
            return;
        }
        sceneRoot = root;
        LgEventConnector connector = LgEventConnector.getLgEventConnector();

        addedListener = new LgEventListener() {
            @Override
            public void processEvent(LgEvent evt) {
                Frame3D frame = ((Frame3DAddedEvent) evt).getFrame3D();
                if (frame != null && !open.contains(frame)) {
                    open.add(frame);
                    model.touch(frame);
                }
            }
            @Override
            public Class[] getTargetEventClasses() {
                return new Class[] { Frame3DAddedEvent.class };
            }
        };
        removedListener = new LgEventListener() {
            @Override
            public void processEvent(LgEvent evt) {
                Frame3D frame = ((Frame3DRemovedEvent) evt).getFrame3D();
                if (frame != null) {
                    open.remove(frame);
                    model.forget(frame);
                }
            }
            @Override
            public Class[] getTargetEventClasses() {
                return new Class[] { Frame3DRemovedEvent.class };
            }
        };
        toFrontListener = new LgEventListener() {
            @Override
            public void processEvent(LgEvent evt) {
                LgEvent src = evt;
                if (src.getSource() instanceof Frame3D) {
                    model.touch(src.getSource());
                }
            }
            @Override
            public Class[] getTargetEventClasses() {
                return new Class[] { Component3DToFrontEvent.class };
            }
        };
        keyListener = new LgEventListener() {
            @Override
            public void processEvent(LgEvent evt) {
                handleKey((KeyEvent3D) evt);
            }
            @Override
            public Class[] getTargetEventClasses() {
                return new Class[] { KeyEvent3D.class };
            }
        };

        connector.addListener(AppContainer.class, addedListener);
        connector.addListener(AppContainer.class, removedListener);
        connector.addListener(Frame3D.class, toFrontListener);
        root.addListener(keyListener);
        root.addChild(overlay);
        installed = true;
        logger.info("3D application switcher installed (Alt+Tab)");
    }

    /** Stops tracking, unbinds the trigger and removes the overlay. */
    public synchronized void uninstall() {
        if (!installed) {
            return;
        }
        LgEventConnector connector = LgEventConnector.getLgEventConnector();
        connector.removeListener(AppContainer.class, addedListener);
        connector.removeListener(AppContainer.class, removedListener);
        connector.removeListener(Frame3D.class, toFrontListener);
        if (sceneRoot != null) {
            sceneRoot.removeListener(keyListener);
            sceneRoot.removeChild(overlay);
        }
        idleCommit.stop();
        overlay.hide();
        open.clear();
        installed = false;
    }

    private void handleKey(KeyEvent3D ke) {
        int code = ke.getKeyCode();
        if (ke.isPressed()) {
            int mask = ke.getAwtEvent().getModifiersEx();
            boolean alt = (mask & InputEvent.ALT_DOWN_MASK) != 0;
            boolean shift = (mask & InputEvent.SHIFT_DOWN_MASK) != 0;
            if (code == KeyEvent.VK_TAB && alt) {
                advance(shift);
            } else if (code == KeyEvent.VK_ESCAPE) {
                cancel();
            } else if (code == KeyEvent.VK_ENTER) {
                commit();
            }
        } else if (ke.isReleased() && code == KeyEvent.VK_ALT) {
            // Releasing Alt commits, like a real Alt+Tab.
            commit();
        }
    }

    private synchronized void advance(boolean back) {
        boolean wasActive = controller.isActive();
        boolean shown = back ? controller.advanceBack() : controller.advance();
        if (!shown) {
            // Fewer than two windows: nothing to cycle, matching Alt+Tab.
            overlay.hide();
            idleCommit.stop();
            return;
        }
        if (wasActive) {
            overlay.update(controller.getIndex());
        } else {
            overlay.show(controller.items(), controller.getIndex());
        }
        idleCommit.restart();
    }

    private synchronized void commit() {
        idleCommit.stop();
        if (!controller.isActive()) {
            overlay.hide();
            return;
        }
        controller.commit();
        overlay.hide();
    }

    private synchronized void cancel() {
        idleCommit.stop();
        if (!controller.isActive()) {
            return;
        }
        controller.cancel();
        overlay.hide();
    }
}
