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
import java.awt.GridLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;

/**
 * Schedule panel for configuring automatic daylight/nightlight wallpaper transitions.
 * Allows users to set transition times and select wallpapers for each period.
 */
public class SchedulePanel implements ControlPanel {

    private static final String BG_DIR = "resources/images/background";

    /** Used when the classpath directory cannot be listed (e.g. jar-only). */
    private static final List<String> FALLBACK = List.of(
            "DreamLakeReflections.jpg",
            "GrandCanyon-0.jpg",
            "Leaves_and_Sky-0.jpg",
            "Stanford-0.jpg");

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final JLabel statusLabel = new JLabel(" ");

    /** Wallpaper / lighting schedule enable selectors (independent toggles). */
    private final DefaultListModel<String> wallpaperOnOffNames = new DefaultListModel<>();
    private final JList<String> wallpaperOnOffList = new JList<>(wallpaperOnOffNames);
    private final DefaultListModel<String> lightingOnOffNames = new DefaultListModel<>();
    private final JList<String> lightingOnOffList = new JList<>(lightingOnOffNames);

    /** Daylight time spinners. */
    private final JSpinner daylightHourSpinner;
    private final JSpinner daylightMinuteSpinner;

    /** Nightlight time spinners. */
    private final JSpinner nightlightHourSpinner;
    private final JSpinner nightlightMinuteSpinner;

    /** Day/night transition ramp width, in minutes. */
    private final JSpinner rampSpinner;

    /** Wallpaper selectors. */
    private final DefaultListModel<String> daylightWallpapers = new DefaultListModel<>();
    private final JList<String> daylightList = new JList<>(daylightWallpapers);
    private final DefaultListModel<String> nightlightWallpapers = new DefaultListModel<>();
    private final JList<String> nightlightList = new JList<>(nightlightWallpapers);

    public SchedulePanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Build the two independent enable/disable selectors
        wallpaperOnOffNames.addElement("Off");
        wallpaperOnOffNames.addElement("On");
        wallpaperOnOffList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        wallpaperOnOffList.setVisibleRowCount(2);
        JScrollPane wallpaperOnOffScroll = new JScrollPane(wallpaperOnOffList);
        wallpaperOnOffScroll.setPreferredSize(new Dimension(72, 58));

        lightingOnOffNames.addElement("Off");
        lightingOnOffNames.addElement("On");
        lightingOnOffList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lightingOnOffList.setVisibleRowCount(2);
        JScrollPane lightingOnOffScroll = new JScrollPane(lightingOnOffList);
        lightingOnOffScroll.setPreferredSize(new Dimension(72, 58));

        // Build time spinners. Each spinner gets its OWN model: sharing one
        // SpinnerNumberModel between the daylight and nightlight spinners would
        // link them, so both periods could never hold different times.
        daylightHourSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 23, 1));
        daylightMinuteSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 59, 1));
        nightlightHourSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 23, 1));
        nightlightMinuteSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 59, 1));
        rampSpinner = new JSpinner(new SpinnerNumberModel(
                DesktopConfig.DEFAULT_RAMP_MINUTES,
                DesktopConfig.MIN_RAMP_MINUTES,
                DesktopConfig.MAX_RAMP_MINUTES, 5));

        // Build wallpaper selectors
        daylightList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nightlightList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane daylightScroll = new JScrollPane(daylightList);
        daylightScroll.setPreferredSize(new Dimension(200, 120));
        JScrollPane nightlightScroll = new JScrollPane(nightlightList);
        nightlightScroll.setPreferredSize(new Dimension(200, 120));

        // Build enable section: wallpaper and lighting are independent toggles
        JPanel enablePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        enablePanel.add(new JLabel("Wallpaper:"));
        enablePanel.add(wallpaperOnOffScroll);
        enablePanel.add(new JLabel("Lighting:"));
        enablePanel.add(lightingOnOffScroll);
        enablePanel.add(new JLabel("Transition (min):"));
        enablePanel.add(rampSpinner);
        enablePanel.setBorder(BorderFactory.createTitledBorder("Enable Schedule"));

        JLabel helpLabel = new JLabel(
                "<html><i>At the scheduled times the desktop can switch wallpaper and/or fade the "
                + "scene lighting (3D) or a night veil (2D) between daylight and nightlight - each "
                + "is an independent on/off. Transition is the lighting fade width in minutes "
                + "(0 = instant).</i></html>");
        JPanel northPanel = new JPanel(new BorderLayout(0, 4));
        northPanel.add(enablePanel, BorderLayout.NORTH);
        northPanel.add(helpLabel, BorderLayout.SOUTH);

        // Build daylight section
        JPanel daylightTimePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        daylightTimePanel.add(new JLabel("Daylight at:"));
        daylightTimePanel.add(daylightHourSpinner);
        daylightTimePanel.add(new JLabel(":"));
        daylightTimePanel.add(daylightMinuteSpinner);

        JPanel daylightPanel = new JPanel(new BorderLayout(6, 6));
        daylightPanel.add(daylightTimePanel, BorderLayout.NORTH);
        daylightPanel.add(daylightScroll, BorderLayout.CENTER);
        daylightPanel.setBorder(BorderFactory.createTitledBorder("Daylight Wallpaper"));

        // Build nightlight section
        JPanel nightlightTimePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        nightlightTimePanel.add(new JLabel("Nightlight at:"));
        nightlightTimePanel.add(nightlightHourSpinner);
        nightlightTimePanel.add(new JLabel(":"));
        nightlightTimePanel.add(nightlightMinuteSpinner);

        JPanel nightlightPanel = new JPanel(new BorderLayout(6, 6));
        nightlightPanel.add(nightlightTimePanel, BorderLayout.NORTH);
        nightlightPanel.add(nightlightScroll, BorderLayout.CENTER);
        nightlightPanel.setBorder(BorderFactory.createTitledBorder("Nightlight Wallpaper"));

        // Build apply button
        JButton applyButton = new JButton("Apply Schedule");
        applyButton.addActionListener(e -> applySchedule());

        JButton applyNowButton = new JButton("Apply Now");
        applyNowButton.addActionListener(e -> applyNow());
        applyNowButton.setToolTipText("Immediately apply the wallpaper for the current time period");

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttonPanel.add(applyButton);
        buttonPanel.add(applyNowButton);

        // Layout
        JPanel center = new JPanel(new GridLayout(2, 1, 6, 6));
        center.add(daylightPanel);
        center.add(nightlightPanel);

        JPanel main = new JPanel(new BorderLayout(6, 6));
        main.add(northPanel, BorderLayout.NORTH);
        main.add(center, BorderLayout.CENTER);
        main.add(buttonPanel, BorderLayout.SOUTH);

        root.add(main, BorderLayout.CENTER);
        root.add(statusLabel, BorderLayout.SOUTH);

        reload();
    }

    @Override
    public String displayName() {
        return "Schedule";
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

    private void reload() {
        DesktopConfig cfg = DesktopConfig.get();

        // Load enable states (independent)
        wallpaperOnOffList.setSelectedIndex(cfg.isWallpaperScheduleEnabled() ? 1 : 0);
        lightingOnOffList.setSelectedIndex(cfg.isLightingScheduleEnabled() ? 1 : 0);

        // Load times
        daylightHourSpinner.setValue(cfg.getDaylightHour());
        daylightMinuteSpinner.setValue(cfg.getDaylightMinute());
        nightlightHourSpinner.setValue(cfg.getNightlightHour());
        nightlightMinuteSpinner.setValue(cfg.getNightlightMinute());
        rampSpinner.setValue(cfg.getRampMinutes());

        // Load wallpaper lists
        daylightWallpapers.clear();
        nightlightWallpapers.clear();
        for (String name : enumerate()) {
            daylightWallpapers.addElement(name);
            nightlightWallpapers.addElement(name);
        }

        // Select current wallpapers
        String daylightWallpaper = cfg.getDaylightWallpaper();
        String nightlightWallpaper = cfg.getNightlightWallpaper();
        if (!daylightWallpaper.isEmpty()) {
            daylightList.setSelectedValue(daylightWallpaper, true);
        }
        if (!nightlightWallpaper.isEmpty()) {
            nightlightList.setSelectedValue(nightlightWallpaper, true);
        }
    }

    private void applySchedule() {
        DesktopConfig cfg = DesktopConfig.get();

        // Save enable states (independent)
        boolean wallpaperOn = wallpaperOnOffList.getSelectedIndex() == 1;
        boolean lightingOn = lightingOnOffList.getSelectedIndex() == 1;
        cfg.setWallpaperScheduleEnabled(wallpaperOn);
        cfg.setLightingScheduleEnabled(lightingOn);

        // Save times
        cfg.setDaylightHour((Integer) daylightHourSpinner.getValue());
        cfg.setDaylightMinute((Integer) daylightMinuteSpinner.getValue());
        cfg.setNightlightHour((Integer) nightlightHourSpinner.getValue());
        cfg.setNightlightMinute((Integer) nightlightMinuteSpinner.getValue());
        cfg.setRampMinutes((Integer) rampSpinner.getValue());

        // Save wallpapers
        String daylightWallpaper = daylightList.getSelectedValue();
        String nightlightWallpaper = nightlightList.getSelectedValue();
        cfg.setDaylightWallpaper(daylightWallpaper != null ? daylightWallpaper : "");
        cfg.setNightlightWallpaper(nightlightWallpaper != null ? nightlightWallpaper : "");

        cfg.save();

        // Ensure the service is running and apply immediately, so the toggles and
        // time edits take effect now (and switching lighting off clears the veil /
        // restores daylight) instead of waiting for the next minute tick.
        org.jdesktop.lg3d.utils.schedule.ScheduleService.get().checkNow();
        statusLabel.setText(describeSchedule(wallpaperOn, lightingOn));
    }

    /** Human-readable summary of which schedules are now active. */
    private static String describeSchedule(boolean wallpaperOn, boolean lightingOn) {
        if (wallpaperOn && lightingOn) {
            return "Schedule enabled: wallpaper and day/night lighting follow the configured times";
        } else if (wallpaperOn) {
            return "Schedule enabled: wallpaper follows the configured times (lighting off)";
        } else if (lightingOn) {
            return "Schedule enabled: day/night lighting follows the configured times (wallpaper off)";
        }
        return "Schedule disabled";
    }

    private void applyNow() {
        org.jdesktop.lg3d.utils.schedule.ScheduleService.get().checkNow();
        statusLabel.setText("Applied the enabled schedule(s) for the current time period");
    }

    private List<String> enumerate() {
        List<String> result = new ArrayList<>();
        java.net.URL dirUrl = getClass().getClassLoader().getResource(BG_DIR);
        if (dirUrl != null && "file".equals(dirUrl.getProtocol())) {
            try {
                File dir = new File(dirUrl.toURI());
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String lower = f.getName().toLowerCase();
                        if (f.isFile() && (lower.endsWith(".jpg")
                                || lower.endsWith(".jpeg") || lower.endsWith(".png"))) {
                            result.add(f.getName());
                        }
                    }
                }
            } catch (Exception e) {
                result.clear();
            }
        }
        Collections.sort(result);
        if (result.isEmpty()) {
            result.addAll(FALLBACK);
        }
        return result;
    }
}
