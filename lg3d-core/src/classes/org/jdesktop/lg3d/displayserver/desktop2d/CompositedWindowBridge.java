/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.SwingUtilities;

import org.jdesktop.lg3d.displayserver.nativewindow.x11.WindowLifecycleListener;

/**
 * The missing link that makes <em>native X11 applications appear inside the
 * conventional 2D desktop</em>. It adapts the window manager's
 * {@link WindowLifecycleListener} notifications (fired on the X event thread by
 * {@code X11WindowManager}) onto the desktop-side {@link Desktop2DCompositorHost}
 * controller (which must run on the Swing EDT), so that:
 *
 * <ul>
 *   <li>a mapped client window opens a {@link Desktop2DWindow} whose content is
 *       the composited Swing surface;</li>
 *   <li>resize / retitle / activate / unmap notifications adjust or close it.</li>
 * </ul>
 *
 * <p>The pixel path is: X server redirect → {@code CompositeWindowImageLoader}
 * (a {@code WindowPixelSource}) → {@code CompositedWindowPipeline} →
 * {@code SwingCompositedWindowSink} → the {@code Desktop2DWindow} content pane.
 * This bridge carries only the <em>lifecycle</em> half; the surface itself is
 * produced by the {@code CompositedWindowHost} the controller was built with.</p>
 *
 * <p>Every callback is marshalled to the EDT through an injectable
 * {@code Consumer<Runnable>} so the forwarding logic is unit-testable headlessly
 * with a synchronous runner (the production default uses
 * {@link SwingUtilities#isEventDispatchThread()} / {@code invokeLater}). A
 * desktop-side failure is logged and swallowed: it must never propagate back onto
 * the X event thread and kill the window manager.</p>
 *
 * @see Desktop2DCompositorHost
 * @see WindowLifecycleListener
 */
public final class CompositedWindowBridge implements WindowLifecycleListener {

    private static final Logger logger =
        Logger.getLogger("lg.desktop2d.compositor.bridge");

    /**
     * The production EDT runner: runs immediately when already on the EDT,
     * otherwise queues via {@link SwingUtilities#invokeLater}.
     */
    public static final Consumer<Runnable> SWING_EDT = task -> {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    };

    /** A runner that executes the task synchronously on the calling thread. */
    public static final Consumer<Runnable> DIRECT = Runnable::run;

    private final Desktop2DCompositorHost controller;
    private final Consumer<Runnable> edtRunner;

    /**
     * Creates a bridge with the production Swing EDT runner.
     *
     * @param controller the desktop-side host controller to drive
     * @throws IllegalArgumentException if {@code controller} is null
     */
    public CompositedWindowBridge(Desktop2DCompositorHost controller) {
        this(controller, SWING_EDT);
    }

    /**
     * @param controller the desktop-side host controller to drive
     * @param edtRunner  marshals each forwarded call onto the EDT
     * @throws IllegalArgumentException if either argument is null
     */
    public CompositedWindowBridge(Desktop2DCompositorHost controller,
            Consumer<Runnable> edtRunner) {
        if (controller == null || edtRunner == null) {
            throw new IllegalArgumentException(
                "controller and edtRunner must both be non-null");
        }
        this.controller = controller;
        this.edtRunner = edtRunner;
    }

    /** The controller this bridge forwards to. */
    public Desktop2DCompositorHost getController() {
        return controller;
    }

    @Override
    public void windowMapped(int windowId, String title, int width, int height) {
        run(() -> controller.windowMapped(windowId, title, width, height),
            "windowMapped", windowId);
    }

    @Override
    public void windowResized(int windowId, int width, int height) {
        run(() -> controller.windowResized(windowId, width, height),
            "windowResized", windowId);
    }

    @Override
    public void windowRetitled(int windowId, String title) {
        run(() -> controller.windowRetitled(windowId, title),
            "windowRetitled", windowId);
    }

    @Override
    public void windowActivated(int windowId) {
        run(() -> controller.focusWindow(windowId), "windowActivated", windowId);
    }

    @Override
    public void windowUnmapped(int windowId) {
        run(() -> controller.windowUnmapped(windowId), "windowUnmapped", windowId);
    }

    /**
     * Marshals {@code task} onto the EDT, isolating any desktop-side failure so it
     * is logged and never propagates back to the X event thread.
     */
    private void run(Runnable task, String what, int windowId) {
        try {
            edtRunner.accept(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING,
                        "Composited window " + what + " failed for 0x"
                        + Integer.toHexString(windowId), e);
                }
            });
        } catch (RuntimeException e) {
            // The runner itself refused (e.g. EDT shut down); log and continue.
            logger.log(Level.WARNING,
                "Could not marshal " + what + " for 0x"
                + Integer.toHexString(windowId), e);
        }
    }
}
