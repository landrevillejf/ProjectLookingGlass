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

import gnu.x11.Atom;
import gnu.x11.Display;
import gnu.x11.Enum;
import gnu.x11.Window;
import gnu.x11.event.Event;
import gnu.x11.extension.NotFoundException;
import gnu.x11.extension.XTest;

import org.jdesktop.lg3d.displayserver.fws.FoundationWinSys;
import org.jogamp.java3d.Canvas3D;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The lg3d X11 compositor.
 *
 * <p>This class layers Composite/Damage redirection on top of the existing
 * {@link X11WindowManager} WM claim. It does <em>not</em> open its own
 * Display connection — it shares the one owned by X11WindowManager, which
 * already holds {@code SubstructureRedirect} on the root window (a
 * prerequisite for {@code CompositeRedirectSubwindows}).
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link X11IntegrationModule#initialize()} creates
 *       {@link X11WindowManager} (claims WM, starts event thread).</li>
 *   <li>It then creates this class with the WM's {@link Display} and root
 *       {@link Window}.</li>
 *   <li>The constructor negotiates Composite, Damage, XFixes, and MIT-SHM
 *       extensions, calls {@code RedirectSubwindows(root, Automatic)}, and
 *       enumerates existing top-level windows.</li>
 *   <li>{@link X11WindowManager}'s event loop routes extension events
 *       (DamageNotify, CursorNotify) here via
 *       {@link #dispatchExtensionEvent(Event)}.</li>
 *   <li>On shutdown, {@link #shutdown()} unredirects subwindows and
 *       destroys all Damage objects.</li>
 * </ol>
 *
 * <h3>Threading</h3>
 * <p>All methods are called from the X11WindowManager event thread except
 * {@link #shutdown()} which may be called from any thread. The
 * {@code damageMap} is a ConcurrentHashMap so Stage 3's image loader can
 * look up damage ids from the Java 3D rendering thread without
 * synchronization.
 *
 * @see X11CompositeExt
 * @see X11DamageExt
 * @see X11ShmExt
 */
public class X11Compositor {

    private static final Logger logger = Logger.getLogger("lg.x11.compositor");

    private final Display display;
    private final Window root;

    // Extensions
    private X11CompositeExt composite;
    private X11DamageExt damage;
    private X11FixesExt fixes;
    private X11ShmExt shm;

    /**
     * XTest binding used by Stage 5's {@link X11InputForwarder} to inject
     * synthetic pointer/keyboard events into redirected client windows.
     * Null if the server lacks XTEST, in which case 3D-&gt;X input forwarding
     * is disabled but compositing still works.
     */
    private XTest xtest;

    /**
     * Maps a window id to its Damage object id. Populated when a window is
     * first mapped and its NameWindowPixmap is obtained. Used by Stage 3's
     * CompositeWindowImageLoader to acknowledge damage after reading pixels.
     */
    private final ConcurrentHashMap<Integer, Integer> damageMap = new ConcurrentHashMap<>();

    /**
     * Maps a window id to its Composite offscreen pixmap id. Invalidated on
     * window resize (the old pixmap becomes stale; a new NameWindowPixmap
     * must be issued).
     */
    private final ConcurrentHashMap<Integer, Integer> pixmapMap = new ConcurrentHashMap<>();

    /**
     * Per-window listeners notified when the server reports damage. The
     * Stage 4 window-manager wiring registers a listener per managed window
     * that drives its {@link CompositeWindowImageLoader}.
     */
    private final ConcurrentHashMap<Integer, DamageListener> damageListeners =
        new ConcurrentHashMap<>();

    private volatile boolean active;

    /**
     * The X window id of lg3d's own Canvas3D host window, or -1 if unknown.
     * The WM skips this window in {@code mapRequest} so lg3d does not try to
     * manage (and texture) itself. Set by {@link #markOwnWindowAsDesktop(int)}.
     */
    private volatile int ownWindowId = -1;

    /**
     * Callback invoked on the X event thread when a monitored window
     * repaints. Implementations typically read the damaged region and update
     * the window's texture.
     */
    public interface DamageListener {
        /**
         * @param windowId the damaged window
         * @param x        damaged region left, in window pixels
         * @param y        damaged region top, in window pixels
         * @param width    damaged region width
         * @param height   damaged region height
         */
        void damageReported(int windowId, int x, int y, int width, int height);
    }

    /**
     * Creates and activates the compositor.
     *
     * @param display the shared Escher Display (WM already claimed)
     * @param root    the root window of the default screen
     * @throws RuntimeException if any required extension is missing or
     *                          Composite redirection fails
     */
    public X11Compositor(Display display, Window root) {
        this.display = display;
        this.root = root;

        try {
            initExtensions();
        } catch (NotFoundException e) {
            throw new RuntimeException(
                "X11 compositor requires extension '" + e.getMessage()
                + "' which is not available on this X server. "
                + "Run :lg3d-core:verifyX11Extensions for details.", e);
        }

        redirectSubwindows();
        selectCursorEvents();
        enumerateExistingWindows();
        active = true;

        logger.info("X11 compositor active on display "
            + display.toString() + " (Composite "
            + composite.server_major_version + "." + composite.server_minor_version
            + ", Damage " + damage.server_major_version + "." + damage.server_minor_version
            + ", SHM " + (shm != null ? shm.server_major_version + "." + shm.server_minor_version : "N/A")
            + ")");
    }

    // ------------------------------------------------------------------
    // Initialization
    // ------------------------------------------------------------------

    private void initExtensions() throws NotFoundException {
        composite = new X11CompositeExt(display);
        logger.fine("Composite " + composite.server_major_version + "."
            + composite.server_minor_version + " negotiated");

        damage = new X11DamageExt(display);
        logger.fine("Damage " + damage.server_major_version + "."
            + damage.server_minor_version + " negotiated");

        fixes = new X11FixesExt(display);
        logger.fine("XFixes " + fixes.server_major_version + "."
            + fixes.server_minor_version + " negotiated");

        // MIT-SHM is optional: if the server doesn't have it (unlikely but
        // possible on a minimal Xorg), Stage 3 falls back to core GetImage.
        try {
            shm = new X11ShmExt(display);
            logger.fine("MIT-SHM " + shm.server_major_version + "."
                + shm.server_minor_version + " negotiated (shared-pixmaps="
                + shm.shared_pixmaps_supported + ")");
        } catch (NotFoundException e) {
            logger.warning("MIT-SHM not available; pixel readback will use "
                + "core GetImage (slower). Install/enable the SHM extension "
                + "for better compositor performance.");
            shm = null;
        }

        // XTest drives Stage 5 input forwarding (synthetic pointer/keyboard
        // events into redirected client windows). Treated as optional so a
        // server without XTEST still composites; forwarding is then disabled.
        try {
            xtest = new XTest(display);
            logger.fine("XTest " + xtest.server_major_version + "."
                + xtest.server_minor_version + " negotiated");
        } catch (NotFoundException e) {
            logger.warning("XTest not available; 3D->X input forwarding will "
                + "be disabled. Enable the XTEST extension on the X server.");
            xtest = null;
        }
    }

    /**
     * Calls CompositeRedirectSubwindows on the root window with Automatic
     * update mode. After this call, the X server renders all top-level
     * children into offscreen pixmaps instead of directly to the screen.
     * The screen shows only the root window's background until a compositor
     * paints the redirected content back (or lg3d's Canvas3D covers it).
     */
    private void redirectSubwindows() {
        composite.redirectSubwindows(root, X11CompositeExt.REDIRECT_AUTOMATIC);
        display.flush();
        display.check_error();
        logger.fine("CompositeRedirectSubwindows(root, Automatic) issued");
    }

    /**
     * Asks XFixes to deliver CursorNotify events on the root window, so the
     * compositor learns whenever the cursor image changes. Stage 5 uses these
     * to update the 3D cursor representation.
     */
    private void selectCursorEvents() {
        if (fixes == null) {
            return;
        }
        fixes.selectCursorInput(root, X11FixesExt.SET_CURSOR_NOTIFY_MASK);
        display.flush();
        display.check_error();
        logger.fine("XFixesSelectCursorInput(root, SetCursorNotify) issued");
    }

    /**
     * Enumerates top-level windows that already exist (e.g. panels,
     * backgrounds, or apps started before lg3d) and sets up Damage
     * monitoring for any that are already mapped.
     */
    private void enumerateExistingWindows() {
        Window.TreeReply tree = root.tree();
        int count = tree.children_count();
        if (count == 0) {
            logger.fine("No existing top-level windows found");
            return;
        }
        logger.fine("Enumerating " + count + " existing top-level window(s)");
        gnu.x11.Enum children = tree.children();
        while (children.more()) {
            Window w = (Window) children.next();
            int childId = w.id;
            try {
                Window.AttributesReply attrs = w.attributes();
                if (attrs.map_state() == Window.AttributesReply.VIEWABLE) {
                    setupDamageForWindow(childId);
                }
            } catch (gnu.x11.Error e) {
                // Window may have been destroyed between QueryTree and
                // GetWindowAttributes; ignore.
                logger.fine("Skipping destroyed window 0x"
                    + Integer.toHexString(childId) + ": " + e);
            }
        }
    }

    // ------------------------------------------------------------------
    // Damage management
    // ------------------------------------------------------------------

    /**
     * Sets up Damage monitoring and obtains the Composite offscreen pixmap
     * for a newly mapped window. Called from the event loop on MapNotify.
     *
     * @param windowId the X window id
     */
    public void setupDamageForWindow(int windowId) {
        if (damageMap.containsKey(windowId)) {
            return; // already monitored
        }

        Window w = new Window(display, windowId);

        // Obtain the Composite offscreen pixmap for this window.
        try {
            int pixmapId = display.allocate_id(composite);
            composite.nameWindowPixmap(w, new gnu.x11.Pixmap(pixmapId));
            pixmapMap.put(windowId, pixmapId);
        } catch (gnu.x11.Error e) {
            logger.log(Level.WARNING, "NameWindowPixmap failed for 0x"
                + Integer.toHexString(windowId), e);
            return;
        }

        // Create a Damage object on the window (not the pixmap) with
        // NON_EMPTY report level: one event per repaint burst.
        int damageId = display.allocate_id(damage);
        damage.create(damageId, w, X11DamageExt.REPORT_LEVEL_NON_EMPTY);
        damageMap.put(windowId, damageId);

        logger.fine("Damage setup for window 0x" + Integer.toHexString(windowId)
            + " (damage=0x" + Integer.toHexString(damageId) + ")");
    }

    /**
     * Tears down Damage monitoring for a window that has been unmapped or
     * destroyed. Releases the Damage object and forgets the pixmap.
     */
    public void teardownDamageForWindow(int windowId) {
        Integer damageId = damageMap.remove(windowId);
        if (damageId != null) {
            try {
                damage.destroy(damageId);
            } catch (gnu.x11.Error e) {
                // Already destroyed or invalid; ignore.
                logger.fine("Damage destroy failed for 0x"
                    + Integer.toHexString(windowId) + ": " + e);
            }
        }
        pixmapMap.remove(windowId);
        damageListeners.remove(windowId);
    }

    /**
     * Re-issues NameWindowPixmap after a window resize. The old pixmap is
     * invalidated by the server when the window is reconfigured.
     */
    public void refreshPixmapForWindow(int windowId) {
        Integer oldPixmapId = pixmapMap.remove(windowId);
        if (oldPixmapId != null) {
            // Free the old pixmap resource id.
            try {
                gnu.x11.Pixmap old = new gnu.x11.Pixmap(oldPixmapId);
                old.display = display;
                old.free();
            } catch (gnu.x11.Error ignored) {}
        }

        Window w = new Window(display, windowId);
        try {
            int pixmapId = display.allocate_id(composite);
            composite.nameWindowPixmap(w, new gnu.x11.Pixmap(pixmapId));
            pixmapMap.put(windowId, pixmapId);
        } catch (gnu.x11.Error e) {
            logger.log(Level.WARNING, "NameWindowPixmap refresh failed for 0x"
                + Integer.toHexString(windowId), e);
        }
    }

    // ------------------------------------------------------------------
    // Own-window exemption
    // ------------------------------------------------------------------

    /**
     * Exempts lg3d's own top-level window (the AWT window hosting the
     * Canvas3D) from both WM management and Composite redirection.
     *
     * <p>Three things happen, all required:
     * <ol>
     *   <li>{@code override_redirect = true} so lg3d-as-WM does not try to
     *       manage (reparent/decorate) its own output window.</li>
     *   <li>{@code _NET_WM_WINDOW_TYPE = _NET_WM_WINDOW_TYPE_DESKTOP} so any
     *       EWMH-aware logic treats it as the background/desktop window.</li>
     *   <li>{@code CompositeUnredirectWindow} so the window keeps drawing
     *       <em>directly</em> to the screen. {@code RedirectSubwindows}
     *       redirects <em>all</em> root children into offscreen pixmaps;
     *       without this, lg3d's own rendering would land in a pixmap that
     *       nothing composites back and the screen would stay black.</li>
     * </ol>
     *
     * <p>The native X window id of the AWT Canvas3D host is platform- and
     * JDK-specific to obtain (it is not exposed by WinSysAWT), so the caller
     * supplies it. See the Stage 4 window-manager wiring.
     *
     * @param windowId the X window id of lg3d's own top-level window
     */
    public void markOwnWindowAsDesktop(int windowId) {
        ownWindowId = windowId;
        Window w = new Window(display, windowId);

        // 1. override_redirect
        Window.Attributes attrs = new Window.Attributes();
        attrs.set_override_redirect(true);
        w.change_attributes(attrs);

        // 2. _NET_WM_WINDOW_TYPE = _NET_WM_WINDOW_TYPE_DESKTOP
        Atom netWmWindowType = (Atom) Atom.intern(display, "_NET_WM_WINDOW_TYPE");
        Atom desktop = (Atom) Atom.intern(display, "_NET_WM_WINDOW_TYPE_DESKTOP");
        w.change_property(Window.REPLACE, 1, netWmWindowType, Atom.ATOM, 32,
            new int[] { desktop.id }, 0, 32);

        // 3. Keep drawing straight to the screen (undo RedirectSubwindows).
        try {
            composite.unredirectWindow(w, X11CompositeExt.REDIRECT_AUTOMATIC);
        } catch (gnu.x11.Error e) {
            logger.log(Level.WARNING, "UnredirectWindow failed for own window 0x"
                + Integer.toHexString(windowId), e);
        }

        display.flush();
        display.check_error();
        logger.fine("Own window 0x" + Integer.toHexString(windowId)
            + " marked override_redirect + DESKTOP and unredirected");
    }

    /**
     * Discovers and exempts lg3d's own top-level window (the AWT window that
     * hosts the Canvas3D) via {@link #markOwnWindowAsDesktop(int)}. This is the
     * Stage 4 wiring for the compositor bootstrap responsibility that keeps
     * lg3d-as-WM from managing (and Composite from redirecting) lg3d's own
     * output window.
     *
     * <p>The window id is resolved in the following order:
     * <ol>
     *   <li>the {@code lg3d.x11.ownwindowid} system property (decimal or
     *       {@code 0x} hex), for deterministic deployments;</li>
     *   <li>walking the Canvas3D's AWT component hierarchy up to its top-level
     *       {@link java.awt.Window} and reading the native X handle from the
     *       AWT peer reflectively. On JDK 21 this requires
     *       {@code --add-exports java.desktop/sun.awt=ALL-UNNAMED} at runtime;
     *       if it is unavailable the discovery fails quietly.</li>
     * </ol>
     *
     * <p>If neither yields an id, a warning is logged: without the exemption
     * lg3d's own rendering is redirected into an offscreen pixmap that nothing
     * composites back, so the physical screen would appear black.
     */
    public void exemptOwnWindow() {
        long wid = -1L;
        String override = System.getProperty("lg3d.x11.ownwindowid");
        if (override != null && !override.trim().isEmpty()) {
            try {
                wid = Long.decode(override.trim());
            } catch (NumberFormatException e) {
                logger.warning("Ignoring invalid lg3d.x11.ownwindowid='"
                    + override + "': " + e.getMessage());
            }
        }
        if (wid <= 0L) {
            wid = discoverOwnWindowId();
        }
        if (wid > 0L) {
            markOwnWindowAsDesktop((int) wid);
        } else {
            logger.warning("Could not determine lg3d's own X window id; the "
                + "Canvas3D window may be redirected offscreen (black screen). "
                + "Set -Dlg3d.x11.ownwindowid=<id> or run with "
                + "--add-exports java.desktop/sun.awt=ALL-UNNAMED to enable "
                + "automatic discovery.");
        }
    }

    /**
     * Walks from the Canvas3D up to its top-level AWT window and reads the
     * native X window id from the platform peer. Returns -1 if unavailable.
     */
    private static long discoverOwnWindowId() {
        try {
            FoundationWinSys fws = FoundationWinSys.getFoundationWinSys();
            if (fws == null) {
                return -1L;
            }
            Canvas3D canvas = fws.getCanvas(0);
            java.awt.Component c = canvas;
            while (c != null && !(c instanceof java.awt.Window)) {
                c = c.getParent();
            }
            if (c == null) {
                return -1L;
            }
            return readNativeWindowId(c);
        } catch (Throwable t) {
            logger.log(Level.FINE, "own-window discovery failed", t);
            return -1L;
        }
    }

    /**
     * Reads the native X window id from a realized AWT component's peer via
     * reflection ({@code sun.awt.X11ComponentPeer.getWindow()}). Fully guarded:
     * returns -1 if the peer is not yet created or the JDK internals are not
     * exported.
     */
    private static long readNativeWindowId(java.awt.Component c) {
        try {
            Method getPeer = java.awt.Component.class.getDeclaredMethod("getPeer");
            getPeer.setAccessible(true);
            Object peer = getPeer.invoke(c);
            if (peer == null) {
                return -1L;
            }
            Method getWindow = peer.getClass().getMethod("getWindow");
            Object id = getWindow.invoke(peer);
            if (id instanceof Number) {
                return ((Number) id).longValue();
            }
        } catch (Throwable t) {
            logger.log(Level.FINE, "readNativeWindowId failed", t);
        }
        return -1L;
    }

    // ------------------------------------------------------------------
    // Event dispatching
    // ------------------------------------------------------------------

    /**
     * Called by {@link X11WindowManager}'s event loop for events it does not
     * handle (extension events with code >= 64, plus any future compositor-
     * specific core events).
     *
     * @param event the raw Escher event
     * @return true if this compositor consumed the event, false otherwise
     */
    public boolean dispatchExtensionEvent(Event event) {
        if (!active) return false;

        int code = event.code();

        // DamageNotify: first_event of the Damage extension + 0
        if (damage != null && code == damage.first_event) {
            handleDamageNotify(event);
            return true;
        }

        // XFixes CursorNotify: first_event of XFixes + 1
        // (XFixes event 0 = SelectionNotify, event 1 = CursorNotify)
        if (fixes != null && code == fixes.first_event + 1) {
            handleCursorNotify(event);
            return true;
        }

        return false;
    }

    /**
     * Handles a DamageNotify event: acknowledges the damage (subtract all) so
     * the server reports the next repaint, then notifies the window's
     * registered {@link DamageListener} (if any) with the damaged area so it
     * can re-read that region of the Composite pixmap.
     */
    private void handleDamageNotify(Event event) {
        if (event instanceof X11DamageExt.NotifyEvent) {
            X11DamageExt.NotifyEvent dn = (X11DamageExt.NotifyEvent) event;
            int damageId = dn.damage_id();
            // The Damage object was created on the window, so the reported
            // drawable is the window id.
            int windowId = dn.drawable_id();
            gnu.x11.Rectangle area = dn.area();

            // Acknowledge: subtract all damage so we get the next
            // notification when the window repaints again.
            damage.subtract(damageId);

            logger.finest("DamageNotify: window=0x"
                + Integer.toHexString(windowId)
                + " area=" + area
                + " geometry=" + dn.geometry());

            DamageListener listener = damageListeners.get(windowId);
            if (listener != null && area != null) {
                listener.damageReported(windowId, area.x, area.y,
                    area.width, area.height);
            }
        }
    }

    /**
     * Handles an XFixes CursorNotify event. Stage 5 will use this to update
     * the 3D cursor representation; for now it is logged and consumed so it
     * does not fall through to the WM's "unhandled event" path.
     */
    private void handleCursorNotify(Event event) {
        if (event instanceof X11FixesExt.CursorNotifyEvent) {
            X11FixesExt.CursorNotifyEvent cn = (X11FixesExt.CursorNotifyEvent) event;
            logger.finest("CursorNotify: serial=" + cn.cursor_serial()
                + " window=0x" + Integer.toHexString(cn.window_id()));
            // Stage 5 hook: update the 3D cursor image/name here.
        }
    }

    // ------------------------------------------------------------------
    // Accessors for Stage 3+
    // ------------------------------------------------------------------

    /** Returns the Composite extension binding. */
    public X11CompositeExt getCompositeExt() { return composite; }

    /** Returns the Damage extension binding. */
    public X11DamageExt getDamageExt() { return damage; }

    /** Returns the XFixes extension binding. */
    public X11FixesExt getFixesExt() { return fixes; }

    /** Returns the MIT-SHM binding, or null if unavailable. */
    public X11ShmExt getShmExt() { return shm; }

    /**
     * Returns the XTest binding, or null if the server lacks XTEST (in which
     * case {@link X11InputForwarder} injects nothing).
     */
    public XTest getXTest() { return xtest; }

    /** Returns the shared Display. */
    public Display getDisplay() { return display; }

    /** Returns the root window. */
    public Window getRoot() { return root; }

    /** Returns the Composite pixmap id for a window, or -1 if not tracked. */
    public int getPixmapId(int windowId) {
        Integer id = pixmapMap.get(windowId);
        return (id != null) ? id : -1;
    }

    /** Returns the Damage id for a window, or -1 if not tracked. */
    public int getDamageId(int windowId) {
        Integer id = damageMap.get(windowId);
        return (id != null) ? id : -1;
    }

    /**
     * Registers a listener to be notified when {@code windowId} reports
     * damage. Replaces any existing listener for that window.
     */
    public void addDamageListener(int windowId, DamageListener listener) {
        if (listener != null) {
            damageListeners.put(windowId, listener);
        }
    }

    /** Removes and returns the damage listener for {@code windowId}. */
    public DamageListener removeDamageListener(int windowId) {
        return damageListeners.remove(windowId);
    }

    /** Returns an unmodifiable view of the window→pixmap map. */
    public Map<Integer, Integer> getPixmapMap() {
        return java.util.Collections.unmodifiableMap(pixmapMap);
    }

    /** Returns true if the compositor is active (redirected). */
    public boolean isActive() { return active; }

    /**
     * Returns true if {@code windowId} is lg3d's own Canvas3D host window, which
     * the WM must not manage. False if the own window id is unknown.
     */
    public boolean isOwnWindow(int windowId) {
        return ownWindowId != -1 && ownWindowId == windowId;
    }

    // ------------------------------------------------------------------
    // Shutdown
    // ------------------------------------------------------------------

    /**
     * Tears down the compositor: destroys all Damage objects, unredirects
     * subwindows, and marks the compositor inactive. Safe to call multiple
     * times and from any thread.
     */
    public void shutdown() {
        if (!active) return;
        active = false;

        logger.info("Shutting down X11 compositor");

        // Destroy all Damage objects.
        for (Map.Entry<Integer, Integer> entry : damageMap.entrySet()) {
            try {
                damage.destroy(entry.getValue());
            } catch (gnu.x11.Error ignored) {}
        }
        damageMap.clear();
        pixmapMap.clear();
        damageListeners.clear();

        // Unredirect: windows resume drawing directly to the screen.
        try {
            composite.unredirectSubwindows(root, X11CompositeExt.REDIRECT_AUTOMATIC);
            display.flush();
        } catch (gnu.x11.Error e) {
            logger.log(Level.WARNING, "UnredirectSubwindows failed", e);
        }
    }
}
