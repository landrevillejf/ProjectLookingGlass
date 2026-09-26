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
import org.jdesktop.lg3d.displayserver.desktop2d.TimeZoneStatus;

/**
 * Date &amp; Time panel: shows the current time zone and local time, lets the
 * user pick a zone from a {@link JList} (never a combo box, so the panel keeps
 * working hosted offscreen in a {@code SwingNode}) and toggle NTP time
 * synchronization. Everything goes through the {@link TimeZoneStatus} seam, so
 * on a host without systemd the panel degrades to a clear read-only note rather
 * than failing; applying a change normally needs administrator privileges.
 */
public class DateTimePanel implements ControlPanel {

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final JLabel currentLabel = new JLabel(" ");
    private final JLabel warningLabel = new JLabel(" ");
    private final DefaultListModel<String> zoneModel = new DefaultListModel<>();
    private final JList<String> zoneList = new JList<>(zoneModel);
    private final JCheckBox ntpCheck = new JCheckBox("Synchronize date and time automatically (NTP)");
    private final JButton setZone = new JButton("Set time zone");
    private final JButton applyNtp = new JButton("Apply");
    private final JButton refresh = new JButton("Refresh");
    private final JLabel statusLabel = new JLabel(" ");

    public DateTimePanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        warningLabel.setForeground(new Color(160, 90, 20));

        zoneList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        zoneList.setVisibleRowCount(12);
        JScrollPane scroll = new JScrollPane(zoneList);
        scroll.setPreferredSize(new Dimension(340, 240));
        scroll.setBorder(BorderFactory.createTitledBorder("Time zone"));

        setZone.addActionListener(e -> applyZone());
        applyNtp.addActionListener(e -> applyNtp());
        refresh.addActionListener(e -> reload());

        JPanel zoneButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        zoneButtons.add(setZone);
        zoneButtons.add(refresh);
        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.add(scroll, BorderLayout.CENTER);
        center.add(zoneButtons, BorderLayout.SOUTH);

        JPanel north = new JPanel(new BorderLayout(2, 2));
        north.add(currentLabel, BorderLayout.NORTH);
        north.add(warningLabel, BorderLayout.SOUTH);

        JPanel ntpRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        ntpRow.add(ntpCheck);
        ntpRow.add(applyNtp);
        JPanel south = new JPanel(new BorderLayout(2, 2));
        south.add(ntpRow, BorderLayout.NORTH);
        south.add(statusLabel, BorderLayout.SOUTH);

        root.add(north, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        reload();
    }

    @Override
    public String displayName() {
        return "Date & Time";
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
        if (!TimeZoneStatus.available()) {
            warningLabel.setText("timedatectl is not available; date and time settings are read-only.");
            currentLabel.setText(" ");
            zoneModel.clear();
            setControlsEnabled(false);
            statusLabel.setText(" ");
            return;
        }
        warningLabel.setText(" ");
        setControlsEnabled(true);

        String zone = TimeZoneStatus.currentTimezone();
        String local = TimeZoneStatus.currentLocalTime();
        currentLabel.setText("Current zone: " + (zone.isEmpty() ? "(unknown)" : zone)
                + (local.isEmpty() ? "" : "      Local time: " + local));

        List<String> zones = TimeZoneStatus.listTimezones();
        String keep = zoneList.getSelectedValue();
        zoneModel.clear();
        for (String z : zones) {
            zoneModel.addElement(z);
        }
        int sel = zoneIndex(zones, zone);
        if (sel < 0 && keep != null) {
            sel = zones.indexOf(keep);
        }
        if (sel >= 0 && sel < zoneModel.size()) {
            zoneList.setSelectedIndex(sel);
        }
        ntpCheck.setSelected(TimeZoneStatus.isNtpEnabled());
        statusLabel.setText(zones.size() + " time zone(s) available.");
    }

    private void setControlsEnabled(boolean enabled) {
        zoneList.setEnabled(enabled);
        setZone.setEnabled(enabled);
        ntpCheck.setEnabled(enabled);
        applyNtp.setEnabled(enabled);
    }

    private void applyZone() {
        String zone = zoneList.getSelectedValue();
        if (zone == null) {
            statusLabel.setText("Select a time zone first.");
            return;
        }
        boolean ok = TimeZoneStatus.setTimezone(zone);
        statusLabel.setText(ok
                ? "Time zone set to " + zone + "."
                : "Could not set the time zone (administrator privileges may be required).");
        reload();
    }

    private void applyNtp() {
        boolean on = ntpCheck.isSelected();
        boolean ok = TimeZoneStatus.setNtp(on);
        statusLabel.setText(ok
                ? (on ? "Automatic time synchronization enabled."
                      : "Automatic time synchronization disabled.")
                : "Could not change NTP (administrator privileges may be required).");
        reload();
    }

    /**
     * The list index to preselect for the current {@code zone}, or {@code -1} when
     * it is blank or not offered. Pure so it can be unit-tested headless.
     */
    static int zoneIndex(List<String> zones, String zone) {
        if (zones == null || zone == null || zone.isEmpty()) {
            return -1;
        }
        return zones.indexOf(zone);
    }
}
