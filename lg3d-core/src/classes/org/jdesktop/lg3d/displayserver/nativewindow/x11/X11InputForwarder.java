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
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jdesktop.lg3d.displayserver.nativewindow.NativeWindow3D;
import org.jdesktop.lg3d.wg.event.KeyEvent3D;
import org.jdesktop.lg3d.wg.event.LgEvent;
import org.jdesktop.lg3d.wg.event.LgEventListener;
import org.jdesktop.lg3d.wg.event.MouseButtonEvent3D;
import org.jdesktop.lg3d.wg.event.MouseEnteredEvent3D;
import org.jdesktop.lg3d.wg.event.MouseEvent3D;
import org.jdesktop.lg3d.wg.event.MouseMotionEvent3D;
import org.jdesktop.lg3d.wg.event.MouseWheelEvent3D;
import org.jogamp.vecmath.Point3f;

/**
 * Forwards lg3d 3D input events into the real X11 window that backs a
 * {@link NativeWindow3D} when lg3d runs as its own compositor.
 *
 * <h3>Why this is needed</h3>
 * <p>The Composite extension redirects a top-level window's <em>rendering</em>
 * into an offscreen pixmap (which {@link CompositeWindowImageLoader} reads into
 * a texture on a 3D quad). It does <em>not</em> redirect <em>input</em>: the
 * window still occupies its original rectangle in the X window tree and still
 * receives pointer/keyboard events there. Because lg3d displays the window as a
 * textured quad somewhere in 3D space — generally not at the window's real X/Y —
 * physical input lands on lg3d's Canvas3D, not on the client window.
 *
 * <p>lg3d's scene-graph picking turns that physical input into
 * {@link MouseEvent3D}/{@link KeyEvent3D} events targeted at the picked
 * {@link NativeWindow3D}. This listener converts them back into X input via the
 * XTest extension:
 * <ul>
 *   <li><b>Pointer</b>: the 3D intersection point (in the window body's local
 *       coordinates) is mapped to a pixel offset within the window, added to the
 *       window's root-relative X/Y, and injected with
 *       {@link XTest#fake_motion_event} followed by
 *       {@link XTest#fake_button_event}.</li>
 *   <li><b>Keyboard</b>: physical keycodes are replayed with
 *       {@link XTest#fake_key_event} on KEY_PRESSED/KEY_RELEASED. The X server
 *       applies its own keymap and modifier state to produce the character, so
 *       KEY_TYPED is deliberately ignored to avoid double input.</li>
 *   <li><b>Focus</b>: on mouse-enter the client window is given the X input
 *       focus ({@link Window#set_input_focus()}) so forwarded key events are
 *       delivered to it — mirroring the WM's focus-follows-pointer policy.</li>
 * </ul>
 *
 * <h3>Coordinate mapping</h3>
 * <p>The window body is centred on the {@link NativeWindow3D} local origin and
 * spans {@code getBodyWidth()} &times; {@code getBodyHeight()} physical units,
 * with local +X to the right and +Y up. A local intersection {@code (lx, ly)}
 * therefore maps to normalised window space as
 * {@code u = lx/bodyWidth + 0.5} (left&rarr;right) and
 * {@code v = 0.5 - ly/bodyHeight} (top&rarr;bottom), then to pixels
 * {@code (u*(pixelW-1), v*(pixelH-1))}. Because the WM does not reparent
 * clients, the Escher {@code Window.x/y} geometry is already root-relative, so
 * the absolute injection point is {@code (winX + px, winY + py)}.
 *
 * <h3>Threading</h3>
 * <p>Events are delivered on lg3d's picking/event thread, not the X11 event
 * thread. This matches the existing pattern in which {@code NativeWindowControl}
 * mutators ({@code setLocation}, {@code setVisible}, ...) already issue Escher
 * requests from the scene-manager thread against the shared {@link Display}. To
 * keep that safe, this class only performs <em>non-round-trip</em> sends
 * (fake input, set-input-focus) followed by {@link Display#flush()}; it never
 * calls {@code check_error()} or any reply-reading request from this thread.
 *
 * <h3>Known limitation</h3>
 * <p>Injecting a pointer warp moves the <em>real</em> X cursor to the client
 * window's true rectangle, which is distinct from where the quad is drawn. On a
 * bare Xorg (the deployment target) with the client windows stacked above the
 * Canvas3D this delivers input correctly; redundant warps are suppressed by
 * tracking the last injected position.
 *
 * @see X11Compositor#getXTest()
 * @see CompositeWindowImageLoader
 */
final class X11InputForwarder implements LgEventListener {

    private static final Logger logger = Logger.getLogger("lg.x11.input");

    /** X button numbers for the vertical wheel (X11 convention). */
    private static final int WHEEL_UP_BUTTON = 4;
    private static final int WHEEL_DOWN_BUTTON = 5;

    private final X11Compositor compositor;
    private final X11Client client;
    private final NativeWindow3D nw3d;
    private final Display display;
    private final Window root;

    // Scratch state (single-threaded per listener instance).
    private final Point3f tmpP3f = new Point3f();
    private final int[] absCoords = new int[2];
    private int lastAbsX = Integer.MIN_VALUE;
    private int lastAbsY = Integer.MIN_VALUE;

    /**
     * @param compositor the active compositor (supplies XTest, Display, root)
     * @param client     the X window backing {@code nw3d}
     * @param nw3d       the 3D representation whose events are forwarded
     */
    X11InputForwarder(X11Compositor compositor, X11Client client,
                      NativeWindow3D nw3d) {
        this.compositor = compositor;
        this.client = client;
        this.nw3d = nw3d;
        this.display = compositor.getDisplay();
        this.root = compositor.getRoot();
    }

    // ------------------------------------------------------------------
    // LgEventListener
    // ------------------------------------------------------------------

    public Class[] getTargetEventClasses() {
        return new Class[] {
            MouseButtonEvent3D.class,
            MouseMotionEvent3D.class,
            MouseWheelEvent3D.class,
            MouseEnteredEvent3D.class,
            KeyEvent3D.class,
        };
    }

    public void processEvent(LgEvent event) {
        XTest xtest = compositor.getXTest();
        if (xtest == null) {
            return; // XTEST unavailable: nothing to inject
        }
        try {
            if (event instanceof MouseEnteredEvent3D) {
                handleMouseEntered((MouseEnteredEvent3D) event);
            } else if (event instanceof MouseButtonEvent3D) {
                handleMouseButton((MouseButtonEvent3D) event, xtest);
            } else if (event instanceof MouseWheelEvent3D) {
                handleMouseWheel((MouseWheelEvent3D) event, xtest);
            } else if (event instanceof MouseMotionEvent3D) {
                handleMouseMotion((MouseMotionEvent3D) event, xtest);
            } else if (event instanceof KeyEvent3D) {
                handleKey((KeyEvent3D) event, xtest);
            } else {
                return;
            }
            display.flush();
        } catch (Throwable t) {
            logger.log(Level.WARNING, "input forwarding failed for window 0x"
                + Integer.toHexString(client.id), t);
        }
    }

    // ------------------------------------------------------------------
    // Pointer forwarding
    // ------------------------------------------------------------------

    /**
     * On mouse-enter, hand the X input focus to the client window so that
     * subsequently forwarded key events reach it. Non-round-trip: the WM's own
     * {@code check_error()} on its thread will surface any error.
     */
    private void handleMouseEntered(MouseEnteredEvent3D e) {
        if (!e.isEntered()) {
            return;
        }
        client.set_input_focus();
        // The pointer is now logically over this window; reset the warp dedup
        // so the next motion is always injected.
        lastAbsX = Integer.MIN_VALUE;
        lastAbsY = Integer.MIN_VALUE;
    }

    private void handleMouseMotion(MouseMotionEvent3D e, XTest xtest) {
        if (computeAbsCoords(e, absCoords)) {
            warpPointer(xtest, absCoords[0], absCoords[1]);
        }
    }

    private void handleMouseButton(MouseButtonEvent3D e, XTest xtest) {
        boolean press = e.isPressed();
        boolean release = e.isReleased();
        if (!press && !release) {
            // MOUSE_CLICKED: the client synthesises a click from the press and
            // release we already forward, so injecting here would double it.
            return;
        }
        int button = mapButton(e.getButton());
        if (button <= 0) {
            return;
        }
        if (computeAbsCoords(e, absCoords)) {
            warpPointer(xtest, absCoords[0], absCoords[1]);
        }
        xtest.fake_button_event(button, press, 0);
    }

    private void handleMouseWheel(MouseWheelEvent3D e, XTest xtest) {
        int rotation = e.getWheelRotation();
        if (rotation == 0) {
            return;
        }
        if (computeAbsCoords(e, absCoords)) {
            warpPointer(xtest, absCoords[0], absCoords[1]);
        }
        int button = (rotation < 0) ? WHEEL_UP_BUTTON : WHEEL_DOWN_BUTTON;
        int clicks = Math.abs(rotation);
        for (int i = 0; i < clicks; i++) {
            xtest.fake_button_event(button, true, 0);
            xtest.fake_button_event(button, false, 0);
        }
    }

    /** Warps the X pointer to an absolute root position, suppressing no-ops. */
    private void warpPointer(XTest xtest, int absX, int absY) {
        if (absX == lastAbsX && absY == lastAbsY) {
            return;
        }
        lastAbsX = absX;
        lastAbsY = absY;
        xtest.fake_motion_event(root, absX, absY, false, 0);
    }

    private static int mapButton(MouseEvent3D.ButtonId id) {
        if (id == null) {
            return -1;
        }
        switch (id) {
            case BUTTON1: return Input.BUTTON1; // 1 (left)
            case BUTTON2: return Input.BUTTON2; // 2 (middle)
            case BUTTON3: return Input.BUTTON3; // 3 (right)
            default:      return -1;            // NOBUTTON
        }
    }

    // ------------------------------------------------------------------
    // Keyboard forwarding
    // ------------------------------------------------------------------

    /**
     * Replays physical keycodes on KEY_PRESSED/KEY_RELEASED. The server applies
     * its keymap and the modifier state (also forwarded) to yield the correct
     * character, so KEY_TYPED is ignored to prevent double input.
     */
    private void handleKey(KeyEvent3D e, XTest xtest) {
        if (e.isTyped()) {
            return;
        }
        boolean press = e.isPressed();
        boolean release = e.isReleased();
        if (!press && !release) {
            return;
        }
        int keysym = vkToKeysym(e.getKeyCode());
        if (keysym == 0) {
            return; // key not mapped to an X keysym
        }
        int keycode = keysymToKeycode(keysym);
        if (keycode < 0) {
            return; // keysym absent from this server's keymap
        }
        xtest.fake_key_event(keycode, press, 0);
    }

    /**
     * Resolves an X keysym to a keycode using the server's keymap, accounting
     * for {@code keysyms_per_keycode}. (Escher's own
     * {@link Input#keysym_to_keycode(int)} ignores the per-keycode count and is
     * therefore wrong for modern servers, which report 2 or more.)
     *
     * @return the keycode, or -1 if the keysym is not present
     */
    private int keysymToKeycode(int keysym) {
        Input input = display.input;
        if (input == null || input.keysyms == null
            || input.keysyms_per_keycode <= 0) {
            return -1;
        }
        int[] syms = input.keysyms;
        int per = input.keysyms_per_keycode;
        for (int i = 0; i < syms.length; i++) {
            if (syms[i] == keysym) {
                return input.min_keycode + (i / per);
            }
        }
        return -1;
    }

    /**
     * Maps an AWT virtual key code to the <em>unshifted base</em> X11 keysym.
     * Returning the base keysym (e.g. lowercase for letters) lets the server
     * apply Shift/Ctrl/Alt — which are forwarded as their own key events — to
     * produce the final character, exactly as a physical keyboard would.
     *
     * @return the keysym, or 0 if the key is not mapped
     */
    private static int vkToKeysym(int vk) {
        // Letters: VK_A..VK_Z are 0x41..0x5A; base keysym is 0x61..0x7A.
        if (vk >= KeyEvent.VK_A && vk <= KeyEvent.VK_Z) {
            return vk - KeyEvent.VK_A + 0x61;
        }
        // Dedicated VKs (specials, navigation, function, modifiers, keypad and
        // the two punctuation keys whose VK != ASCII) must be resolved before
        // the ASCII fallback, since several of their codes fall inside 0x20..0x7e.
        int special = specialVkToKeysym(vk);
        if (special != 0) {
            return special;
        }
        // Remaining printable ASCII whose VK equals the character code:
        // digits 0-9, and punctuation , - . / ; = [ \ ].
        if (vk >= 0x20 && vk <= 0x7e) {
            return vk;
        }
        return 0; // unmapped
    }

    private static int specialVkToKeysym(int vk) {
        switch (vk) {
            // Whitespace / editing
            case KeyEvent.VK_SPACE:       return 0x0020; // XK_space
            case KeyEvent.VK_BACK_SPACE:  return 0xff08; // XK_BackSpace
            case KeyEvent.VK_TAB:         return 0xff09; // XK_Tab
            case KeyEvent.VK_ENTER:       return 0xff0d; // XK_Return
            case KeyEvent.VK_ESCAPE:      return 0xff1b; // XK_Escape
            case KeyEvent.VK_DELETE:      return 0xffff; // XK_Delete
            case KeyEvent.VK_INSERT:      return 0xff63; // XK_Insert
            // Navigation
            case KeyEvent.VK_HOME:        return 0xff50; // XK_Home
            case KeyEvent.VK_LEFT:        return 0xff51; // XK_Left
            case KeyEvent.VK_UP:          return 0xff52; // XK_Up
            case KeyEvent.VK_RIGHT:       return 0xff53; // XK_Right
            case KeyEvent.VK_DOWN:        return 0xff54; // XK_Down
            case KeyEvent.VK_PAGE_UP:     return 0xff55; // XK_Page_Up
            case KeyEvent.VK_PAGE_DOWN:   return 0xff56; // XK_Page_Down
            case KeyEvent.VK_END:         return 0xff57; // XK_End
            // Punctuation whose VK does not equal its ASCII code
            case KeyEvent.VK_BACK_QUOTE:  return 0x0060; // XK_grave
            case KeyEvent.VK_QUOTE:       return 0x0027; // XK_apostrophe
            // Modifiers / locks
            case KeyEvent.VK_SHIFT:       return 0xffe1; // XK_Shift_L
            case KeyEvent.VK_CONTROL:     return 0xffe3; // XK_Control_L
            case KeyEvent.VK_ALT:         return 0xffe9; // XK_Alt_L
            case KeyEvent.VK_META:        return 0xffeb; // XK_Super_L
            case KeyEvent.VK_ALT_GRAPH:   return 0xfe03; // XK_ISO_Level3_Shift
            case KeyEvent.VK_CAPS_LOCK:   return 0xffe5; // XK_Caps_Lock
            case KeyEvent.VK_NUM_LOCK:    return 0xff7f; // XK_Num_Lock
            case KeyEvent.VK_SCROLL_LOCK: return 0xff14; // XK_Scroll_Lock
            case KeyEvent.VK_PRINTSCREEN: return 0xff61; // XK_Print
            case KeyEvent.VK_PAUSE:       return 0xff13; // XK_Pause
            // Numeric keypad operators
            case KeyEvent.VK_MULTIPLY:    return 0xffaa; // XK_KP_Multiply
            case KeyEvent.VK_ADD:         return 0xffab; // XK_KP_Add
            case KeyEvent.VK_SEPARATOR:   return 0xffac; // XK_KP_Separator
            case KeyEvent.VK_SUBTRACT:    return 0xffad; // XK_KP_Subtract
            case KeyEvent.VK_DECIMAL:     return 0xffae; // XK_KP_Decimal
            case KeyEvent.VK_DIVIDE:      return 0xffaf; // XK_KP_Divide
            default: break;
        }
        // Function keys F1..F12: XK_F1 = 0xffbe.
        if (vk >= KeyEvent.VK_F1 && vk <= KeyEvent.VK_F12) {
            return 0xffbe + (vk - KeyEvent.VK_F1);
        }
        // Keypad digits 0..9: XK_KP_0 = 0xffb0.
        if (vk >= KeyEvent.VK_NUMPAD0 && vk <= KeyEvent.VK_NUMPAD9) {
            return 0xffb0 + (vk - KeyEvent.VK_NUMPAD0);
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // Coordinate translation
    // ------------------------------------------------------------------

    /**
     * Converts a mouse event's local 3D intersection into an absolute
     * root-relative X pointer position for the backing client window.
     *
     * @param e3d the 3D mouse event
     * @param out receives {@code [absX, absY]}
     * @return true if the intersection and window geometry are valid
     */
    private boolean computeAbsCoords(MouseEvent3D e3d, int[] out) {
        e3d.getLocalIntersection(tmpP3f);
        float lx = tmpP3f.x;
        float ly = tmpP3f.y;
        if (Float.isNaN(lx) || Float.isNaN(ly)) {
            return false; // event did not occur over the window body
        }
        float bodyW = nw3d.getBodyWidth();
        float bodyH = nw3d.getBodyHeight();
        int pixelW = client.getWidth();
        int pixelH = client.getHeight();
        if (bodyW <= 0f || bodyH <= 0f || pixelW <= 0 || pixelH <= 0) {
            return false;
        }
        // Local origin is the body centre; +X right, +Y up. Map to normalised
        // window space with origin at the top-left, then to pixels.
        float u = (lx / bodyW) + 0.5f;
        float v = 0.5f - (ly / bodyH);
        if (u < 0f) u = 0f; else if (u > 1f) u = 1f;
        if (v < 0f) v = 0f; else if (v > 1f) v = 1f;
        int px = Math.round(u * (pixelW - 1));
        int py = Math.round(v * (pixelH - 1));
        // Client geometry is root-relative (no reparenting), so the absolute
        // injection point is the window origin plus the pixel offset.
        out[0] = client.getX() + px;
        out[1] = client.getY() + py;
        return true;
    }
}
