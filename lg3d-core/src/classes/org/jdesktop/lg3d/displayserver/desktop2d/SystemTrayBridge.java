/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle / JDK 21
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

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.util.Optional;
import javax.swing.Timer;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig.Indicator;

/**
 * Mirrors the master-volume and network-link indicators into the host
 * {@code java.awt.SystemTray} - a real system tray, the counterpart of the
 * in-taskbar {@link TaskbarIndicators} cluster. It is opt-in
 * ({@link DesktopConfig#isIndicatorsSystemTray()}) and each indicator can be
 * toggled individually ({@link DesktopConfig#isIndicatorShown}).
 *
 * <p>Two {@link Timer}s poll the same platform seams the taskbar cluster uses
 * ({@link VolumeStatus}, {@link NetworkStatus}) and push the live reading into
 * each {@link TrayIcon}'s image and tooltip. The glyphs are drawn by
 * {@link IndicatorIcons}, so the tray pictures track state (level, mute, link
 * kind, offline) rather than a static icon.</p>
 *
 * <p>The bridge degrades gracefully: when the host has no system tray
 * ({@link SystemTray#isSupported()} is false - the common case on GNOME/Wayland,
 * which dropped the XEmbed tray protocol) or the JVM is headless, every method
 * is a no-op and {@link #isMirroring()} reports false. The pure
 * {@code *Image}/{@code *Tooltip} helpers are static so they can be asserted
 * headlessly, exactly like the {@link TaskbarIndicators} apply-seams.</p>
 */
final class SystemTrayBridge {

    /** Volume poll interval: quick, so the tray glyph tracks the OS level. */
    static final int VOLUME_INTERVAL_MS = 1000;
    /** Network poll interval: the link state changes slowly. */
    static final int STATUS_INTERVAL_MS = 5000;
    /** Tray-icon edge, in pixels; trays usually want a larger glyph than the bar. */
    static final int ICON_SIZE = 22;

    /** The host tray, or null when unsupported/disabled (every op then no-ops). */
    private final SystemTray tray;
    private TrayIcon volumeTray;
    private TrayIcon networkTray;
    private final Timer volumeTimer;
    private final Timer statusTimer;

    /** Per-indicator visibility from {@link DesktopConfig} (both shown by default). */
    private boolean showVolume = true;
    private boolean showNetwork = true;

    /** Last platform readings, so a config change re-renders without re-probing. */
    private Optional<VolumeStatus.Level> lastVolume = Optional.empty();
    private NetworkStatus.State lastNetwork = NetworkStatus.State.offline();

    /** Builds a bridge against the real host tray (null when unsupported). */
    SystemTrayBridge() {
        this(supported() ? SystemTray.getSystemTray() : null);
    }

    /**
     * Test seam: builds a bridge against a supplied tray. A null {@code tray}
     * reproduces the unsupported host, where every operation is a no-op.
     */
    SystemTrayBridge(SystemTray tray) {
        this.tray = tray;
        this.volumeTimer = new Timer(VOLUME_INTERVAL_MS, e -> refreshVolume());
        this.volumeTimer.setRepeats(true);
        this.statusTimer = new Timer(STATUS_INTERVAL_MS, e -> refreshNetwork());
        this.statusTimer.setRepeats(true);
    }

    /**
     * Whether the host offers a usable system tray. False when headless, when
     * the platform has no tray (GNOME/Wayland), or when probing throws.
     */
    static boolean supported() {
        try {
            return !GraphicsEnvironment.isHeadless() && SystemTray.isSupported();
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when this bridge is actually mirroring into a live host tray. */
    boolean isMirroring() {
        return tray != null;
    }

    /** Reads both indicators once and starts the polling timers. */
    void start() {
        if (tray == null) {
            return;
        }
        refreshVolume();
        refreshNetwork();
        volumeTimer.start();
        statusTimer.start();
    }

    /** Stops polling and removes every tray icon this bridge added. */
    void stop() {
        volumeTimer.stop();
        statusTimer.stop();
        removeVolume();
        removeNetwork();
    }

    /**
     * Applies the desktop config: per-indicator visibility, adding or removing
     * the matching tray icons. The master enable is handled by the owner, which
     * tears the whole bridge down when the mirror is switched off.
     */
    void applyConfig(DesktopConfig cfg) {
        if (cfg == null) {
            return;
        }
        showVolume = cfg.isIndicatorShown(Indicator.VOLUME);
        showNetwork = cfg.isIndicatorShown(Indicator.NETWORK);
        reconcile();
    }

    /** Brings the live tray icons in line with the per-indicator visibility. */
    private void reconcile() {
        if (tray == null) {
            return;
        }
        if (showVolume) {
            addVolume();
        } else {
            removeVolume();
        }
        if (showNetwork) {
            addNetwork();
        } else {
            removeNetwork();
        }
    }

    /** Reads and applies the master volume (headless-testable seam). */
    void refreshVolume() {
        lastVolume = VolumeStatus.read();
        renderVolume();
    }

    /** Reads and applies the network link state (headless-testable seam). */
    void refreshNetwork() {
        lastNetwork = NetworkStatus.read();
        renderNetwork();
    }

    private void renderVolume() {
        if (volumeTray != null) {
            volumeTray.setImage(volumeImage(lastVolume));
            volumeTray.setToolTip(volumeTooltip(lastVolume));
        }
    }

    private void renderNetwork() {
        if (networkTray != null) {
            networkTray.setImage(networkImage(lastNetwork));
            networkTray.setToolTip(networkTooltip(lastNetwork));
        }
    }

    private void addVolume() {
        if (tray == null || volumeTray != null) {
            return;
        }
        TrayIcon icon = new TrayIcon(volumeImage(lastVolume), volumeTooltip(lastVolume));
        icon.setImageAutoSize(true);
        try {
            tray.add(icon);
            volumeTray = icon;
        } catch (AWTException e) {
            volumeTray = null;
        }
    }

    private void addNetwork() {
        if (tray == null || networkTray != null) {
            return;
        }
        TrayIcon icon = new TrayIcon(networkImage(lastNetwork), networkTooltip(lastNetwork));
        icon.setImageAutoSize(true);
        try {
            tray.add(icon);
            networkTray = icon;
        } catch (AWTException e) {
            networkTray = null;
        }
    }

    private void removeVolume() {
        if (tray != null && volumeTray != null) {
            tray.remove(volumeTray);
        }
        volumeTray = null;
    }

    private void removeNetwork() {
        if (tray != null && networkTray != null) {
            tray.remove(networkTray);
        }
        networkTray = null;
    }

    // ------------------------------------------------------------------
    // Pure helpers (headless-testable): image + tooltip per indicator.
    // ------------------------------------------------------------------

    /** The volume tray glyph for a reading; a missing level draws a grey speaker. */
    static Image volumeImage(Optional<VolumeStatus.Level> level) {
        VolumeStatus.Level v = (level == null) ? null : level.orElse(null);
        Integer percent = (v == null) ? null : v.percent();
        boolean muted = v != null && v.muted();
        return IndicatorIcons.volumeImage(percent, muted, ICON_SIZE);
    }

    /** The volume tray tooltip (never null). */
    static String volumeTooltip(Optional<VolumeStatus.Level> level) {
        return VolumeStatus.label((level == null) ? null : level.orElse(null));
    }

    /** The network tray glyph for a state; null reads as offline. */
    static Image networkImage(NetworkStatus.State state) {
        NetworkStatus.State s = (state == null) ? NetworkStatus.State.offline() : state;
        return IndicatorIcons.networkImage(s.kind(), ICON_SIZE);
    }

    /** The network tray tooltip (never null). */
    static String networkTooltip(NetworkStatus.State state) {
        return NetworkStatus.label(state);
    }
}
