/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.controlcenter;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import org.jdesktop.lg3d.displayserver.desktop2d.BatteryStatus;
import org.jdesktop.lg3d.displayserver.desktop2d.BrightnessStatus;
import org.jdesktop.lg3d.utils.system.ThermalService;

/**
 * Power panel: battery charge, screen backlight brightness and the hardware
 * temperature sensors, over the {@link BatteryStatus}, {@link BrightnessStatus}
 * and {@link ThermalService} seams. Brightness is a {@link JList} of decile
 * presets (never a combo box or a slider) so the panel keeps working when hosted
 * offscreen in a {@code SwingNode}; the gauges refresh every two seconds while
 * the panel is shown, exactly like {@link SystemInfoPanel}. Every read-out
 * degrades to an explanatory note when its backing hardware is absent (a desktop
 * with no battery, an uncontrollable backlight, a VM with no sensors) rather than
 * failing.
 */
public class PowerPanel implements ControlPanel {

    /** The brightness presets offered, in percent. */
    static final int[] BRIGHTNESS_PRESETS = {0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100};

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final JLabel batteryLabel = new JLabel("Battery: --");
    private final JProgressBar batteryBar = new JProgressBar(0, 100);
    private final DefaultListModel<String> brightnesses = new DefaultListModel<>();
    private final JList<String> brightnessList = new JList<>(brightnesses);
    private final JTextArea thermalText = new JTextArea();
    private final JLabel brightnessStatus = new JLabel(" ");
    private final JButton applyBrightness = new JButton("Apply");
    private final Timer timer;

    public PowerPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        batteryBar.setStringPainted(false);
        JPanel battery = new JPanel(new BorderLayout(6, 4));
        battery.setBorder(BorderFactory.createTitledBorder("Battery"));
        battery.add(batteryLabel, BorderLayout.NORTH);
        battery.add(batteryBar, BorderLayout.CENTER);

        for (int preset : BRIGHTNESS_PRESETS) {
            brightnesses.addElement(preset + "%");
        }
        brightnessList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        brightnessList.setVisibleRowCount(6);
        JScrollPane brightScroll = new JScrollPane(brightnessList);
        brightScroll.setPreferredSize(new Dimension(140, 150));
        applyBrightness.addActionListener(e -> applyBrightness());
        JPanel brightness = new JPanel(new BorderLayout(4, 4));
        brightness.setBorder(BorderFactory.createTitledBorder("Screen brightness"));
        brightness.add(brightScroll, BorderLayout.CENTER);
        brightness.add(applyBrightness, BorderLayout.SOUTH);

        thermalText.setEditable(false);
        thermalText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane thermalScroll = new JScrollPane(thermalText);
        thermalScroll.setBorder(BorderFactory.createTitledBorder("Temperature sensors"));

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(brightness, BorderLayout.WEST);
        center.add(thermalScroll, BorderLayout.CENTER);

        root.add(battery, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(brightnessStatus, BorderLayout.SOUTH);

        timer = new Timer(2000, e -> refresh());
        refresh();
    }

    @Override
    public String displayName() {
        return "Power";
    }

    @Override
    public javax.swing.Icon icon() {
        return null;
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void onShow() {
        refresh();
        timer.start();
    }

    @Override
    public void onHide() {
        timer.stop();
    }

    // ------------------------------------------------------------------

    private void refresh() {
        refreshBattery();
        refreshBrightness();
        refreshThermal();
    }

    private void refreshBattery() {
        Optional<BatteryStatus.Level> level = BatteryStatus.read();
        if (level.isEmpty()) {
            batteryLabel.setText("No battery (desktop or unsupported host)");
            batteryBar.setValue(0);
            batteryBar.setForeground(BatteryStatus.color(null));
            return;
        }
        BatteryStatus.Level value = level.get();
        batteryLabel.setText(BatteryStatus.label(value));
        batteryBar.setValue(value.percent());
        batteryBar.setForeground(BatteryStatus.color(value));
    }

    private void refreshBrightness() {
        Optional<BrightnessStatus.Level> level = BrightnessStatus.read();
        boolean controllable = BrightnessStatus.isControllable();
        if (level.isPresent()) {
            brightnessList.setSelectedIndex(indexOfBrightness(level.get().percent()));
        } else {
            brightnessList.clearSelection();
        }
        brightnessList.setEnabled(controllable);
        applyBrightness.setEnabled(controllable);
        if (!controllable) {
            brightnessStatus.setText(level.isPresent()
                    ? "Brightness " + level.get().percent()
                            + "% (not controllable from this session)."
                    : "No controllable backlight on this host.");
        } else if (level.isPresent()) {
            brightnessStatus.setText(BrightnessStatus.label(level.get()));
        } else {
            brightnessStatus.setText("Backlight present but unreadable.");
        }
    }

    private void refreshThermal() {
        ThermalService.Sensor cpu = ThermalService.cpuTemperature();
        List<ThermalService.Sensor> sensors = ThermalService.read();
        StringBuilder sb = new StringBuilder();
        if (cpu != null) {
            sb.append(String.format("CPU:  %.1f C  (%s)%n", cpu.getCelsius(), cpu.getLabel()));
            if (cpu.getCritical() != null) {
                sb.append(String.format("      critical %.0f C%n", cpu.getCritical()));
            }
            sb.append('\n');
        }
        if (sensors.isEmpty()) {
            sb.append("No temperature sensors available.\n");
        } else {
            sb.append("All sensors:\n");
            for (ThermalService.Sensor s : sensors) {
                sb.append(String.format("  %-22s %.1f C%n", s.getLabel(), s.getCelsius()));
            }
        }
        thermalText.setText(sb.toString());
        thermalText.setCaretPosition(0);
    }

    private void applyBrightness() {
        int index = brightnessList.getSelectedIndex();
        if (index < 0 || index >= BRIGHTNESS_PRESETS.length) {
            brightnessStatus.setText("Select a brightness level first.");
            return;
        }
        boolean ok = BrightnessStatus.setBrightness(BRIGHTNESS_PRESETS[index]);
        refreshBrightness();
        if (!ok) {
            brightnessStatus.setText(
                    "Could not set the hardware backlight (permission denied?).");
        }
    }

    /**
     * The preset index nearest {@code percent} (clamped). Pure so it can be
     * unit-tested headless.
     */
    static int indexOfBrightness(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        int index = Math.round(clamped / 10f);
        return Math.max(0, Math.min(BRIGHTNESS_PRESETS.length - 1, index));
    }
}
