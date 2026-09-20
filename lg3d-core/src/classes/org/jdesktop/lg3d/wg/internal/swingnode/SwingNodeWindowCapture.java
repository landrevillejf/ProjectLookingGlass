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
package org.jdesktop.lg3d.wg.internal.swingnode;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.AWTEvent;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.swing.JFrame;
import org.jdesktop.lg3d.wg.SwingNode;

/**
 * Global registry that lets a {@link SwingNode} capture and render the top-level
 * Swing windows an unmodified application creates - {@code JOptionPane} and
 * {@code JFileChooser} dialogs, custom {@code JDialog}/{@code JWindow} popups,
 * and a conventional app's own {@code JFrame}s - instead of letting them pop
 * onto the host desktop where the offscreen texture capture cannot reach them.
 *
 * <p>One {@link AWTEventListener} is installed lazily on the first registration
 * and watches every {@code WINDOW_EVENT_MASK} event in the JVM:
 * <ul>
 *   <li>A newly opened {@link JFrame} (other than a SwingNode's own hidden
 *       frame and the desktop host window) is handed to
 *       {@link CapturedFrameHost} so it becomes a real desktop window.</li>
 *   <li>A newly opened {@code JDialog}/{@code JWindow} is attributed to the
 *       SwingNode that owns it (via the owner chain, else the last-active node),
 *       parked off-screen, and recorded as a centred overlay that the node paints
 *       into its texture and routes input to. Modal semantics are preserved
 *       because a modal dialog runs its own secondary EDT loop, which processes
 *       the events the node's renderer dispatches on the EDT.</li>
 * </ul>
 *
 * <p>This class is an implementation detail of {@link SwingNode} and must not be
 * instantiated by users; every member is static.
 */
public final class SwingNodeWindowCapture {

    private static final Logger logger =
            Logger.getLogger("lg.wg.swingnode");

    /**
     * Off-screen parking location. Far enough out that a captured window is
     * never visible on any real display, yet still a valid peer so Swing layout
     * and painting keep working.
     */
    private static final int PARK_X = -32000;
    private static final int PARK_Y = -32000;

    /** SwingNode hidden frames that must never themselves be captured. */
    private static final Map<Window, SwingNode> HIDDEN_FRAMES =
            Collections.synchronizedMap(new IdentityHashMap<Window, SwingNode>());

    /** Captured overlay windows per node, in paint order (topmost last). */
    private static final Map<SwingNode, List<Capture>> CAPTURES =
            Collections.synchronizedMap(new IdentityHashMap<SwingNode, List<Capture>>());

    /** Reverse index: a captured window back to the node presenting it. */
    private static final Map<Window, SwingNode> WINDOW_TO_NODE =
            Collections.synchronizedMap(new IdentityHashMap<Window, SwingNode>());

    /** Full-bleed captured frames (a JFrame presented as its own window). */
    private static final Map<Window, SwingNode> FRAME_TO_NODE =
            Collections.synchronizedMap(new IdentityHashMap<Window, SwingNode>());

    /** Last node the pointer entered/pressed, used to attribute owner-less dialogs. */
    private static volatile SwingNode lastActiveNode;

    private static boolean hookInstalled;

    private SwingNodeWindowCapture() {
    }

    /** A single captured window and how the owning node should present it. */
    private static final class Capture {
        final Window window;
        /** True when the window fills the whole texture at origin (0,0). */
        final boolean fullBleed;

        Capture(Window window, boolean fullBleed) {
            this.window = window;
            this.fullBleed = fullBleed;
        }
    }

    /**
     * The resolved destination for a forwarded input event: the top-level Swing
     * {@link Component} to dispatch against and the pointer position in that
     * component's own coordinate space.
     */
    public static final class InputTarget {
        public final Component root;
        public final Point local;

        InputTarget(Component root, Point local) {
            this.root = root;
            this.local = local;
        }
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------

    /**
     * Registers a SwingNode's hidden frame so its own window is never captured
     * and so dialogs it owns can be attributed back to the node. Installs the
     * global window hook on the first call.
     */
    public static void registerHiddenFrame(Window hiddenFrame, SwingNode node) {
        if (hiddenFrame == null || node == null) {
            return;
        }
        HIDDEN_FRAMES.put(hiddenFrame, node);
        installHook();
    }

    /** Unregisters a SwingNode's hidden frame (called from {@code dispose()}). */
    public static void unregisterHiddenFrame(Window hiddenFrame) {
        if (hiddenFrame == null) {
            return;
        }
        HIDDEN_FRAMES.remove(hiddenFrame);
    }

    /**
     * Records the node the pointer is currently over so an owner-less dialog
     * (e.g. {@code JOptionPane.showConfirmDialog(null, ...)}) can be attributed
     * to a window instead of escaping to the host desktop.
     */
    public static void setLastActiveNode(SwingNode node) {
        lastActiveNode = node;
    }

    /**
     * Registers {@code window} as a full-bleed capture of {@code node}: the node
     * paints it as its entire texture at origin (0,0) and routes all input to
     * it. Used by {@link CapturedFrameHost} to present a conventional app's
     * {@code JFrame} as a desktop window.
     */
    public static void registerFullBleed(SwingNode node, Window window) {
        if (node == null || window == null) {
            return;
        }
        addCapture(node, new Capture(window, true));
        WINDOW_TO_NODE.put(window, node);
        FRAME_TO_NODE.put(window, node);
        parkOffScreen(window);
        node.requestRecapture();
    }

    /** Removes every capture associated with {@code node} (called on dispose). */
    public static void releaseNode(SwingNode node) {
        if (node == null) {
            return;
        }
        List<Capture> caps;
        synchronized (CAPTURES) {
            caps = CAPTURES.remove(node);
        }
        if (caps != null) {
            for (Capture c : caps) {
                WINDOW_TO_NODE.remove(c.window);
                FRAME_TO_NODE.remove(c.window);
            }
        }
    }

    private static void addCapture(SwingNode node, Capture capture) {
        synchronized (CAPTURES) {
            List<Capture> caps = CAPTURES.get(node);
            if (caps == null) {
                caps = new ArrayList<Capture>();
                CAPTURES.put(node, caps);
            }
            caps.add(capture);
        }
    }

    // ------------------------------------------------------------------
    // Queries used by SwingNode (painting) and SwingNodeRenderer (input)
    // ------------------------------------------------------------------

    /**
     * Returns the windows {@code node} should paint as overlays, in paint order
     * (topmost last). Never null; safe to iterate on the EDT.
     */
    public static List<Window> getCaptured(SwingNode node) {
        synchronized (CAPTURES) {
            List<Capture> caps = CAPTURES.get(node);
            if (caps == null || caps.isEmpty()) {
                return Collections.emptyList();
            }
            List<Window> out = new ArrayList<Window>(caps.size());
            for (Capture c : caps) {
                out.add(c.window);
            }
            return out;
        }
    }

    /**
     * Returns the top-left origin, in the hosting panel's pixel space, where
     * {@code window} should be painted. Full-bleed captures sit at (0,0);
     * dialogs are centred within the {@code panelW x panelH} area and clamped so
     * they never start at a negative offset.
     */
    public static Point getOverlayOrigin(SwingNode node, Window window,
                                         int panelW, int panelH) {
        Capture cap = findCapture(node, window);
        if (cap != null && cap.fullBleed) {
            return new Point(0, 0);
        }
        int ww = window.getWidth();
        int wh = window.getHeight();
        int x = Math.max(0, (panelW - ww) / 2);
        int y = Math.max(0, (panelH - wh) / 2);
        return new Point(x, y);
    }

    /**
     * Returns the node presenting {@code window} (or any window up its owner
     * chain) so the SwingNode repaint manager can mark it dirty when a captured
     * dialog repaints. Returns {@code null} when the component is unrelated.
     */
    public static SwingNode getNodeForWindow(Window window) {
        if (window == null) {
            return null;
        }
        SwingNode node = WINDOW_TO_NODE.get(window);
        if (node != null) {
            return node;
        }
        return FRAME_TO_NODE.get(window);
    }

    /**
     * Resolves where a forwarded input event aimed at panel-space {@code panelPos}
     * should go. When the node has a captured modal dialog, ALL input is routed
     * to it (modal semantics). Otherwise a captured overlay whose rect contains
     * {@code panelPos} receives the event with local coordinates. Returns
     * {@code null} when no captured window applies, meaning the caller should
     * fall back to the node's own hidden frame.
     */
    public static InputTarget resolveInputTarget(SwingNode node, Point panelPos,
                                                 int panelW, int panelH) {
        List<Capture> caps;
        synchronized (CAPTURES) {
            caps = CAPTURES.get(node);
            if (caps == null || caps.isEmpty()) {
                return null;
            }
            caps = new ArrayList<Capture>(caps);
        }
        // Topmost modal dialog wins outright.
        for (int i = caps.size() - 1; i >= 0; i--) {
            Capture c = caps.get(i);
            if (isModal(c.window)) {
                Point origin = getOverlayOrigin(node, c.window, panelW, panelH);
                return new InputTarget(c.window,
                        new Point(panelPos.x - origin.x, panelPos.y - origin.y));
            }
        }
        // Otherwise the topmost overlay under the pointer.
        for (int i = caps.size() - 1; i >= 0; i--) {
            Capture c = caps.get(i);
            Point origin = getOverlayOrigin(node, c.window, panelW, panelH);
            int ww = c.window.getWidth();
            int wh = c.window.getHeight();
            if (panelPos.x >= origin.x && panelPos.x < origin.x + ww
                    && panelPos.y >= origin.y && panelPos.y < origin.y + wh) {
                return new InputTarget(c.window,
                        new Point(panelPos.x - origin.x, panelPos.y - origin.y));
            }
        }
        return null;
    }

    private static Capture findCapture(SwingNode node, Window window) {
        synchronized (CAPTURES) {
            List<Capture> caps = CAPTURES.get(node);
            if (caps != null) {
                for (Capture c : caps) {
                    if (c.window == window) {
                        return c;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isModal(Window w) {
        return (w instanceof Dialog) && ((Dialog) w).isModal();
    }

    // ------------------------------------------------------------------
    // Global window hook
    // ------------------------------------------------------------------

    private static synchronized void installHook() {
        if (hookInstalled) {
            return;
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
            public void eventDispatched(java.awt.AWTEvent event) {
                if (!(event instanceof WindowEvent)) {
                    return;
                }
                WindowEvent we = (WindowEvent) event;
                switch (we.getID()) {
                    case WindowEvent.WINDOW_OPENED:
                    case WindowEvent.WINDOW_ACTIVATED:
                        onWindowOpened(we.getWindow());
                        break;
                    case WindowEvent.WINDOW_CLOSED:
                        onWindowClosed(we.getWindow());
                        break;
                    default:
                        break;
                }
            }
        }, AWTEvent.WINDOW_EVENT_MASK);
        hookInstalled = true;
    }

    private static void onWindowOpened(Window w) {
        if (w == null) {
            return;
        }
        // Never capture a SwingNode's own hidden frame or the desktop host.
        if (HIDDEN_FRAMES.containsKey(w) || isDesktopHost(w)) {
            return;
        }
        // Already presented? Nothing to do (WINDOW_ACTIVATED is a safety net).
        if (WINDOW_TO_NODE.containsKey(w) || FRAME_TO_NODE.containsKey(w)) {
            return;
        }
        if (w instanceof JFrame) {
            try {
                CapturedFrameHost.present((JFrame) w);
            } catch (Throwable t) {
                logger.warning("Failed to present captured JFrame: " + t);
            }
            return;
        }
        // JDialog / JWindow: attribute to a node and park as an overlay.
        SwingNode node = attribute(w);
        if (node == null) {
            return;
        }
        addCapture(node, new Capture(w, false));
        WINDOW_TO_NODE.put(w, node);
        parkOffScreen(w);
        node.requestRecapture();
    }

    private static void onWindowClosed(Window w) {
        if (w == null) {
            return;
        }
        SwingNode node = WINDOW_TO_NODE.remove(w);
        boolean wasFrame = FRAME_TO_NODE.remove(w) != null;
        if (node == null) {
            return;
        }
        synchronized (CAPTURES) {
            List<Capture> caps = CAPTURES.get(node);
            if (caps != null) {
                caps.removeIf(c -> c.window == w);
                if (caps.isEmpty()) {
                    CAPTURES.remove(node);
                }
            }
        }
        if (!wasFrame) {
            node.requestRecapture();
        }
    }

    /**
     * Walks {@code w}'s owner chain to the SwingNode that owns it: a registered
     * hidden frame, or a captured JFrame presented by {@link CapturedFrameHost}.
     * Falls back to the last-active node for owner-less transient dialogs.
     */
    private static SwingNode attribute(Window w) {
        Window o = w.getOwner();
        while (o != null) {
            SwingNode n = HIDDEN_FRAMES.get(o);
            if (n != null) {
                return n;
            }
            n = FRAME_TO_NODE.get(o);
            if (n != null) {
                return n;
            }
            o = o.getOwner();
        }
        return lastActiveNode;
    }

    private static void parkOffScreen(Window w) {
        try {
            w.setLocation(PARK_X, PARK_Y);
        } catch (Throwable t) {
            // A window that cannot be relocated simply stays where it is; the
            // capture still paints it into the texture.
        }
    }

    /**
     * True when {@code w} is the desktop's own host window (it contains the
     * Java3D {@code Canvas3D}), so the shell itself is never captured.
     */
    private static boolean isDesktopHost(Window w) {
        if (!(w instanceof Container)) {
            return false;
        }
        return containsCanvas3D((Container) w);
    }

    private static boolean containsCanvas3D(Container c) {
        for (Component child : c.getComponents()) {
            if (isCanvas3D(child)) {
                return true;
            }
            if (child instanceof Container && containsCanvas3D((Container) child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCanvas3D(Component child) {
        // Avoid a hard compile-time dependency on the Java3D canvas type from
        // this internal helper; a name check is sufficient and robust.
        for (Class<?> k = child.getClass(); k != null; k = k.getSuperclass()) {
            if ("org.jogamp.java3d.Canvas3D".equals(k.getName())) {
                return true;
            }
        }
        return false;
    }
}
