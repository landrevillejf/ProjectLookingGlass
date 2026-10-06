/**
 * Project Looking Glass
 *
 * Copyright (c) 2026 Project Looking Glass contributors.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

import gnu.x11.Display;
import gnu.x11.Input;
import gnu.x11.Window;
import gnu.x11.extension.XTest;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JComponent;

/**
 * Forwards Swing input on a {@link SwingCompositedWindowSink}'s component into
 * the real X11 window that backs a composited client, when lg3d runs as its own
 * compositor on the <b>2D</b> desktop.
 *
 * <p>This is the 2D sibling of {@link X11InputForwarder}. The Composite
 * extension redirects a client's <em>rendering</em> into an offscreen pixmap
 * (read into the Swing canvas) but not its <em>input</em>: the window still
 * occupies its original rectangle in the X tree and still receives events there.
 * Because the 2D desktop shows the window inside a {@code Desktop2DWindow} at an
 * arbitrary Swing position, physical input lands on the Swing component, not on
 * the client. This forwarder re-injects it via XTest.</p>
 *
 * <p>Unlike the 3D path there is no scene-graph pick to invert: the Swing canvas
 * is a 1:1 pixel copy of the client window, so a component-local point
 * {@code (px, py)} maps straight to the window pixel {@code (px, py)} and hence
 * to the root-relative injection point {@code (winX + px, winY + py)}. Key
 * mapping reuses {@link X11InputForwarder#vkToKeysym(int)} (already covered by
 * {@code X11InputForwarderKeysymTest}); the keysym&rarr;keycode resolution is
 * extracted here as a pure {@link #keysymToKeycode(int[], int, int, int)} so it
 * is unit-testable with no live {@link Display}.</p>
 *
 * <h3>Threading</h3>
 * <p>Listeners fire on the EDT. As in the 3D forwarder, only <em>non-round-trip</em>
 * sends (fake input, set-input-focus) are issued, followed by
 * {@link Display#flush()}; no reply-reading request and no {@code check_error()}
 * is called from this thread.</p>
 *
 * @see SwingCompositedWindowSink
 * @see X11InputForwarder
 */
final class SwingX11InputForwarder
        implements MouseListener, MouseMotionListener, MouseWheelListener,
        KeyListener {

    private static final Logger logger = Logger.getLogger("lg.x11.input.swing");

    /** X button numbers for the vertical wheel (X11 convention). */
    private static final int WHEEL_UP_BUTTON = 4;
    private static final int WHEEL_DOWN_BUTTON = 5;

    private final X11Compositor compositor;
    private final X11Client client;
    private final Display display;
    private final Window root;
    private final JComponent host;

    private final int[] absCoords = new int[2];
    private int lastAbsX = Integer.MIN_VALUE;
    private int lastAbsY = Integer.MIN_VALUE;

    /**
     * Attaches a forwarder to {@code host}, the Swing component painting the
     * composited client.
     *
     * @param compositor the active compositor (supplies XTest, Display, root)
     * @param client     the X window backing the composited canvas
     * @param host       the Swing component that receives physical input
     */
    SwingX11InputForwarder(X11Compositor compositor, X11Client client,
            JComponent host) {
        this.compositor = compositor;
        this.client = client;
        this.display = compositor.getDisplay();
        this.root = compositor.getRoot();
        this.host = host;
        host.addMouseListener(this);
        host.addMouseMotionListener(this);
        host.addMouseWheelListener(this);
        host.addKeyListener(this);
        host.setFocusable(true);
        logger.info("X11 Swing input forwarder attached to window 0x"
            + Integer.toHexString(client.id) + " (XTest "
            + (compositor.getXTest() != null ? "available" : "UNAVAILABLE") + ")");
    }

    /** Detaches every listener installed by the constructor. */
    void detach() {
        host.removeMouseListener(this);
        host.removeMouseMotionListener(this);
        host.removeMouseWheelListener(this);
        host.removeKeyListener(this);
    }

    // ------------------------------------------------------------------
    // Pointer
    // ------------------------------------------------------------------

    @Override
    public void mouseMoved(MouseEvent e) {
        XTest xtest = compositor.getXTest();
        if (xtest == null) {
            return;
        }
        if (computeRoot(e.getX(), e.getY(), absCoords)) {
            warpPointer(xtest, absCoords[0], absCoords[1]);
        }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        mouseMoved(e);
    }

    @Override
    public void mousePressed(MouseEvent e) {
        focusClient();
        XTest xtest = compositor.getXTest();
        if (xtest == null) {
            return;
        }
        int button = mapAwtButton(e.getButton());
        if (button <= 0) {
            return;
        }
        if (computeRoot(e.getX(), e.getY(), absCoords)) {
            warpPointer(xtest, absCoords[0], absCoords[1]);
        }
        sendButton(xtest, button, true);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        XTest xtest = compositor.getXTest();
        if (xtest == null) {
            return;
        }
        int button = mapAwtButton(e.getButton());
        if (button <= 0) {
            return;
        }
        if (computeRoot(e.getX(), e.getY(), absCoords)) {
            warpPointer(xtest, absCoords[0], absCoords[1]);
        }
        sendButton(xtest, button, false);
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        // The client synthesises a click from the forwarded press+release.
    }

    @Override
    public void mouseEntered(MouseEvent e) {
        focusClient();
        lastAbsX = Integer.MIN_VALUE;
        lastAbsY = Integer.MIN_VALUE;
    }

    @Override
    public void mouseExited(MouseEvent e) {
        // no-op: focus stays until another window takes it
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        XTest xtest = compositor.getXTest();
        if (xtest == null) {
            return;
        }
        int rotation = e.getWheelRotation();
        if (rotation == 0) {
            return;
        }
        if (computeRoot(e.getX(), e.getY(), absCoords)) {
            warpPointer(xtest, absCoords[0], absCoords[1]);
        }
        int button = (rotation < 0) ? WHEEL_UP_BUTTON : WHEEL_DOWN_BUTTON;
        int clicks = Math.abs(rotation);
        for (int i = 0; i < clicks; i++) {
            sendButton(xtest, button, true);
            sendButton(xtest, button, false);
        }
    }

    // ------------------------------------------------------------------
    // Keyboard
    // ------------------------------------------------------------------

    @Override
    public void keyPressed(KeyEvent e) {
        sendKey(e.getKeyCode(), true);
    }

    @Override
    public void keyReleased(KeyEvent e) {
        sendKey(e.getKeyCode(), false);
    }

    @Override
    public void keyTyped(KeyEvent e) {
        // Ignored: the server derives the character from the forwarded keycode
        // plus the forwarded modifier state, so injecting KEY_TYPED doubles it.
    }

    private void sendKey(int vk, boolean press) {
        XTest xtest = compositor.getXTest();
        if (xtest == null) {
            return;
        }
        int keysym = X11InputForwarder.vkToKeysym(vk);
        if (keysym == 0) {
            return;
        }
        int keycode = resolveKeycode(keysym);
        if (keycode < 0) {
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("keysym 0x" + Integer.toHexString(keysym)
                    + " absent from server keymap; key not forwarded");
            }
            return;
        }
        try {
            xtest.fake_key_event(keycode, press, 0);
            display.flush();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "key forwarding failed for window 0x"
                + Integer.toHexString(client.id), t);
        }
    }

    // ------------------------------------------------------------------
    // Shared injection helpers
    // ------------------------------------------------------------------

    private void focusClient() {
        try {
            client.set_input_focus();
            display.flush();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "set_input_focus failed for window 0x"
                + Integer.toHexString(client.id), t);
        }
    }

    private void sendButton(XTest xtest, int button, boolean press) {
        try {
            xtest.fake_button_event(button, press, 0);
            display.flush();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "button forwarding failed for window 0x"
                + Integer.toHexString(client.id), t);
        }
    }

    private void warpPointer(XTest xtest, int absX, int absY) {
        if (absX == lastAbsX && absY == lastAbsY) {
            return;
        }
        lastAbsX = absX;
        lastAbsY = absY;
        try {
            xtest.fake_motion_event(root, absX, absY, false, 0);
            display.flush();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "pointer warp failed for window 0x"
                + Integer.toHexString(client.id), t);
        }
    }

    /** Computes the root-relative injection point for a component-local pixel. */
    private boolean computeRoot(int px, int py, int[] out) {
        return mapPanelToRoot(px, py, client.getX(), client.getY(), out);
    }

    /** Resolves a keysym against the live server keymap. */
    private int resolveKeycode(int keysym) {
        Input input = display.input;
        if (input == null) {
            return -1;
        }
        return keysymToKeycode(input.keysyms, input.keysyms_per_keycode,
            input.min_keycode, keysym);
    }

    // ------------------------------------------------------------------
    // Pure, headless-testable seams
    // ------------------------------------------------------------------

    /**
     * Maps a component-local pixel to an absolute root-relative injection point.
     * The Swing canvas is a 1:1 copy of the client window, so the local offset is
     * already a window pixel; the window's root-relative origin is added. Negative
     * local offsets (events outside the canvas) are clamped to zero.
     *
     * @param out receives {@code [absX, absY]}
     * @return always true (ints cannot be NaN; geometry is clamped, not rejected)
     */
    static boolean mapPanelToRoot(int px, int py, int winX, int winY, int[] out) {
        int cx = Math.max(0, px);
        int cy = Math.max(0, py);
        out[0] = winX + cx;
        out[1] = winY + cy;
        return true;
    }

    /**
     * Maps an AWT {@code MouseEvent.getButton()} value to an X button number.
     * AWT and X agree on 1=left, 2=middle, 3=right; anything else (including
     * {@code NOBUTTON}) maps to -1 (do not inject).
     */
    static int mapAwtButton(int awtButton) {
        switch (awtButton) {
            case MouseEvent.BUTTON1: return Input.BUTTON1; // 1
            case MouseEvent.BUTTON2: return Input.BUTTON2; // 2
            case MouseEvent.BUTTON3: return Input.BUTTON3; // 3
            default:                 return -1;
        }
    }

    /**
     * Resolves an X keysym to a keycode from a server keymap, accounting for
     * {@code keysyms_per_keycode} (Escher's own resolver ignores the per-keycode
     * count and is wrong for modern servers). Pure so it is testable with no
     * {@link Display}.
     *
     * @param syms         the flat keysym table ({@code Input.keysyms})
     * @param per          keysyms per keycode ({@code Input.keysyms_per_keycode})
     * @param minKeycode   the server's minimum keycode
     * @param keysym       the keysym to find
     * @return the keycode, or -1 if absent or the table is unusable
     */
    static int keysymToKeycode(int[] syms, int per, int minKeycode, int keysym) {
        if (syms == null || per <= 0) {
            return -1;
        }
        for (int i = 0; i < syms.length; i++) {
            if (syms[i] == keysym) {
                return minKeycode + (i / per);
            }
        }
        return -1;
    }
}
