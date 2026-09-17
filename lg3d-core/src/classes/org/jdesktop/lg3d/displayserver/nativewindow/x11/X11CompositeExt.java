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

import gnu.x11.Data;
import gnu.x11.Display;
import gnu.x11.Pixmap;
import gnu.x11.Request;
import gnu.x11.Window;
import gnu.x11.XProtocolInfo;
import gnu.x11.extension.Extension;
import gnu.x11.extension.NotFoundException;

/**
 * Pure-Java Escher binding for the X Composite extension (version 0.4).
 *
 * <p>Composite allows a window manager/compositor to redirect the rendering
 * of top-level windows into offscreen pixmaps instead of directly to the
 * screen. The compositor then reads those pixmaps (via XShm or GetImage) and
 * composites them however it likes — in lg3d's case, as textured quads in a
 * 3D scene.
 *
 * <p>Protocol reference: <em>Composite Extension Protocol, Version 0.4</em>
 * (part of the X.Org specification).
 *
 * <h3>Usage in lg3d</h3>
 * <ol>
 *   <li>{@link #redirectSubwindows} on the root window with
 *       {@link #REDIRECT_AUTOMATIC} — makes the X server render all
 *       top-level children into offscreen pixmaps.</li>
 *   <li>{@link #nameWindowPixmap} for each managed window — obtains a
 *       {@link Pixmap} handle that references the window's offscreen
 *       storage.</li>
 *   <li>Read pixels from the pixmap (via XShm GetImage or core GetImage)
 *       whenever a Damage notification fires.</li>
 *   <li>{@link #unredirectSubwindows} on shutdown.</li>
 * </ol>
 *
 * @see X11DamageExt
 * @see X11ShmExt
 */
public class X11CompositeExt extends Extension {

    /** Names of every minor opcode defined by Composite 0.4, in order. */
    static final String[] MINOR_OPCODE_STRINGS = {
        "QueryVersion",                // 0
        "RedirectWindow",              // 1
        "RedirectSubwindows",          // 2
        "UnredirectWindow",            // 3
        "UnredirectSubwindows",        // 4
        "CreateRegionFromBorderClip",  // 5
        "NameWindowPixmap",            // 6
        "GetOverlayWindow",            // 7
        "FreeOverlayWindow"            // 8
    };

    /** Client-side Composite version we request. */
    public static final int CLIENT_MAJOR_VERSION = 0;
    public static final int CLIENT_MINOR_VERSION = 4;

    // --- Update (redirect) modes ---

    /**
     * The server automatically manages the offscreen pixmap; the
     * compositor gets Damage notifications but does not need to manually
     * trigger repaints. This is the mode lg3d uses.
     */
    public static final int REDIRECT_AUTOMATIC = 0;

    /**
     * The compositor is responsible for calling {@code CompositeUpdate}
     * (not implemented here; not needed for lg3d).
     */
    public static final int REDIRECT_MANUAL = 1;

    /** Server-side version, populated after successful QueryVersion. */
    public int server_major_version;
    public int server_minor_version;

    /**
     * Negotiates the Composite extension against the given display and
     * issues QueryVersion.
     *
     * @throws NotFoundException if the X server does not advertise Composite
     */
    public X11CompositeExt(Display display) throws NotFoundException {
        super(display, "Composite", MINOR_OPCODE_STRINGS);

        // QueryVersion and GetOverlayWindow expect replies.
        XProtocolInfo.extensionRequestExpectsReply(major_opcode, 0, 32);
        XProtocolInfo.extensionRequestExpectsReply(major_opcode, 7, 32);

        // --- QueryVersion (minor opcode 0) ---
        Request request = new Request(display, major_opcode, 0, 3);
        request.write4(CLIENT_MAJOR_VERSION);
        request.write4(CLIENT_MINOR_VERSION);

        Data reply = display.read_reply(request);
        server_major_version = reply.read4(8);
        server_minor_version = reply.read4(12);
    }

    // ------------------------------------------------------------------
    // Opcode 1: RedirectWindow
    // ------------------------------------------------------------------

    /**
     * Redirects a single window's rendering into an offscreen pixmap.
     *
     * @param window the top-level window to redirect
     * @param update {@link #REDIRECT_AUTOMATIC} or {@link #REDIRECT_MANUAL}
     * @see <a href="https://xorg.freedesktop.org/wiki/Extensions/Composite/">CompositeRedirectWindow</a>
     */
    public void redirectWindow(Window window, int update) {
        Request request = new Request(display, major_opcode, 1, 3);
        request.write4(window.id);
        request.write1(update);
        request.write3_unused();
        display.send_request(request);
    }

    // ------------------------------------------------------------------
    // Opcode 2: RedirectSubwindows
    // ------------------------------------------------------------------

    /**
     * Redirects all current and future children of the given window into
     * offscreen pixmaps. lg3d calls this on the root window to become the
     * compositor for the entire screen.
     *
     * @param window typically the root window
     * @param update {@link #REDIRECT_AUTOMATIC}
     */
    public void redirectSubwindows(Window window, int update) {
        Request request = new Request(display, major_opcode, 2, 3);
        request.write4(window.id);
        request.write1(update);
        request.write3_unused();
        display.send_request(request);
    }

    // ------------------------------------------------------------------
    // Opcode 3: UnredirectWindow
    // ------------------------------------------------------------------

    /**
     * Stops redirecting a single window. The window resumes drawing directly
     * to the screen.
     */
    public void unredirectWindow(Window window, int update) {
        Request request = new Request(display, major_opcode, 3, 3);
        request.write4(window.id);
        request.write1(update);
        request.write3_unused();
        display.send_request(request);
    }

    // ------------------------------------------------------------------
    // Opcode 4: UnredirectSubwindows
    // ------------------------------------------------------------------

    /**
     * Stops redirecting children of the given window. lg3d calls this on the
     * root window during compositor shutdown.
     */
    public void unredirectSubwindows(Window window, int update) {
        Request request = new Request(display, major_opcode, 4, 3);
        request.write4(window.id);
        request.write1(update);
        request.write3_unused();
        display.send_request(request);
    }

    // ------------------------------------------------------------------
    // Opcode 5: CreateRegionFromBorderClip
    // ------------------------------------------------------------------

    /**
     * Creates an XFixes Region from the border clip of a window. The border
     * clip is the area of the window that is not obscured by its border.
     *
     * @param region a pre-allocated XFixes region id
     *               (via {@code display.allocate_id()})
     * @param window the window whose border clip to capture
     */
    public void createRegionFromBorderClip(int region, Window window) {
        Request request = new Request(display, major_opcode, 5, 3);
        request.write4(region);
        request.write4(window.id);
        display.send_request(request);
    }

    // ------------------------------------------------------------------
    // Opcode 6: NameWindowPixmap
    // ------------------------------------------------------------------

    /**
     * Obtains a {@link Pixmap} that references the offscreen storage of a
     * redirected window. The pixmap remains valid until the window is
     * resized or unredirected; after a resize the compositor must call this
     * again to get a new pixmap.
     *
     * <p>The caller must pre-allocate the pixmap id via
     * {@code display.allocate_id()}.
     *
     * @param window a redirected window
     * @param pixmap a pre-allocated pixmap id that will name the window's
     *               offscreen storage
     */
    public void nameWindowPixmap(Window window, Pixmap pixmap) {
        Request request = new Request(display, major_opcode, 6, 3);
        request.write4(window.id);
        request.write4(pixmap.id);
        display.send_request(request);
    }

    /**
     * Convenience overload: allocates a new pixmap id and returns it.
     *
     * @param window a redirected window
     * @return a new Pixmap referencing the window's offscreen storage
     */
    public Pixmap nameWindowPixmap(Window window) {
        int id = display.allocate_id(this);
        Pixmap pixmap = new Pixmap(id);
        nameWindowPixmap(window, pixmap);
        return pixmap;
    }

    // ------------------------------------------------------------------
    // Opcode 7: GetOverlayWindow
    // ------------------------------------------------------------------

    /** Reply of {@link #getOverlayWindow(Window)}. */
    public static class OverlayReply extends Data {
        public OverlayReply(Data data) { super(data); }

        /** The overlay window id. */
        public int overlay_window_id() { return read4(8); }
    }

    /**
     * Returns the overlay window for the given root. The overlay window
     * sits above all redirected windows and is used by compositors to draw
     * screen-level UI (e.g. a magnifier or on-screen display). lg3d does
     * not currently use it but the binding is here for completeness.
     */
    public OverlayReply getOverlayWindow(Window window) {
        Request request = new Request(display, major_opcode, 7, 2);
        request.write4(window.id);
        return new OverlayReply(display.read_reply(request));
    }

    // ------------------------------------------------------------------
    // Opcode 8: FreeOverlayWindow
    // ------------------------------------------------------------------

    /**
     * Releases a previously obtained overlay window.
     */
    public void freeOverlayWindow(Window window) {
        Request request = new Request(display, major_opcode, 8, 2);
        request.write4(window.id);
        display.send_request(request);
    }

    // ------------------------------------------------------------------

    @Override
    public String more_string() {
        return "\n  client-version: " + CLIENT_MAJOR_VERSION + "." + CLIENT_MINOR_VERSION
             + "\n  server-version: " + server_major_version + "." + server_minor_version;
    }
}
