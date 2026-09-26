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
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import org.jdesktop.lg3d.displayserver.desktop2d.InputSettings;
import org.jdesktop.lg3d.utils.system.SystemInfoService;

/**
 * Mouse &amp; Keyboard panel: pointer acceleration/threshold and keyboard
 * auto-repeat rate/delay, over the {@link InputSettings} ({@code xset}) seam.
 * Every choice is a {@link JList} preset (never a combo box or a free
 * key-capture widget) so the panel keeps working hosted offscreen in a
 * {@code SwingNode}. Like the Display panel it warns - and, without an X server,
 * degrades to read-only - under Wayland, since {@code xset} only talks to X.
 */
public class InputPanel implements ControlPanel {

    /** Pointer acceleration multiplier presets. */
    static final int[] ACCELERATIONS = { 1, 2, 3, 4, 5, 6 };
    /** Pointer acceleration threshold presets (pixels of motion). */
    static final int[] THRESHOLDS = { 1, 2, 3, 4, 5, 6, 8, 10 };
    /** Key-repeat delay presets (milliseconds before repeating starts). */
    static final int[] DELAYS = { 200, 300, 400, 500, 600, 800, 1000 };
    /** Key-repeat rate presets (characters per second). */
    static final int[] RATES = { 10, 15, 20, 25, 30, 40, 50 };

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final JLabel currentLabel = new JLabel(" ");
    private final JLabel warningLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");

    private final DefaultListModel<String> accelModel = new DefaultListModel<>();
    private final JList<String> accelList = new JList<>(accelModel);
    private final DefaultListModel<String> thresholdModel = new DefaultListModel<>();
    private final JList<String> thresholdList = new JList<>(thresholdModel);
    private final DefaultListModel<String> delayModel = new DefaultListModel<>();
    private final JList<String> delayList = new JList<>(delayModel);
    private final DefaultListModel<String> rateModel = new DefaultListModel<>();
    private final JList<String> rateList = new JList<>(rateModel);
    private final JCheckBox repeatCheck = new JCheckBox("Repeat keys");
    private final JButton applyMouse = new JButton("Apply");
    private final JButton applyKeyboard = new JButton("Apply");
    private final JButton refresh = new JButton("Refresh");

    public InputPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        warningLabel.setForeground(new Color(160, 90, 20));

        for (int v : ACCELERATIONS) {
            accelModel.addElement(v + "x");
        }
        for (int v : THRESHOLDS) {
            thresholdModel.addElement(String.valueOf(v));
        }
        for (int v : DELAYS) {
            delayModel.addElement(v + " ms");
        }
        for (int v : RATES) {
            rateModel.addElement(v + " /s");
        }

        applyMouse.addActionListener(e -> applyMouse());
        applyKeyboard.addActionListener(e -> applyKeyboard());
        refresh.addActionListener(e -> reload());

        JPanel center = new JPanel(new GridLayout(1, 2, 10, 0));
        center.add(buildMouseColumn());
        center.add(buildKeyboardColumn());

        JPanel north = new JPanel(new BorderLayout(2, 2));
        north.add(currentLabel, BorderLayout.NORTH);
        north.add(warningLabel, BorderLayout.SOUTH);

        JPanel south = new JPanel(new BorderLayout(2, 2));
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(refresh);
        south.add(row, BorderLayout.NORTH);
        south.add(statusLabel, BorderLayout.SOUTH);

        root.add(north, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        accelList.setSelectedIndex(1);   // 2x
        thresholdList.setSelectedIndex(3); // 4
        delayList.setSelectedIndex(3);   // 500 ms
        rateList.setSelectedIndex(3);    // 25 /s

        reload();
    }

    private JComponent buildMouseColumn() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Mouse (pointer acceleration)"));

        accelList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        accelList.setVisibleRowCount(6);
        thresholdList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        thresholdList.setVisibleRowCount(6);

        JPanel lists = new JPanel(new GridLayout(1, 2, 6, 0));
        JScrollPane accelScroll = new JScrollPane(accelList);
        accelScroll.setBorder(BorderFactory.createTitledBorder("Acceleration"));
        accelScroll.setPreferredSize(new Dimension(90, 130));
        JScrollPane thresholdScroll = new JScrollPane(thresholdList);
        thresholdScroll.setBorder(BorderFactory.createTitledBorder("Threshold"));
        thresholdScroll.setPreferredSize(new Dimension(90, 130));
        lists.add(accelScroll);
        lists.add(thresholdScroll);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(applyMouse);

        panel.add(lists, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildKeyboardColumn() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Keyboard (key repeat)"));

        delayList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        delayList.setVisibleRowCount(6);
        rateList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        rateList.setVisibleRowCount(6);

        JPanel lists = new JPanel(new GridLayout(1, 2, 6, 0));
        JScrollPane delayScroll = new JScrollPane(delayList);
        delayScroll.setBorder(BorderFactory.createTitledBorder("Delay"));
        delayScroll.setPreferredSize(new Dimension(90, 130));
        JScrollPane rateScroll = new JScrollPane(rateList);
        rateScroll.setBorder(BorderFactory.createTitledBorder("Rate"));
        rateScroll.setPreferredSize(new Dimension(90, 130));
        lists.add(delayScroll);
        lists.add(rateScroll);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        controls.add(repeatCheck);
        controls.add(applyKeyboard);

        panel.add(lists, BorderLayout.CENTER);
        panel.add(controls, BorderLayout.SOUTH);
        return panel;
    }

    @Override
    public String displayName() {
        return "Mouse & Keyboard";
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
        reload();
    }

    // ------------------------------------------------------------------

    private void reload() {
        boolean xset = InputSettings.available();
        if (!xset) {
            warningLabel.setText("xset is not available (no X server); mouse and "
                    + "keyboard settings are read-only.");
            currentLabel.setText(" ");
            setControlsEnabled(false);
            statusLabel.setText(" ");
            return;
        }
        setControlsEnabled(true);
        if (SystemInfoService.isWayland()) {
            warningLabel.setText("Wayland session detected: xset changes may not "
                    + "apply on this display server.");
        } else {
            warningLabel.setText(" ");
        }

        InputSettings.Pointer p = InputSettings.pointer();
        if (p != null) {
            if (!Double.isNaN(p.acceleration())) {
                int idx = closestIndex(ACCELERATIONS, (int) Math.round(p.acceleration()));
                if (idx >= 0) {
                    accelList.setSelectedIndex(idx);
                }
            }
            if (p.threshold() >= 0) {
                int idx = closestIndex(THRESHOLDS, p.threshold());
                if (idx >= 0) {
                    thresholdList.setSelectedIndex(idx);
                }
            }
            currentLabel.setText("Pointer acceleration: " + formatAccel(p.acceleration())
                    + "      threshold: " + p.threshold());
        } else {
            currentLabel.setText(" ");
        }
        repeatCheck.setSelected(InputSettings.autoRepeatEnabled());
        statusLabel.setText("Repeat rate/delay are presets; xset does not report the current values.");
    }

    private void setControlsEnabled(boolean enabled) {
        accelList.setEnabled(enabled);
        thresholdList.setEnabled(enabled);
        delayList.setEnabled(enabled);
        rateList.setEnabled(enabled);
        repeatCheck.setEnabled(enabled);
        applyMouse.setEnabled(enabled);
        applyKeyboard.setEnabled(enabled);
    }

    private void applyMouse() {
        int ai = accelList.getSelectedIndex();
        int ti = thresholdList.getSelectedIndex();
        if (ai < 0 || ti < 0) {
            statusLabel.setText("Select an acceleration and a threshold first.");
            return;
        }
        boolean ok = InputSettings.applyMouse(
                Integer.toString(ACCELERATIONS[ai]), Integer.toString(THRESHOLDS[ti]));
        statusLabel.setText(ok
                ? "Pointer settings applied."
                : "Could not apply pointer settings (is an X server running?).");
    }

    private void applyKeyboard() {
        int di = delayList.getSelectedIndex();
        int ri = rateList.getSelectedIndex();
        boolean on = repeatCheck.isSelected();
        boolean okToggle = InputSettings.setAutoRepeat(on);
        boolean okRate = (di >= 0 && ri >= 0)
                ? InputSettings.applyRepeatRate(DELAYS[di], RATES[ri])
                : okToggle;
        boolean ok = okToggle && okRate;
        statusLabel.setText(ok
                ? "Keyboard repeat settings applied."
                : "Could not apply keyboard settings (is an X server running?).");
    }

    /** Formats an acceleration multiplier for the read-out. Pure/testable. */
    static String formatAccel(double acceleration) {
        if (Double.isNaN(acceleration)) {
            return "(unknown)";
        }
        if (acceleration == Math.rint(acceleration)) {
            return ((int) acceleration) + "x";
        }
        return String.format("%.2fx", acceleration);
    }

    /**
     * The index of the preset closest to {@code target} (ties resolve to the lower
     * index), or {@code -1} for an empty table. Pure so it can be unit-tested.
     */
    static int closestIndex(int[] values, int target) {
        if (values == null || values.length == 0) {
            return -1;
        }
        int best = 0;
        int bestDist = Math.abs(values[0] - target);
        for (int i = 1; i < values.length; i++) {
            int dist = Math.abs(values[i] - target);
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }
}
