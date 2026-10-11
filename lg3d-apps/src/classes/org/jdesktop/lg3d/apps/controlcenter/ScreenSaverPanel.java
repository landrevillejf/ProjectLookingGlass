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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.DefaultListModel;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D;
import org.jdesktop.lg3d.displayserver.desktop2d.screensaver.ScreenSaver;
import org.jdesktop.lg3d.displayserver.desktop2d.screensaver.ScreenSaverOption;
import org.jdesktop.lg3d.displayserver.desktop2d.screensaver.ScreenSaverRegistry;
import org.jdesktop.lg3d.displayserver.desktop2d.screensaver.ScreenSaverView;
import org.jdesktop.lg3d.displayserver.desktop2d.screensaver.ScreensaverService;
import org.jdesktop.lg3d.displayserver.desktop2d.screensaver.ScreensaverSettings;

/**
 * Screen Saver panel: configures the 2D desktop's idle screen saver. It edits
 * the shared {@link ScreensaverSettings} singleton and, on Apply, persists it
 * and re-arms the running {@link ScreensaverService} through
 * {@link Desktop2D#applyScreensaverConfig()}; "Test" engages a timed preview
 * through {@link Desktop2D#testScreensaver()}.
 *
 * <p><b>Works with or without a live desktop.</b> The saver inventory comes from
 * {@link ScreenSaverRegistry#discover()} and the settings from the store, so the
 * panel is fully functional (enable, timeout, selection, per-saver options) even
 * on the 3D desktop where no {@code ScreensaverService} runs. Only the embedded
 * live preview needs the running 2D service; when it is absent the preview pane
 * shows a note instead, and the settings still save for the next start.</p>
 *
 * <p><b>Offscreen-safe controls.</b> Following the Control Center rulebook, a
 * choice is a {@link JList} (never a {@code JComboBox}), a toggle is a
 * {@link JCheckBox}, a bounded number a {@link JSlider}/{@link JSpinner} and
 * free text a {@link JTextField} - none of which needs a popup or raw key
 * capture. The per-saver option editors are built generically from each saver's
 * {@link ScreenSaverOption} schema, so a newly installed saver becomes
 * configurable with no change here.</p>
 *
 * <p>Construction is headless-safe: no top-level window is realised and the
 * preview (which is the only thing that needs a displayable screen) is built
 * lazily in {@link #onShow()}, so a headless test that never calls it is CI
 * safe.</p>
 */
public class ScreenSaverPanel implements ControlPanel {

    /** Preview box size (fits within the 720x500 panel alongside the controls). */
    private static final Dimension PREVIEW_SIZE = new Dimension(280, 176);

    private final ScreensaverSettings settings = ScreensaverSettings.get();

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final JLabel statusLabel = new JLabel(" ");

    // --- Enable / timing section ----------------------------------------
    private final JCheckBox enableCheck =
            new JCheckBox("Start the screen saver when the desktop is idle");
    private final JSpinner timeoutSpinner = new JSpinner(new SpinnerNumberModel(
            ScreensaverSettings.DEFAULT_TIMEOUT_SEC,
            ScreensaverSettings.MIN_TIMEOUT_SEC,
            ScreensaverSettings.MAX_TIMEOUT_SEC, 15));
    private final JCheckBox cycleCheck =
            new JCheckBox("Cycle through all installed savers");
    private final JCheckBox suppressCheck = new JCheckBox(
            "Postpone while a window fills the screen (presentations, slideshows)");

    // --- Saver selection ------------------------------------------------
    private final DefaultListModel<String> saverNames = new DefaultListModel<>();
    private final JList<String> saverList = new JList<>(saverNames);
    private final JLabel descriptionLabel = new JLabel(" ");

    // --- Per-saver option editors (rebuilt when the selection changes) --
    private final JPanel optionsPanel = new JPanel();
    /** The options currently rendered, paired with their editor control. */
    private final List<ScreenSaverOption> shownOptions = new ArrayList<>();
    private final Map<String, JComponent> optionEditors = new LinkedHashMap<>();
    /** The saver id whose options are currently on screen; edits made before a
     *  selection change are committed against it. */
    private String renderedSaverId;
    /** saverId -> (optionKey -> edited value); committed on Apply. */
    private final Map<String, Map<String, String>> workingOptions =
            new LinkedHashMap<>();

    // --- Live preview (only when a ScreensaverService is running) -------
    private final JPanel previewPanel = new JPanel(new BorderLayout());
    private final JLabel previewNote = new JLabel(
            "<html><i>Live preview is available on the 2D desktop. Your "
            + "settings are saved either way.</i></html>");
    private JComponent previewView;

    /** The installed savers, refreshed on each show; index-aligned to the list. */
    private List<ScreenSaver> savers = List.of();

    public ScreenSaverPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        saverList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        saverList.setVisibleRowCount(5);
        saverList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                saverSelected();
            }
        });

        cycleCheck.addActionListener(e -> timeoutSpinner.setEnabled(
                !cycleCheck.isSelected()));

        optionsPanel.setLayout(new BoxLayout(optionsPanel, BoxLayout.Y_AXIS));

        JPanel enableSection = new JPanel(new GridLayout(0, 1, 2, 2));
        enableSection.add(enableCheck);
        JPanel timeoutRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        timeoutRow.add(new JLabel("Idle timeout (seconds):"));
        timeoutRow.add(timeoutSpinner);
        enableSection.add(timeoutRow);
        enableSection.add(cycleCheck);
        enableSection.add(suppressCheck);
        enableSection.setBorder(BorderFactory.createTitledBorder("When"));

        JPanel saverBox = new JPanel(new BorderLayout(6, 4));
        saverBox.add(new JScrollPaneBox(saverList).scroll(), BorderLayout.CENTER);
        JPanel descRow = new JPanel(new BorderLayout());
        descRow.add(descriptionLabel, BorderLayout.NORTH);
        saverBox.add(descRow, BorderLayout.SOUTH);
        saverBox.setBorder(BorderFactory.createTitledBorder("Saver"));

        JPanel optionsBox = new JPanel(new BorderLayout());
        optionsBox.add(optionsPanel, BorderLayout.NORTH);
        optionsBox.setBorder(BorderFactory.createTitledBorder("Saver options"));

        previewPanel.setPreferredSize(PREVIEW_SIZE);
        previewPanel.setMinimumSize(PREVIEW_SIZE);
        previewPanel.setBorder(BorderFactory.createTitledBorder("Preview"));
        previewPanel.add(previewNote, BorderLayout.CENTER);

        // Left column: enable + saver + options; right column: preview.
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        for (JPanel section : List.of(enableSection, saverBox, optionsBox)) {
            section.setAlignmentX(Component.LEFT_ALIGNMENT);
            section.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                    section.getPreferredSize().height));
            controls.add(section);
            controls.add(Box.createVerticalStrut(6));
        }

        JPanel center = new JPanel(new BorderLayout(8, 0));
        center.add(controls, BorderLayout.CENTER);
        center.add(previewPanel, BorderLayout.EAST);

        JButton testButton = new JButton("Test");
        testButton.setToolTipText("Preview the selected saver for a few seconds");
        testButton.addActionListener(e -> Desktop2D.testScreensaver());
        JButton applyButton = new JButton("Apply");
        applyButton.addActionListener(e -> apply());
        JButton resetButton = new JButton("Reset to defaults");
        resetButton.addActionListener(e -> resetToDefaults());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(testButton);
        buttons.add(applyButton);
        buttons.add(resetButton);

        root.add(center, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.NORTH);
        root.add(statusLabel, BorderLayout.SOUTH);

        reload();
    }

    @Override
    public String displayName() {
        return "Screen Saver";
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
        refreshPreview();
    }

    @Override
    public void onHide() {
        stopPreview();
    }

    // ------------------------------------------------------------------
    // Model <-> UI
    // ------------------------------------------------------------------

    /** Re-reads the settings and rebuilds the list, options and preview. */
    private void reload() {
        settings.load();
        savers = ScreenSaverRegistry.discover();

        enableCheck.setSelected(settings.isEnabled());
        timeoutSpinner.setValue(settings.getTimeoutSec());
        cycleCheck.setSelected(settings.isCycleAll());
        suppressCheck.setSelected(settings.isSuppressFullscreen());
        timeoutSpinner.setEnabled(!settings.isCycleAll());

        saverNames.clear();
        for (ScreenSaver s : savers) {
            saverNames.addElement(s.displayName());
        }

        workingOptions.clear();
        // Discard any editors left from the previous show so the first
        // commit inside showSaverOptions() is a no-op and the reload reads
        // purely from the persisted store.
        shownOptions.clear();
        optionEditors.clear();
        renderedSaverId = null;

        int idx = selectedIndexFor(settings.getSaverId());
        if (idx >= 0 && idx < savers.size()) {
            saverList.setSelectedIndex(idx);
        } else if (!savers.isEmpty()) {
            saverList.setSelectedIndex(0);
        }
        showSaverOptions(selected());
    }

    /** Maps a stored saver id (or blank = "default") to a list index. */
    private int selectedIndexFor(String id) {
        ScreenSaver byId = ScreenSaverRegistry.byId(savers, id);
        return byId == null ? -1 : savers.indexOf(byId);
    }

    private ScreenSaver selected() {
        int idx = saverList.getSelectedIndex();
        return idx >= 0 && idx < savers.size() ? savers.get(idx) : null;
    }

    private void saverSelected() {
        ScreenSaver saver = selected();
        if (saver != null) {
            descriptionLabel.setText("<html><i>" + saver.description()
                    + "</i></html>");
        }
        showSaverOptions(saver);
        refreshPreview();
    }

    /**
     * Rebuilds the option editors for {@code saver}, first committing the
     * values currently shown for the previous saver into the working copy.
     */
    private void showSaverOptions(ScreenSaver saver) {
        commitShownOptions();
        optionsPanel.removeAll();
        shownOptions.clear();
        optionEditors.clear();
        if (saver != null) {
            Map<String, String> stored = workingOptions.computeIfAbsent(
                    saver.id(), id -> new LinkedHashMap<>(
                            settings.optionsFor(id)));
            for (ScreenSaverOption option : saver.options()) {
                String value = option.coerce(
                        stored.getOrDefault(option.getKey(),
                                option.getDefaultValue()));
                JComponent editor = buildEditor(option, value);
                if (editor == null) {
                    continue;
                }
                shownOptions.add(option);
                optionEditors.put(option.getKey(), editor);
                optionsPanel.add(editorRow(option, editor));
            }
            if (shownOptions.isEmpty()) {
                optionsPanel.add(rightAligned(new JLabel(
                        "<html><i>This saver has no options.</i></html>")));
            }
        }
        optionsPanel.revalidate();
        optionsPanel.repaint();
        renderedSaverId = saver == null ? null : saver.id();
    }

    /** Writes the current editors back into the working copy for their saver. */
    private void commitShownOptions() {
        if (shownOptions.isEmpty()) {
            return;
        }
        String id = renderedSaverId;
        if (id == null) {
            return;
        }
        Map<String, String> target = workingOptions.computeIfAbsent(
                id, k -> new LinkedHashMap<>());
        for (ScreenSaverOption option : shownOptions) {
            JComponent editor = optionEditors.get(option.getKey());
            if (editor != null) {
                target.put(option.getKey(), readValue(option, editor));
            }
        }
    }

    // ------------------------------------------------------------------
    // Generic option editors
    // ------------------------------------------------------------------

    /** One labelled row holding {@code editor}, left aligned for the column. */
    private JPanel editorRow(ScreenSaverOption option, JComponent editor) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        JLabel label = new JLabel(option.getLabel() + ":");
        label.setPreferredSize(new Dimension(150, 20));
        row.add(label, BorderLayout.WEST);
        row.add(editor, BorderLayout.CENTER);
        return rightAligned(row);
    }

    private static <T extends JComponent> T rightAligned(T c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    /** Builds the editor control for one option, seeded with {@code value}. */
    private JComponent buildEditor(ScreenSaverOption option, String value) {
        switch (option.getType()) {
            case TOGGLE: {
                JCheckBox box = new JCheckBox();
                box.setSelected("true".equals(value));
                return box;
            }
            case SLIDER: {
                JSlider slider = new JSlider(option.getMin(), option.getMax());
                slider.setValue(parseInt(value, option.getMin()));
                slider.setMajorTickSpacing(Math.max(1,
                        (option.getMax() - option.getMin()) / 4));
                slider.setPaintTicks(true);
                slider.setPaintLabels(true);
                return slider;
            }
            case CHOICE: {
                DefaultListModel<String> model = new DefaultListModel<>();
                for (String choice : option.getChoices()) {
                    model.addElement(choice);
                }
                JList<String> list = new JList<>(model);
                list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
                list.setVisibleRowCount(Math.min(4, option.getChoices().size()));
                list.setSelectedValue(value, true);
                return new JScrollPaneBox(list).scroll();
            }
            case TEXT:
            default: {
                JTextField field = new JTextField(value, 18);
                return field;
            }
        }
    }

    /** Reads the string value out of an editor control for {@code option}. */
    private String readValue(ScreenSaverOption option, JComponent editor) {
        if (editor instanceof JCheckBox box) {
            return Boolean.toString(box.isSelected());
        }
        if (editor instanceof JSlider slider) {
            return Integer.toString(slider.getValue());
        }
        if (editor instanceof JList<?> list) {
            Object v = list.getSelectedValue();
            return v != null ? v.toString() : option.getDefaultValue();
        }
        if (editor instanceof JScrollPane scroll) {
            // A CHOICE editor is wrapped in a scroll pane; unwrap to the list.
            for (Component c : viewportComponents(scroll)) {
                if (c instanceof JList<?> inner) {
                    Object v = inner.getSelectedValue();
                    return v != null ? v.toString() : option.getDefaultValue();
                }
            }
        }
        if (editor instanceof JTextField field) {
            return field.getText();
        }
        return option.getDefaultValue();
    }

    private static List<Component> viewportComponents(JScrollPane scroll) {
        List<Component> out = new ArrayList<>();
        if (scroll.getViewport() != null
                && scroll.getViewport().getView() instanceof Component view) {
            out.add(view);
        }
        return out;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // Apply / reset
    // ------------------------------------------------------------------

    /** Persists the UI to the shared settings and re-arms the running service. */
    private void apply() {
        // Commit the option editors currently on screen first: the last value
        // the user touched has not been flushed anywhere else.
        commitShownOptions();
        ScreenSaver saver = selected();

        settings.setEnabled(enableCheck.isSelected());
        settings.setTimeoutSec((Integer) timeoutSpinner.getValue());
        settings.setCycleAll(cycleCheck.isSelected());
        settings.setSuppressFullscreen(suppressCheck.isSelected());
        if (saver != null) {
            settings.setSaverId(saver.id());
        }
        for (Map.Entry<String, Map<String, String>> entry
                : workingOptions.entrySet()) {
            for (Map.Entry<String, String> opt : entry.getValue().entrySet()) {
                settings.setOption(entry.getKey(), opt.getKey(), opt.getValue());
            }
        }
        settings.save();
        Desktop2D.applyScreensaverConfig();

        statusLabel.setText(describeState());
        refreshPreview();
    }

    private void resetToDefaults() {
        settings.resetToDefaults();
        reload();
        refreshPreview();
        statusLabel.setText("Screen saver settings reset to defaults");
    }

    private String describeState() {
        if (!enableCheck.isSelected()) {
            return "Screen saver disabled";
        }
        String which = cycleCheck.isSelected()
                ? "cycling through all installed savers"
                : "saver '" + (selected() == null
                        ? "?" : selected().displayName()) + "'";
        return "Screen saver armed: " + timeoutSpinner.getValue()
                + "s idle, " + which;
    }

    // ------------------------------------------------------------------
    // Live preview (needs the running 2D ScreensaverService)
    // ------------------------------------------------------------------

    /** Rebuilds the embedded preview; no-op unless a service is running. */
    private void refreshPreview() {
        ScreensaverService service = Desktop2D.screensaverService();
        ScreenSaver saver = selected();
        if (service == null || saver == null) {
            stopPreview();
            previewPanel.removeAll();
            previewPanel.add(previewNote, BorderLayout.CENTER);
            previewPanel.revalidate();
            previewPanel.repaint();
            return;
        }
        Runnable build = () -> {
            stopPreview();
            JComponent view = service.previewView(saver, PREVIEW_SIZE);
            previewPanel.removeAll();
            if (view != null) {
                previewView = view;
                previewPanel.add(view, BorderLayout.CENTER);
            } else {
                previewPanel.add(previewNote, BorderLayout.CENTER);
            }
            previewPanel.revalidate();
            previewPanel.repaint();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            build.run();
        } else {
            SwingUtilities.invokeLater(build);
        }
    }

    private void stopPreview() {
        if (previewView instanceof ScreenSaverView ssv) {
            try {
                ssv.stop();
            } catch (Throwable ignored) {
                // A preview that will not stop is cosmetic; never crash the UI.
            }
        }
        previewView = null;
    }

    /** Small wrapper so the CHOICE/saver lists get consistent scroll panes. */
    private static final class JScrollPaneBox {
        private final JScrollPane scroll;

        JScrollPaneBox(JList<String> list) {
            scroll = new JScrollPane(list);
        }

        JScrollPane scroll() {
            return scroll;
        }
    }
}
