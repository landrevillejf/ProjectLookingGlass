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

import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Optional;
import java.util.function.IntConsumer;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.Timer;
import javax.swing.event.ChangeEvent;
import com.protonmail.landrevillejf.IconManager;
import org.jdesktop.lg3d.displayserver.desktop2d.BatteryStatus.Level;
import org.jdesktop.lg3d.displayserver.desktop2d.NetworkStatus.State;

/**
 * The taskbar's system-indicator cluster: small text glyphs for master volume,
 * network link and battery charge, sitting left of the clock like a conventional
 * system tray.
 *
 * <p>Two {@link Timer}s poll the platform: a fast one for volume (so the slider
 * feels live) and a slow one for network/battery (which change rarely). Each
 * indicator hides itself when its probe reports nothing (no sound card, no
 * battery), so the cluster degrades gracefully on desktops and non-Linux hosts.
 * The pure seams ({@link VolumeStatus}, {@link NetworkStatus},
 * {@link BatteryStatus}) do the parsing/formatting and are unit-tested; this
 * panel is the thin Swing wiring, and its {@code applyX} methods take an
 * already-read value so they can be driven headless.</p>
 */
final class TaskbarIndicators extends JPanel {

    /** Volume poll interval: quick, so dragging the slider tracks the OS. */
    static final int VOLUME_INTERVAL_MS = 1000;
    /** Network/battery poll interval: these change slowly. */
    static final int STATUS_INTERVAL_MS = 5000;

    /** Edge of the battery/brightness gauge icons, in pixels. */
    static final int GAUGE_SIZE = 14;
    /** Track colour behind a gauge's fill. */
    private static final Color GAUGE_TRACK = new Color(0x33, 0x33, 0x33);
    /** Fill colour of the brightness gauge. */
    private static final Color BRIGHTNESS_FILL = new Color(0xF1, 0xC4, 0x0F);

    private final JLabel volumeLabel = new JLabel();
    private final JLabel brightnessLabel = new JLabel();
    private final JLabel networkLabel = new JLabel();
    private final JLabel batteryLabel = new JLabel();
    private final JSlider volumeSlider = new JSlider(0, 100, 0);
    private final JPopupMenu volumePopup = new JPopupMenu();
    private final JSlider brightnessSlider = new JSlider(0, 100, 0);
    private final JPopupMenu brightnessPopup = new JPopupMenu();

    private final Timer volumeTimer;
    private final Timer statusTimer;

    /** Guards against the slider's own change events fighting a refresh. */
    private boolean adjustingSlider;
    /** Guards against the brightness slider's own change events fighting a refresh. */
    private boolean adjustingBrightnessSlider;

    /**
     * The last percentage the user dragged the brightness slider to. While the
     * hardware backlight is not controllable the poll must not snap the label
     * and slider back to the unchanged hardware reading, or the control looks
     * broken; this remembered value is what is displayed instead.
     */
    private Integer requestedBrightness;

    /**
     * Receives a brightness percentage the hardware refused, so the desktop can
     * apply a software dim and the slider still visibly does something.
     */
    private IntConsumer softwareBrightness;

    TaskbarIndicators() {
        super(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        setOpaque(false);

        for (JLabel label : new JLabel[] {
                volumeLabel, brightnessLabel, networkLabel, batteryLabel}) {
            label.setOpaque(false);
            add(label);
        }

        volumeLabel.setToolTipText("Volume");
        volumeLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showVolumePopup();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showVolumePopup();
            }
        });

        volumeSlider.setMajorTickSpacing(25);
        volumeSlider.setMinorTickSpacing(5);
        volumeSlider.setPaintTicks(true);
        volumeSlider.addChangeListener(this::onSliderChange);
        volumePopup.add(volumeSlider);

        brightnessLabel.setToolTipText("Brightness");
        brightnessLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showBrightnessPopup();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showBrightnessPopup();
            }
        });

        brightnessSlider.setMajorTickSpacing(25);
        brightnessSlider.setMinorTickSpacing(5);
        brightnessSlider.setPaintTicks(true);
        brightnessSlider.addChangeListener(this::onBrightnessSliderChange);
        brightnessPopup.add(brightnessSlider);

        volumeTimer = new Timer(VOLUME_INTERVAL_MS, e -> refreshVolume());
        volumeTimer.setRepeats(true);
        statusTimer = new Timer(STATUS_INTERVAL_MS, e -> refreshStatuses());
        statusTimer.setRepeats(true);

        refreshAll();
        volumeTimer.start();
        statusTimer.start();
    }

    /** Polls all three platform seams and applies the results. */
    void refreshAll() {
        refreshVolume();
        refreshStatuses();
    }

    /** Reads and applies the master volume. */
    void refreshVolume() {
        applyVolume(VolumeStatus.read());
    }

    /** Reads and applies the network, battery and brightness state. */
    void refreshStatuses() {
        applyNetwork(NetworkStatus.read());
        applyBattery(BatteryStatus.read());
        applyBrightness(BrightnessStatus.read(), BrightnessStatus.isControllable());
    }

    /**
     * Registers the fallback invoked with a brightness percentage the hardware
     * backlight refused (root-owned sysfs, no polkit agent), so the desktop can
     * dim itself in software and the control stays meaningful.
     */
    void setSoftwareBrightness(IntConsumer consumer) {
        softwareBrightness = consumer;
    }

    /** Applies an already-read volume (headless-testable seam). */
    void applyVolume(Optional<VolumeStatus.Level> level) {
        VolumeStatus.Level value = level.orElse(null);
        volumeLabel.setVisible(value != null);
        volumeLabel.setText(VolumeStatus.glyph(value));
        volumeLabel.setToolTipText(VolumeStatus.label(value));
        if (value != null) {
            syncSlider(value);
        }
    }

    /** Applies an already-read network state (headless-testable seam). */
    void applyNetwork(State state) {
        networkLabel.setText(NetworkStatus.glyph(state));
        networkLabel.setToolTipText(NetworkStatus.label(state));
    }

    /** Applies an already-read brightness, hiding the glyph when absent. */
    void applyBrightness(Optional<BrightnessStatus.Level> level) {
        applyBrightness(level, BrightnessStatus.isControllable());
    }

    /**
     * Applies an already-read brightness together with whether the hardware
     * backlight is writable. When it is not, a percentage the user dragged to
     * wins over the (unchanged) hardware reading so the value sticks.
     */
    void applyBrightness(Optional<BrightnessStatus.Level> level, boolean controllable) {
        BrightnessStatus.Level value = level.orElse(null);
        if (value == null && requestedBrightness == null) {
            brightnessLabel.setVisible(false);
            return;
        }
        brightnessLabel.setVisible(true);
        int percent;
        if (requestedBrightness != null && !controllable) {
            percent = requestedBrightness;
        } else {
            if (value == null) {
                return;
            }
            percent = value.percent();
            if (controllable) {
                requestedBrightness = null;
            }
        }
        BrightnessStatus.Level shown = new BrightnessStatus.Level(percent);
        brightnessLabel.setText(BrightnessStatus.glyph(shown));
        brightnessLabel.setToolTipText(BrightnessStatus.label(shown)
                + (controllable ? "" : " (software dim)"));
        brightnessLabel.setIcon(gauge(percent, BRIGHTNESS_FILL));
        syncBrightnessSlider(shown);
    }

    /** Applies an already-read battery level, hiding the glyph when absent. */
    void applyBattery(Optional<Level> level) {
        boolean present = level.isPresent();
        batteryLabel.setVisible(present);
        if (!present) {
            return;
        }
        Level value = level.get();
        batteryLabel.setText(BatteryStatus.glyph(value));
        batteryLabel.setToolTipText(BatteryStatus.label(value));
        batteryLabel.setIcon(gauge(value.percent(), BatteryStatus.color(value)));
    }

    /** Stops the polling timers; called when the desktop shuts down. */
    void stop() {
        volumeTimer.stop();
        statusTimer.stop();
    }

    // Package-private accessors for headless tests.

    String volumeText() {
        return volumeLabel.getText();
    }

    String networkText() {
        return networkLabel.getText();
    }

    String batteryText() {
        return batteryLabel.getText();
    }

    String brightnessText() {
        return brightnessLabel.getText();
    }

    Icon batteryIcon() {
        return batteryLabel.getIcon();
    }

    Icon brightnessIcon() {
        return brightnessLabel.getIcon();
    }

    boolean volumeVisible() {
        return volumeLabel.isVisible();
    }

    boolean batteryVisible() {
        return batteryLabel.isVisible();
    }

    boolean brightnessVisible() {
        return brightnessLabel.isVisible();
    }

    private void syncSlider(VolumeStatus.Level value) {
        adjustingSlider = true;
        try {
            volumeSlider.setValue(VolumeStatus.clamp(value.percent()));
        } finally {
            adjustingSlider = false;
        }
    }

    private void onSliderChange(ChangeEvent e) {
        if (adjustingSlider || volumeSlider.getValueIsAdjusting()) {
            return;
        }
        int percent = volumeSlider.getValue();
        VolumeStatus.setVolume(percent);
        volumeLabel.setText(VolumeStatus.glyph(new VolumeStatus.Level(percent, false)));
    }

    private void showVolumePopup() {
        // Refresh from the OS first so the slider opens at the real level.
        refreshVolume();
        int width = volumeSlider.getPreferredSize().width;
        int height = volumePopup.getPreferredSize().height;
        volumePopup.show(volumeLabel, -width / 4, -height);
    }

    private void syncBrightnessSlider(BrightnessStatus.Level value) {
        adjustingBrightnessSlider = true;
        try {
            brightnessSlider.setValue(BrightnessStatus.clamp(value.percent()));
        } finally {
            adjustingBrightnessSlider = false;
        }
    }

    private void onBrightnessSliderChange(ChangeEvent e) {
        if (adjustingBrightnessSlider || brightnessSlider.getValueIsAdjusting()) {
            return;
        }
        applyUserBrightness(brightnessSlider.getValue());
    }

    /**
     * Commits a brightness percentage the user dragged the slider to: tries the
     * hardware, falls back to the software dim when it refuses, and shows the
     * requested value immediately so the control always responds.
     */
    void applyUserBrightness(int percent) {
        requestedBrightness = percent;
        boolean applied = BrightnessStatus.setBrightness(percent);
        if (!applied && softwareBrightness != null) {
            softwareBrightness.accept(percent);
        }
        BrightnessStatus.Level shown = new BrightnessStatus.Level(percent);
        brightnessLabel.setText(BrightnessStatus.glyph(shown));
        brightnessLabel.setIcon(gauge(percent, BRIGHTNESS_FILL));
    }

    private void showBrightnessPopup() {
        // Refresh from the OS first so the slider opens at the real level.
        applyBrightness(BrightnessStatus.read(), BrightnessStatus.isControllable());
        int width = brightnessSlider.getPreferredSize().width;
        int height = brightnessPopup.getPreferredSize().height;
        brightnessPopup.show(brightnessLabel, -width / 4, -height);
    }

    /**
     * A small filled gauge icon for a 0-100 percentage, or null when IconManager
     * is not on the run classpath (the text glyph still carries the value).
     */
    private static Icon gauge(int percent, Color fill) {
        try {
            return IconManager.createProgressIcon(
                    percent / 100.0, GAUGE_SIZE, fill, GAUGE_TRACK);
        } catch (Throwable t) {
            return null;
        }
    }
}
