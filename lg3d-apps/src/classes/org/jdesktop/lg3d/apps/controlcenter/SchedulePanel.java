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
import org.jdesktop.lg3d.utils.schedule.ScheduleEntry;
import org.jdesktop.lg3d.utils.schedule.ScheduleService;

/**
 * Schedule panel: two <strong>independent</strong> schedules that no longer
 * share any control.
 *
 * <ul>
 *   <li><b>Wallpaper schedule</b> - its own on/off plus a dynamic, ordered list
 *       of {@code (time -> wallpaper)} entries. The user adds or removes entries
 *       freely (two, four, ten...) and edits each one's time and wallpaper on
 *       its own.</li>
 *   <li><b>Lighting schedule</b> - its own on/off plus its own dawn and dusk
 *       times and fade width. It fades the scene light (3D) or the night veil
 *       (2D) and deliberately carries <b>no wallpaper list</b>.</li>
 * </ul>
 *
 * <p>Choice controls are {@code JList} selectors (never combo boxes) because the
 * Control Center is rendered offscreen inside a SwingNode where popup editors
 * misbehave. Construction is headless-safe (no window is realised).</p>
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

    // --- Wallpaper schedule controls -------------------------------------
    private final DefaultListModel<String> wallpaperOnOffNames = new DefaultListModel<>();
    private final JList<String> wallpaperOnOffList = new JList<>(wallpaperOnOffNames);
    /** The variable-length entry list, rendered as "HH:MM  -  wallpaper". */
    private final DefaultListModel<String> entryNames = new DefaultListModel<>();
    private final JList<String> entryList = new JList<>(entryNames);
    private final JButton addButton = new JButton("Add");
    private final JButton removeButton = new JButton("Remove");
    /** Per-selected-entry editor. */
    private final JSpinner entryHourSpinner =
            new JSpinner(new SpinnerNumberModel(0, 0, 23, 1));
    private final JSpinner entryMinuteSpinner =
            new JSpinner(new SpinnerNumberModel(0, 0, 59, 1));
    private final DefaultListModel<String> entryWallpaperNames = new DefaultListModel<>();
    private final JList<String> entryWallpaperList = new JList<>(entryWallpaperNames);

    // --- Lighting schedule controls (no wallpaper list) ------------------
    private final DefaultListModel<String> lightingOnOffNames = new DefaultListModel<>();
    private final JList<String> lightingOnOffList = new JList<>(lightingOnOffNames);
    private final JSpinner dawnHourSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 23, 1));
    private final JSpinner dawnMinuteSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 59, 1));
    private final JSpinner duskHourSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 23, 1));
    private final JSpinner duskMinuteSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 59, 1));
    private final JSpinner rampSpinner;

    /** Working copy of the wallpaper entries being edited (committed on Apply). */
    private final List<ScheduleEntry> working = new ArrayList<>();
    /** Index of the entry currently loaded into the per-entry editor. */
    private int editingIndex = -1;

    public SchedulePanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // On/off selectors (independent per schedule).
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

        rampSpinner = new JSpinner(new SpinnerNumberModel(
                DesktopConfig.DEFAULT_RAMP_MINUTES,
                DesktopConfig.MIN_RAMP_MINUTES,
                DesktopConfig.MAX_RAMP_MINUTES, 5));

        // Wallpaper entry list + per-entry wallpaper selector.
        entryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane entryScroll = new JScrollPane(entryList);
        entryScroll.setPreferredSize(new Dimension(320, 110));
        entryWallpaperList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane entryWallpaperScroll = new JScrollPane(entryWallpaperList);
        entryWallpaperScroll.setPreferredSize(new Dimension(200, 96));

        entryList.addListSelectionListener(ev -> {
            if (ev.getValueIsAdjusting()) {
                return;
            }
            int idx = entryList.getSelectedIndex();
            if (idx >= 0 && idx != editingIndex) {
                commitEditor();
                loadEditor(idx);
            }
        });
        addButton.addActionListener(e -> addEntry());
        removeButton.addActionListener(e -> removeEntry());

        // --- Wallpaper section layout ---
        JPanel wpEnable = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        wpEnable.add(new JLabel("Enabled:"));
        wpEnable.add(wallpaperOnOffScroll);

        JPanel wpButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        wpButtons.add(addButton);
        wpButtons.add(removeButton);

        JPanel wpEditor = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        wpEditor.add(new JLabel("At:"));
        wpEditor.add(entryHourSpinner);
        wpEditor.add(new JLabel(":"));
        wpEditor.add(entryMinuteSpinner);
        wpEditor.add(new JLabel("Wallpaper:"));
        wpEditor.add(entryWallpaperScroll);

        JPanel wpSouth = new JPanel(new BorderLayout(0, 4));
        wpSouth.add(wpButtons, BorderLayout.NORTH);
        wpSouth.add(wpEditor, BorderLayout.SOUTH);

        JPanel wallpaperPanel = new JPanel(new BorderLayout(6, 6));
        wallpaperPanel.add(wpEnable, BorderLayout.NORTH);
        wallpaperPanel.add(entryScroll, BorderLayout.CENTER);
        wallpaperPanel.add(wpSouth, BorderLayout.SOUTH);
        wallpaperPanel.setBorder(BorderFactory.createTitledBorder(
                "Wallpaper schedule (time -> wallpaper, add as many as you like)"));

        // --- Lighting section layout (times only, no photos) ---
        JPanel lgEnable = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        lgEnable.add(new JLabel("Enabled:"));
        lgEnable.add(lightingOnOffScroll);

        JPanel dawnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        dawnPanel.add(new JLabel("Dawn at:"));
        dawnPanel.add(dawnHourSpinner);
        dawnPanel.add(new JLabel(":"));
        dawnPanel.add(dawnMinuteSpinner);

        JPanel duskPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        duskPanel.add(new JLabel("Dusk at:"));
        duskPanel.add(duskHourSpinner);
        duskPanel.add(new JLabel(":"));
        duskPanel.add(duskMinuteSpinner);

        JPanel rampPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        rampPanel.add(new JLabel("Transition (min):"));
        rampPanel.add(rampSpinner);

        JPanel lightingPanel = new JPanel(new GridLayout(4, 1, 2, 2));
        lightingPanel.add(lgEnable);
        lightingPanel.add(dawnPanel);
        lightingPanel.add(duskPanel);
        lightingPanel.add(rampPanel);
        lightingPanel.setBorder(BorderFactory.createTitledBorder(
                "Lighting schedule (day/night fade - no wallpaper)"));

        // --- Apply buttons ---
        JButton applyButton = new JButton("Apply Schedule");
        applyButton.addActionListener(e -> applySchedule());
        JButton applyNowButton = new JButton("Apply Now");
        applyNowButton.addActionListener(e -> applyNow());
        applyNowButton.setToolTipText("Immediately apply the enabled schedule(s) for the current time");
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttonPanel.add(applyButton);
        buttonPanel.add(applyNowButton);

        JLabel helpLabel = new JLabel(
                "<html><i>The wallpaper and lighting schedules are independent: each has its own "
                + "on/off and its own times. The wallpaper schedule is a list of time-&gt;wallpaper "
                + "entries you add or remove (2, 4, 10...). The lighting schedule only fades the "
                + "scene light (3D) or night veil (2D) between its dawn and dusk times and needs no "
                + "wallpaper. Transition is the fade width in minutes (0 = instant).</i></html>");

        JPanel center = new JPanel(new GridLayout(2, 1, 6, 6));
        center.add(wallpaperPanel);
        center.add(lightingPanel);

        JPanel main = new JPanel(new BorderLayout(6, 6));
        main.add(helpLabel, BorderLayout.NORTH);
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

    // ------------------------------------------------------------------
    // Model <-> UI
    // ------------------------------------------------------------------

    private void reload() {
        DesktopConfig cfg = DesktopConfig.get();

        wallpaperOnOffList.setSelectedIndex(cfg.isWallpaperScheduleEnabled() ? 1 : 0);
        lightingOnOffList.setSelectedIndex(cfg.isLightingScheduleEnabled() ? 1 : 0);

        // Lighting's own times + ramp.
        dawnHourSpinner.setValue(cfg.getLightingDawnHour());
        dawnMinuteSpinner.setValue(cfg.getLightingDawnMinute());
        duskHourSpinner.setValue(cfg.getLightingDuskHour());
        duskMinuteSpinner.setValue(cfg.getLightingDuskMinute());
        rampSpinner.setValue(cfg.getRampMinutes());

        // Wallpaper entries working copy. Drop the editing index first so the
        // entry-list selection listener cannot commit stale spinner values from
        // the previous show into the freshly reloaded entries.
        editingIndex = -1;
        working.clear();
        working.addAll(cfg.getWallpaperSchedule());

        // Per-entry wallpaper choices.
        entryWallpaperNames.clear();
        for (String name : enumerate()) {
            entryWallpaperNames.addElement(name);
        }

        refreshEntryList();
        if (!working.isEmpty()) {
            entryList.setSelectedIndex(0);
            loadEditor(0);
        }
    }

    /** Rebuilds the entry list display from the working copy. */
    private void refreshEntryList() {
        entryNames.clear();
        for (ScheduleEntry e : working) {
            entryNames.addElement(label(e));
        }
    }

    private static String label(ScheduleEntry e) {
        String wp = e.wallpaper().isEmpty() ? "(default)" : e.wallpaper();
        return e.formatTime() + "  -  " + wp;
    }

    /** Writes the per-entry editor back into the working copy. */
    private void commitEditor() {
        if (editingIndex < 0 || editingIndex >= working.size()) {
            return;
        }
        String wp = entryWallpaperList.getSelectedValue();
        working.set(editingIndex, new ScheduleEntry(
                (Integer) entryHourSpinner.getValue(),
                (Integer) entryMinuteSpinner.getValue(),
                wp != null ? wp : ScheduleEntry.DEFAULT_WALLPAPER));
        entryNames.set(editingIndex, label(working.get(editingIndex)));
    }

    /** Loads the working entry at {@code idx} into the per-entry editor. */
    private void loadEditor(int idx) {
        editingIndex = idx;
        ScheduleEntry e = working.get(idx);
        entryHourSpinner.setValue(e.hour());
        entryMinuteSpinner.setValue(e.minute());
        if (!e.wallpaper().isEmpty()) {
            entryWallpaperList.setSelectedValue(e.wallpaper(), true);
        }
    }

    private void addEntry() {
        commitEditor();
        working.add(new ScheduleEntry(12, 0, ScheduleEntry.DEFAULT_WALLPAPER));
        refreshEntryList();
        int last = working.size() - 1;
        entryList.setSelectedIndex(last);
        loadEditor(last);
    }

    private void removeEntry() {
        int idx = entryList.getSelectedIndex();
        if (idx < 0 || working.size() <= 1) {
            return; // keep at least one entry
        }
        commitEditor();
        working.remove(idx);
        refreshEntryList();
        int next = Math.min(idx, working.size() - 1);
        entryList.setSelectedIndex(next);
        loadEditor(next);
    }

    // ------------------------------------------------------------------
    // Apply
    // ------------------------------------------------------------------

    private void applySchedule() {
        commitEditor();
        DesktopConfig cfg = DesktopConfig.get();

        boolean wallpaperOn = wallpaperOnOffList.getSelectedIndex() == 1;
        boolean lightingOn = lightingOnOffList.getSelectedIndex() == 1;
        cfg.setWallpaperScheduleEnabled(wallpaperOn);
        cfg.setLightingScheduleEnabled(lightingOn);

        cfg.setWallpaperSchedule(working);

        cfg.setLightingDawnHour((Integer) dawnHourSpinner.getValue());
        cfg.setLightingDawnMinute((Integer) dawnMinuteSpinner.getValue());
        cfg.setLightingDuskHour((Integer) duskHourSpinner.getValue());
        cfg.setLightingDuskMinute((Integer) duskMinuteSpinner.getValue());
        cfg.setRampMinutes((Integer) rampSpinner.getValue());

        cfg.save();

        ScheduleService.get().checkNow();
        statusLabel.setText(describeSchedule(wallpaperOn, lightingOn, working.size()));
    }

    /** Human-readable summary of which schedules are now active. */
    private static String describeSchedule(boolean wallpaperOn, boolean lightingOn, int entries) {
        if (wallpaperOn && lightingOn) {
            return "Schedule enabled: " + entries
                    + " wallpaper entries and the day/night lighting follow their own times";
        } else if (wallpaperOn) {
            return "Schedule enabled: " + entries
                    + " wallpaper entries follow their times (lighting off)";
        } else if (lightingOn) {
            return "Schedule enabled: day/night lighting follows its own times (wallpaper off)";
        }
        return "Schedule disabled";
    }

    private void applyNow() {
        ScheduleService.get().checkNow();
        statusLabel.setText("Applied the enabled schedule(s) for the current time period");
    }

    // ------------------------------------------------------------------

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
