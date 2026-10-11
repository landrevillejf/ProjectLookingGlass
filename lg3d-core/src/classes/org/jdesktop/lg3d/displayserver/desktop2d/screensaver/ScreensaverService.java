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
package org.jdesktop.lg3d.displayserver.desktop2d.screensaver;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.geom.Rectangle2D;
import java.awt.image.MemoryImageSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The 2D desktop's screen-saver host: one per running
 * {@link org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D}.
 *
 * <p><b>How it works.</b> A global {@link AWTEventListener} timestamps every
 * key/mouse/motion/wheel event dispatched in the desktop JVM, and a 1-second
 * swing timer compares that timestamp against {@link ScreensaverSettings}.
 * When the idle timeout expires the service covers every screen device with
 * an undecorated always-on-top {@link JWindow} hosting a
 * {@link ScreenSaverView}; any input event - or any pointer movement noticed
 * by the fallback poll - dismisses it and restarts the idle clock. Engagement
 * is postponed while a maximized window fills a screen (a presentation is not
 * blanked mid-act) when {@link ScreensaverSettings#isSuppressFullscreen()}
 * is on, which is the default.</p>
 *
 * <p><b>Idle detection is JVM-scoped by design.</b> The 2D desktop keeps its
 * own windows inside this JVM, so the event listener plus the
 * {@link MouseInfo} pointer poll covers all in-desktop activity, and the
 * pointer poll also notices mouse movement over foreign windows beside the
 * desktop. Keyboard typing into a foreign window cannot be observed from a
 * plain Swing JVM (that would need X11 RECORD/XInput, the retired native fws
 * path); the honest degradation is that the saver may engage during such a
 * session - one mouse move brings the desktop back with nothing lost, since
 * the saver never owns the windows underneath it.</p>
 *
 * <p><b>Robustness contract.</b> A saver that throws while its view is built
 * is dropped for that engagement and the core {@link BlankSaver} engages
 * instead: the feature degrades, the desktop does not. The check tick and the
 * dismiss path are wrapped the same way. Headless JVMs never arm anything and
 * the whole service is a no-op there, which keeps it unit-test-safe.</p>
 *
 * <p><b>Threading.</b> All state mutations run on the EDT. The AWT event
 * listener - the only significant off-EDT caller - writes a volatile
 * timestamp and marshals the dismissal with {@code invokeLater}.</p>
 */
public final class ScreensaverService {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /** How often the idle check and pointer poll run (milliseconds). */
    static final int CHECK_INTERVAL_MS = 1000;

    /** Default duration of a Control Center "Test" engagement (seconds). */
    public static final int TEST_DURATION_SEC = 10;

    /** The event classes that count as user activity. */
    private static final long ACTIVITY_MASK =
            AWTEvent.KEY_EVENT_MASK
            | AWTEvent.MOUSE_EVENT_MASK
            | AWTEvent.MOUSE_MOTION_EVENT_MASK
            | AWTEvent.MOUSE_WHEEL_EVENT_MASK;

    /**
     * Returns true while some application window of the desktop is maximized,
     * so engagement can be postponed; may be null (never postpone).
     */
    private final Supplier<Boolean> presentationProbe;

    private ScreensaverSettings settings = ScreensaverSettings.get();
    private List<ScreenSaver> savers = List.of();

    private AWTEventListener inputListener;
    private Timer checkTimer;
    private Timer testTimer;

    /** Epoch millis of the last observed user activity. */
    private final AtomicLong lastActivity =
            new AtomicLong(System.currentTimeMillis());

    /** Last polled pointer location; null until the first poll. */
    private Point lastPointer;

    private JWindow[] shownWindows = new JWindow[0];
    private ScreenSaverView[] shownViews = new ScreenSaverView[0];
    private boolean active;
    private int cycleIndex;

    public ScreensaverService(Supplier<Boolean> presentationProbe) {
        this.presentationProbe = presentationProbe;
    }

    // ------------------------------------------------------------------
    // Lifecycle (EDT)
    // ------------------------------------------------------------------

    /**
     * (Re)reads the settings and (re)arms idle watching to match: installs
     * the listener + check timer when enabled, dismisses and stops
     * everything when disabled. Safe to call repeatedly - the Control
     * Center's Apply button routes through
     * {@link org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D#applyScreensaverConfig()}
     * which lands here on the EDT. Headless JVMs get a silent no-op.
     */
    public void apply() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        settings = ScreensaverSettings.get();
        settings.load();
        savers = ScreenSaverRegistry.discover();
        if (active) {
            dismiss();
        }
        if (!settings.isEnabled()) {
            stopWatch();
            logger.config("Screen saver disabled");
            return;
        }
        startWatch();
        ScreenSaver saver = selectedSaver();
        if (saver != null && logger.isLoggable(Level.CONFIG)) {
            logger.config("Screen saver armed: " + settings.getTimeoutSec()
                    + "s idle, saver '" + saver.id() + "', "
                    + savers.size() + " saver(s) installed");
        }
    }

    /** Stops everything the service started (desktop shutdown; idempotent). */
    public void dispose() {
        if (active) {
            dismiss();
        }
        stopWatch();
    }

    private void startWatch() {
        if (inputListener == null) {
            final AWTEventListener listener = this::onUserEvent;
            inputListener = listener;
            try {
                Toolkit.getDefaultToolkit().addAWTEventListener(
                        listener, ACTIVITY_MASK);
            } catch (RuntimeException e) {
                // IllegalStateException (Toolkit disposed/unusable) is a
                // RuntimeException. Without the listener the pointer poll
                // alone still watches idleness, so keep running.
                logger.log(Level.WARNING,
                        "Screen-saver input watcher could not be installed;"
                        + " relying on the pointer poll only", e);
            }
        }
        if (checkTimer == null) {
            checkTimer = new Timer(CHECK_INTERVAL_MS, e -> tick());
            checkTimer.setCoalesce(true);
        }
        if (!checkTimer.isRunning()) {
            lastActivity.set(System.currentTimeMillis());
            lastPointer = pollPointer();
            checkTimer.start();
        }
    }

    private void stopWatch() {
        if (checkTimer != null) {
            checkTimer.stop();
            checkTimer = null;
        }
        if (inputListener != null) {
            try {
                Toolkit.getDefaultToolkit()
                        .removeAWTEventListener(inputListener);
            } catch (RuntimeException e) {
                logger.log(Level.FINE,
                        "Idle watcher was not removable", e);
            }
            inputListener = null;
        }
    }

    // ------------------------------------------------------------------
    // Idle detection
    // ------------------------------------------------------------------

    /**
     * Runs for every matching event dispatched in this JVM. While the saver
     * is shown, any such event dismisses it; while hidden, it just refreshes
     * the idle timestamp. Kept allocation-free: this fires on every mouse
     * move.
     */
    private void onUserEvent(AWTEvent event) {
        lastActivity.set(System.currentTimeMillis());
        if (active) {
            SwingUtilities.invokeLater(this::dismissQuietly);
        }
    }

    /** One second of housekeeping, on the EDT. Never throws. */
    private void tick() {
        try {
            if (active || !settings.isEnabled()) {
                return; // dismissal is event-driven; disarmed does nothing
            }
            // Pointer poll: catches activity outside this JVM's event stream
            // (movement over foreign windows beside the desktop).
            Point now = pollPointer();
            if (now != null) {
                if (lastPointer == null || !lastPointer.equals(now)) {
                    lastActivity.set(System.currentTimeMillis());
                }
                lastPointer = now;
            }
            long idleMs = System.currentTimeMillis() - lastActivity.get();
            if (shouldEngage(idleMs, settings.getTimeoutSec(), active,
                    GraphicsEnvironment.isHeadless(),
                    isPresentationRunning())) {
                engage(cycleOrSelected(), false);
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Screen saver check failed", t);
        }
    }

    /**
     * The engagement decision, kept pure (no windows, no clocks, no settings
     * singleton) so it is unit-testable headless: not already shown, not
     * headless, no full-screen presentation running, and idle past the
     * timeout. The enable gate lives in the caller ({@link #tick()} checks
     * it before even computing idle time).
     */
    static boolean shouldEngage(long idleMs, int timeoutSec, boolean active,
                                boolean headless, boolean presentation) {
        return !active && !headless && !presentation
                && idleMs >= (long) timeoutSec * 1000L;
    }

    private static Point pollPointer() {
        try {
            PointerInfo info = MouseInfo.getPointerInfo();
            return info == null ? null : info.getLocation();
        } catch (RuntimeException e) {
            return null; // display vanished; the AWT stream still works
        }
    }

    /** True while the desktop shows a maximized window (may be unknown). */
    private boolean isPresentationRunning() {
        if (presentationProbe == null) {
            return false;
        }
        try {
            Boolean b = presentationProbe.get();
            return b != null && b;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Engage / dismiss (EDT)
    // ------------------------------------------------------------------

    /**
     * Covers every screen with {@code saver}'s view. {@code test} schedules
     * an automatic dismissal after {@link #TEST_DURATION_SEC} seconds. A
     * saver that cannot build its view is retried once through the
     * {@link BlankSaver} failsafe. Must run on the EDT; a no-op when one is
     * already shown or none can be built.
     */
    private void engage(ScreenSaver saver, boolean test) {
        if (active || saver == null) {
            return;
        }
        GraphicsDevice[] devices = screenDevices();
        if (devices.length == 0) {
            return;
        }
        Dimension total = deskSize(devices);
        shownWindows = new JWindow[devices.length];
        shownViews = new ScreenSaverView[devices.length];
        for (int i = 0; i < devices.length; i++) {
            JWindow window = new JWindow();
            JComponent view = buildView(saver, total);
            if (view == null && !BlankSaver.ID.equals(saver.id())) {
                // Broken saver: degrade to the failsafe for this whole
                // engagement rather than a black nothing (or a crashed EDT).
                logger.log(Level.WARNING,
                        "Screen saver {0} failed to start; engaging Blank",
                        saver.id());
                saver = fallbackSaver();
                view = buildView(saver, total);
            }
            if (view == null) {
                break; // even the failsafe failed: give up quietly
            }
            window.setBackground(Color.BLACK);
            window.setAlwaysOnTop(true);
            // Not focusable: input keeps flowing to whatever had focus, and
            // the dismissal arrives through the global event watcher.
            window.setFocusableWindowState(false);
            window.setCursor(invisibleCursor());
            window.setLayout(new BorderLayout());
            window.add(view, BorderLayout.CENTER);
            window.setBounds(devices[i].getDefaultConfiguration()
                    .getBounds());
            shownWindows[i] = window;
            if (view instanceof ScreenSaverView ssv) {
                shownViews[i] = ssv;
            }
            window.setVisible(true);
            window.toFront();
        }
        if (shownWindows[0] == null) {
            hideWindows();
            return;
        }
        active = true;
        if (test) {
            if (testTimer != null) {
                testTimer.stop();
            }
            testTimer = new Timer(TEST_DURATION_SEC * 1000, e -> dismiss());
            testTimer.setRepeats(false);
            testTimer.start();
        }
    }

    /** The virtual desktop bounds (union of all screens), for view sizing. */
    private static Dimension deskSize(GraphicsDevice[] devices) {
        Rectangle2D union = new Rectangle2D.Double();
        boolean first = true;
        for (GraphicsDevice d : devices) {
            Rectangle b = d.getDefaultConfiguration().getBounds();
            if (first) {
                union.setRect(b);
                first = false;
            } else {
                union.add(b);
            }
        }
        return new Dimension((int) Math.round(union.getWidth()),
                (int) Math.round(union.getHeight()));
    }

    /**
     * A fully transparent cursor so the saver never shows a pointer. There is
     * no {@code NONE_CURSOR} constant in {@link Cursor}; hiding the pointer
     * requires a custom 1x1 transparent image cursor. Falls back to the
     * default cursor if the toolkit cannot build one (engage only ever runs
     * on a displayable EDT, so this is a defensive path).
     */
    private static Cursor invisibleCursor() {
        try {
            int[] transparent = new int[1]; // single fully-transparent pixel
            MemoryImageSource mis = new MemoryImageSource(
                    1, 1, transparent, 0, 1);
            Image img = Toolkit.getDefaultToolkit().createImage(mis);
            return Toolkit.getDefaultToolkit().createCustomCursor(
                    img, new Point(0, 0), "lg3d-saver-blank-cursor");
        } catch (RuntimeException e) {
            return Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR);
        }
    }

    /** Builds one started view for {@code saver}, or null on any failure. */
    private JComponent buildView(ScreenSaver saver, Dimension total) {
        try {
            Map<String, String> opts = ScreenSaverRegistry.resolveOptions(
                    saver, settings.optionsFor(saver.id()));
            JComponent view = saver.createView(opts);
            if (view == null) {
                return null;
            }
            view.setPreferredSize(total);
            if (view instanceof ScreenSaverView ssv) {
                ssv.start();
            }
            return view;
        } catch (Throwable t) {
            logger.log(Level.WARNING,
                    "Screen saver view could not be built: " + saver.id(), t);
            return null;
        }
    }

    /** Dismisses if shown; never throws; EDT. */
    private void dismissQuietly() {
        if (active) {
            dismiss();
        }
    }

    /** Hides the saver windows and re-arms the idle clock from now. EDT. */
    public void dismiss() {
        if (testTimer != null) {
            testTimer.stop();
            testTimer = null;
        }
        for (ScreenSaverView v : shownViews) {
            if (v != null) {
                try {
                    v.stop();
                } catch (Throwable t) {
                    logger.log(Level.FINE, "Saver view would not stop", t);
                }
            }
        }
        hideWindows();
        shownViews = new ScreenSaverView[0];
        active = false;
        lastActivity.set(System.currentTimeMillis());
    }

    private void hideWindows() {
        for (JWindow w : shownWindows) {
            if (w != null) {
                w.setVisible(false);
                w.dispose();
            }
        }
        shownWindows = new JWindow[0];
    }

    // ------------------------------------------------------------------
    // Control Center support
    // ------------------------------------------------------------------

    /** True while a saver covers the screens. */
    public boolean isActive() {
        return active;
    }

    /** The installed savers (validated, sorted, failsafe last). */
    public List<ScreenSaver> availableSavers() {
        if (savers.isEmpty()) {
            savers = ScreenSaverRegistry.discover();
        }
        return savers;
    }

    /** The saver selected by id, or the first installed one. Never null
     *  once {@link ScreenSaverRegistry#discover()} has run (Blank last). */
    public ScreenSaver selectedSaver() {
        return ScreenSaverRegistry.byId(availableSavers(),
                settings.getSaverId());
    }

    /** The {@link BlankSaver} from the registry, or a fresh one. */
    private ScreenSaver fallbackSaver() {
        for (ScreenSaver s : availableSavers()) {
            if (BlankSaver.ID.equals(s.id())) {
                return s;
            }
        }
        return new BlankSaver();
    }

    /** The saver that should engage next: the selected one, or the next in
     *  the cycle when "cycle through all" is on. */
    private ScreenSaver cycleOrSelected() {
        List<ScreenSaver> all = availableSavers();
        if (all.isEmpty()) {
            return null;
        }
        if (!settings.isCycleAll()) {
            return ScreenSaverRegistry.byId(all, settings.getSaverId());
        }
        int i = Math.floorMod(cycleIndex, all.size());
        cycleIndex = i + 1;
        return all.get(i);
    }

    /**
     * Shows the next saver (selected, or the cycle's next when
     * "cycle through all" is on) immediately for {@link #TEST_DURATION_SEC}
     * seconds, whatever the enable flag says - the Control Center's "Test"
     * button. No-op headless; safe from any thread.
     */
    public void testNow() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        onEdt(() -> {
            savers = ScreenSaverRegistry.discover();
            engage(cycleOrSelected(), true);
        });
    }

    /**
     * Builds a started preview view for the Control Center's embedded
     * preview, using the saver's currently stored option values at
     * {@code size}. The panel owns the returned component and must call
     * {@link ScreenSaverView#stop()} on it when replacing or hiding it.
     * Returns null headless or on failure. Must be called on the EDT.
     */
    public JComponent previewView(ScreenSaver saver, Dimension size) {
        if (GraphicsEnvironment.isHeadless() || saver == null) {
            return null;
        }
        try {
            Map<String, String> opts = ScreenSaverRegistry.resolveOptions(
                    saver, settings.optionsFor(saver.id()));
            JComponent view = saver.createView(opts);
            if (view == null) {
                return null;
            }
            view.setPreferredSize(size);
            if (view instanceof ScreenSaverView ssv) {
                ssv.start();
            }
            return view;
        } catch (Throwable t) {
            logger.log(Level.WARNING,
                    "Screen saver preview could not be built: "
                    + saver.id(), t);
            return null;
        }
    }

    /** The live settings model (the panel edits it, saves, then applies). */
    public ScreensaverSettings settings() {
        return settings;
    }

    // ------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------

    private static GraphicsDevice[] screenDevices() {
        try {
            GraphicsEnvironment ge =
                    GraphicsEnvironment.getLocalGraphicsEnvironment();
            List<GraphicsDevice> usable = new ArrayList<>();
            for (GraphicsDevice d : ge.getScreenDevices()) {
                // A device with no default configuration cannot host a
                // window (virtual/odd drivers); skip those, keep the rest.
                if (d.getDefaultConfiguration() != null) {
                    usable.add(d);
                }
            }
            return usable.toArray(new GraphicsDevice[0]);
        } catch (Throwable t) {
            return new GraphicsDevice[0];
        }
    }

    /** Runs {@code r} on the EDT (immediately when already there). */
    private static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }
}
