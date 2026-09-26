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
import java.util.ArrayList;
import java.util.List;
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
import org.jdesktop.lg3d.displayserver.desktop2d.BluetoothStatus;

/**
 * Bluetooth panel: adapter power, the controllers and known devices, and
 * connect/disconnect for a selected device, over the {@link BluetoothStatus}
 * ({@code bluetoothctl} / {@code rfkill}) seam. The controllers and devices are
 * {@link JList}s (never a combo box) so the panel keeps working hosted offscreen
 * in a {@code SwingNode}. On a host without BlueZ, without an adapter, or with
 * the radio rfkill-blocked, the panel degrades to a clear "no adapter" note
 * rather than failing.
 */
public class BluetoothPanel implements ControlPanel {

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final JLabel warningLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");

    private final DefaultListModel<String> controllerModel = new DefaultListModel<>();
    private final JList<String> controllerList = new JList<>(controllerModel);
    private final DefaultListModel<String> deviceModel = new DefaultListModel<>();
    private final JList<String> deviceList = new JList<>(deviceModel);

    private final List<BluetoothStatus.Controller> controllers = new ArrayList<>();
    private final List<BluetoothStatus.Device> devices = new ArrayList<>();

    private final JCheckBox powerCheck = new JCheckBox("Adapter power");
    private final JButton applyPower = new JButton("Apply");
    private final JButton connect = new JButton("Connect");
    private final JButton disconnect = new JButton("Disconnect");
    private final JButton refresh = new JButton("Refresh");

    public BluetoothPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        warningLabel.setForeground(new Color(160, 90, 20));

        controllerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        controllerList.setVisibleRowCount(6);
        JScrollPane controllerScroll = new JScrollPane(controllerList);
        controllerScroll.setBorder(BorderFactory.createTitledBorder("Adapters"));
        controllerScroll.setPreferredSize(new Dimension(280, 150));

        deviceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deviceList.setVisibleRowCount(8);
        JScrollPane deviceScroll = new JScrollPane(deviceList);
        deviceScroll.setBorder(BorderFactory.createTitledBorder("Known devices"));
        deviceScroll.setPreferredSize(new Dimension(280, 150));

        JPanel lists = new JPanel(new GridLayout(2, 1, 0, 8));
        lists.add(controllerScroll);
        lists.add(deviceScroll);

        applyPower.addActionListener(e -> applyPower());
        connect.addActionListener(e -> connect());
        disconnect.addActionListener(e -> disconnect());
        refresh.addActionListener(e -> reload());

        JPanel powerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        powerRow.add(powerCheck);
        powerRow.add(applyPower);
        JPanel deviceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        deviceRow.add(connect);
        deviceRow.add(disconnect);
        deviceRow.add(refresh);

        JPanel south = new JPanel(new BorderLayout(2, 2));
        JPanel buttons = new JPanel(new GridLayout(2, 1, 0, 2));
        buttons.add(powerRow);
        buttons.add(deviceRow);
        south.add(buttons, BorderLayout.NORTH);
        south.add(statusLabel, BorderLayout.SOUTH);

        root.add(warningLabel, BorderLayout.NORTH);
        root.add(lists, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        reload();
    }

    @Override
    public String displayName() {
        return "Bluetooth";
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
        controllers.clear();
        devices.clear();
        controllerModel.clear();
        deviceModel.clear();

        if (!BluetoothStatus.available()) {
            warningLabel.setText("bluetoothctl is not available; Bluetooth management is read-only.");
            setControlsEnabled(false);
            statusLabel.setText(" ");
            return;
        }

        List<BluetoothStatus.Controller> found = BluetoothStatus.controllers();
        if (found.isEmpty()) {
            warningLabel.setText(blockedNote(BluetoothStatus.rfkill()));
            setControlsEnabled(false);
            statusLabel.setText("No Bluetooth adapter.");
            return;
        }

        warningLabel.setText(" ");
        setControlsEnabled(true);

        for (BluetoothStatus.Controller c : found) {
            controllers.add(c);
            controllerModel.addElement(BluetoothStatus.label(c));
        }
        controllerList.setSelectedIndex(0);
        powerCheck.setSelected(BluetoothStatus.isPowered());

        for (BluetoothStatus.Device d : BluetoothStatus.devices()) {
            devices.add(d);
            deviceModel.addElement(BluetoothStatus.label(d));
        }
        if (!deviceModel.isEmpty()) {
            deviceList.setSelectedIndex(0);
        }
        statusLabel.setText(controllers.size() + " adapter(s); "
                + devices.size() + " known device(s).");
    }

    private void setControlsEnabled(boolean enabled) {
        controllerList.setEnabled(enabled);
        deviceList.setEnabled(enabled);
        powerCheck.setEnabled(enabled);
        applyPower.setEnabled(enabled);
        connect.setEnabled(enabled);
        disconnect.setEnabled(enabled);
    }

    private BluetoothStatus.Device selectedDevice() {
        int i = deviceList.getSelectedIndex();
        return (i >= 0 && i < devices.size()) ? devices.get(i) : null;
    }

    private void applyPower() {
        boolean on = powerCheck.isSelected();
        boolean ok = BluetoothStatus.setPower(on);
        statusLabel.setText(ok
                ? (on ? "Adapter powered on." : "Adapter powered off.")
                : "Could not change the adapter power.");
        reload();
    }

    private void connect() {
        BluetoothStatus.Device d = selectedDevice();
        if (d == null) {
            statusLabel.setText("Select a device first.");
            return;
        }
        boolean ok = BluetoothStatus.connect(d.mac());
        statusLabel.setText(ok
                ? "Connected to " + d.name() + "."
                : "Could not connect to " + d.name() + ".");
        reload();
    }

    private void disconnect() {
        BluetoothStatus.Device d = selectedDevice();
        if (d == null) {
            statusLabel.setText("Select a device first.");
            return;
        }
        boolean ok = BluetoothStatus.disconnect(d.mac());
        statusLabel.setText(ok
                ? "Disconnected from " + d.name() + "."
                : "Could not disconnect from " + d.name() + ".");
        reload();
    }

    /**
     * The note to show when no adapter is listed, distinguishing an rfkill-blocked
     * radio from a genuinely absent one. Pure so it can be unit-tested headless.
     */
    static String blockedNote(BluetoothStatus.Rfkill rfkill) {
        if (rfkill != null && rfkill.hardBlocked()) {
            return "Bluetooth is hard-blocked by a hardware switch.";
        }
        if (rfkill != null && rfkill.softBlocked()) {
            return "Bluetooth is soft-blocked (rfkill); unblock it to use the adapter.";
        }
        return "No Bluetooth adapter found.";
    }
}
