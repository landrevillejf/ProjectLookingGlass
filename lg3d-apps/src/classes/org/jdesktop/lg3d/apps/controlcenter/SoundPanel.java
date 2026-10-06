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
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.ListSelectionModel;
import org.jdesktop.lg3d.displayserver.desktop2d.VolumeStatus;

/**
 * Sound panel: a master output volume {@link JSlider} and mute toggle, plus a
 * list of the host's output devices (sinks/cards) that can be inspected and
 * switched, all over the {@link VolumeStatus} seam.
 *
 * <p>The volume is a {@code JSlider} (drag-to-adjust, applied on release) and the
 * device selector is a {@link JList} - never a combo box, whose heavyweight
 * popup cannot be hosted offscreen in a {@code SwingNode}. {@code SwingNode}'s
 * renderer forwards drag events to the hidden frame, so the slider works on the
 * 3D desktop as well as the 2D one; the same panel serves both.</p>
 *
 * <p>When no master audio control exists (headless CI, no sound card) the panel
 * degrades to a "no audio device" note with the controls disabled rather than
 * failing, and an empty device list simply disables the switch button.</p>
 */
public class SoundPanel implements ControlPanel {

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final JSlider volumeSlider = new JSlider(0, 100, 50);
    private final JLabel volumeValue = new JLabel("50%");
    private final JCheckBox muteBox = new JCheckBox("Muted");
    private final DefaultListModel<String> deviceNames = new DefaultListModel<>();
    private final JList<String> deviceList = new JList<>(deviceNames);
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton setDefault = new JButton("Set as Default");
    private final JButton refresh = new JButton("Refresh");

    /** The devices behind the current list rows, parallel to {@link #deviceNames}. */
    private final List<VolumeStatus.Device> devices = new ArrayList<>();

    /** Guards the slider/mute listeners while {@link #reload()} syncs them. */
    private boolean syncing;

    public SoundPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        root.add(statusLabel, BorderLayout.NORTH);
        root.add(buildCenter(), BorderLayout.CENTER);
        root.add(buildButtons(), BorderLayout.SOUTH);

        reload();
    }

    /** The master-volume section stacked above the output-device list. */
    private JComponent buildCenter() {
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(buildMaster());
        center.add(Box.createVerticalStrut(8));
        center.add(buildDevices());
        return center;
    }

    /** The master volume slider, its live percentage read-out and mute box. */
    private JComponent buildMaster() {
        volumeSlider.setMajorTickSpacing(20);
        volumeSlider.setMinorTickSpacing(5);
        volumeSlider.setPaintTicks(true);
        volumeSlider.setPaintLabels(true);
        volumeSlider.addChangeListener(e -> onSliderChange());

        muteBox.addActionListener(e -> onMuteToggle());

        JPanel readout = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        readout.add(new JLabel("Master volume:"));
        readout.add(volumeValue);

        JPanel master = new JPanel(new BorderLayout(4, 4));
        master.setBorder(BorderFactory.createTitledBorder("Master volume"));
        master.add(readout, BorderLayout.NORTH);
        master.add(volumeSlider, BorderLayout.CENTER);
        JPanel muteWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        muteWrap.add(muteBox);
        master.add(muteWrap, BorderLayout.SOUTH);
        master.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
        return master;
    }

    /** The scrollable output-device list. */
    private JComponent buildDevices() {
        deviceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deviceList.setVisibleRowCount(6);
        JScrollPane scroll = new JScrollPane(deviceList);
        scroll.setBorder(BorderFactory.createTitledBorder("Output devices"));
        scroll.setPreferredSize(new Dimension(360, 150));
        return scroll;
    }

    /** The Set-as-Default / Refresh button row. */
    private JComponent buildButtons() {
        setDefault.addActionListener(e -> setDefaultDevice());
        refresh.addActionListener(e -> reload());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(setDefault);
        buttons.add(refresh);
        return buttons;
    }

    @Override
    public String displayName() {
        return "Sound";
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

    /**
     * Live slider feedback: the percentage read-out tracks every change, but the
     * volume is only pushed to the seam once the user releases the knob (the
     * drag itself leaves {@code getValueIsAdjusting()} true), so dragging does
     * not fire a CLI call per pixel.
     */
    private void onSliderChange() {
        int percent = volumeSlider.getValue();
        volumeValue.setText(percent + "%");
        if (syncing || volumeSlider.getValueIsAdjusting()) {
            return;
        }
        VolumeStatus.setVolume(percent);
    }

    /** Pushes the mute state to the seam when the user toggles the box. */
    private void onMuteToggle() {
        if (syncing) {
            return;
        }
        VolumeStatus.setMuted(muteBox.isSelected());
    }

    /** Re-reads the master volume and the device list, syncing every control. */
    private void reload() {
        syncing = true;
        try {
            Optional<VolumeStatus.Level> level = VolumeStatus.read();
            if (level.isEmpty()) {
                volumeSlider.setEnabled(false);
                muteBox.setEnabled(false);
                muteBox.setSelected(false);
                statusLabel.setText("No audio device (master volume unavailable).");
            } else {
                VolumeStatus.Level value = level.get();
                volumeSlider.setEnabled(true);
                volumeSlider.setValue(value.percent());
                volumeValue.setText(value.percent() + "%");
                muteBox.setEnabled(true);
                muteBox.setSelected(value.muted());
                statusLabel.setText(VolumeStatus.label(value));
            }
            reloadDevices();
        } finally {
            syncing = false;
        }
    }

    /** Re-reads the output devices, refreshing the list and switch button. */
    private void reloadDevices() {
        devices.clear();
        deviceNames.clear();
        for (VolumeStatus.Device device : VolumeStatus.devices()) {
            devices.add(device);
            deviceNames.addElement(VolumeStatus.deviceLabel(device));
        }
        if (deviceNames.isEmpty()) {
            setDefault.setEnabled(false);
            return;
        }
        setDefault.setEnabled(true);
        deviceList.setSelectedIndex(defaultIndex());
    }

    /** The row of the current default device, or 0 when none is flagged. */
    private int defaultIndex() {
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).isDefault()) {
                return i;
            }
        }
        return 0;
    }

    /** The device behind the selected row, or null when nothing is selected. */
    private VolumeStatus.Device selectedDevice() {
        int i = deviceList.getSelectedIndex();
        return (i >= 0 && i < devices.size()) ? devices.get(i) : null;
    }

    /** Makes the selected device the default output, then re-reads the state. */
    private void setDefaultDevice() {
        VolumeStatus.Device device = selectedDevice();
        if (device == null) {
            statusLabel.setText("Select an output device first.");
            return;
        }
        boolean ok = VolumeStatus.setDefault(device.id());
        statusLabel.setText(ok
                ? "Default output set to " + device.description()
                : "Could not switch the default output (no sound server supports it).");
        reload();
    }
}
