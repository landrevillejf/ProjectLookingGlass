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
package org.jdesktop.lg3d.apps.texteditor;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;

/**
 * The Settings card: an in-panel form (shown through the editor's centre
 * {@code CardLayout}) rather than a modal dialog, for the same reason as the
 * find bar &mdash; a dialog would escape the 3D desktop's SwingNode capture.
 * Selectors are {@link JList}s, never combo boxes: the offscreen capture
 * path does not present combo popups correctly.
 *
 * <p>The card edits a private {@link EditorSettings} draft; only OK hands the
 * result to the {@link Host}, so Cancel is a genuine no-op. The recent-files
 * list is owned by the panel, not by this card.</p>
 */
public final class SettingsCard extends JPanel {

    /** The panel-side callbacks for OK / Cancel. */
    public interface Host {

        /** Applies (and persists) the edited settings. */
        void applySettings(EditorSettings settings);

        /** Discards the draft and returns to the editor card. */
        void cancelSettings();
    }

    private static final String[] MONOSPACE_HINTS = {
        "mono", "courier", "consolas", "menlo", "source code", "dejavu",
        "liberation", "nimbus",
    };

    private final Host host;
    private final DefaultListModel<String> fontModel = new DefaultListModel<>();
    private final JList<String> fontList = new JList<>(fontModel);
    private final JSpinner sizeSpinner = new JSpinner(
            new SpinnerNumberModel(EditorSettings.DEFAULT_FONT_SIZE,
                    EditorSettings.MIN_FONT_SIZE,
                    EditorSettings.MAX_FONT_SIZE, 1));
    private final JSpinner tabSpinner = new JSpinner(
            new SpinnerNumberModel(4, 1, 16, 1));
    private final JCheckBox hardTabsBox = new JCheckBox("Hard tabs");
    private final JCheckBox wrapBox = new JCheckBox("Word wrap");
    private final JCheckBox lineNumbersBox = new JCheckBox("Line numbers");
    private final JCheckBox autoIndentBox = new JCheckBox("Auto-indent");
    private final JCheckBox highlightBox = new JCheckBox("Syntax highlighting");
    private final DefaultListModel<String> themeModel = new DefaultListModel<>();
    private final JList<String> themeList = new JList<>(themeModel);

    public SettingsCard(Host host) {
        this.host = host;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        for (EditorTheme theme : EditorTheme.builtIn()) {
            themeModel.addElement(theme.getName());
        }
        for (String family : monospaceFamilies()) {
            fontModel.addElement(family);
        }
        fontList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fontList.setVisibleRowCount(8);
        themeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        themeList.setVisibleRowCount(EditorTheme.builtIn().size());

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 10);
        gbc.anchor = GridBagConstraints.WEST;
        int row = 0;
        addRow(form, gbc, row++, "Font:", new JScrollPane(fontList), true);
        addRow(form, gbc, row++, "Size:", sizeSpinner, false);
        addRow(form, gbc, row++, "Tab width:", tabSpinner, false);
        addRow(form, gbc, row++, "Indent with:", hardTabsBox, false);
        addRow(form, gbc, row++, "View:", wrapBox, false);
        addRow(form, gbc, row++, "", lineNumbersBox, false);
        addRow(form, gbc, row++, "Editing:", autoIndentBox, false);
        addRow(form, gbc, row++, "", highlightBox, false);
        addRow(form, gbc, row++, "Theme:", new JScrollPane(themeList), true);

        JButton defaults = new JButton("Restore Defaults");
        defaults.addActionListener(e -> load(EditorSettings.defaults()));
        JButton ok = new JButton("OK");
        ok.addActionListener(e -> host.applySettings(edited()));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> host.cancelSettings());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        buttons.add(defaults);
        buttons.add(ok);
        buttons.add(cancel);

        add(new JLabel("Editor Settings"), BorderLayout.NORTH);
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
    }

    private static void addRow(JPanel form, GridBagConstraints gbc, int row,
            String label, java.awt.Component field, boolean fill) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        form.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.fill = fill ? GridBagConstraints.BOTH : GridBagConstraints.NONE;
        form.add(field, gbc);
    }

    /** The installed font families that look monospaced, logicals first. */
    static List<String> monospaceFamilies() {
        List<String> families = new ArrayList<>();
        families.add("Monospaced");
        try {
            for (String family : GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames()) {
                String key = family.toLowerCase(Locale.ROOT);
                for (String hint : MONOSPACE_HINTS) {
                    if (key.contains(hint) && !families.contains(family)) {
                        families.add(family);
                        break;
                    }
                }
            }
        } catch (RuntimeException rte) {
            // A headless environment without font enumeration still offers
            // the logical "Monospaced" family.
        }
        return families;
    }

    /** Populates the form from a settings snapshot. */
    public void load(EditorSettings settings) {
        EditorSettings source = (settings != null) ? settings
                : EditorSettings.defaults();
        selectOrAdd(fontModel, fontList, source.getFontFamily());
        sizeSpinner.setValue(source.getFontSize());
        tabSpinner.setValue(source.getTabSize());
        hardTabsBox.setSelected(source.isHardTabs());
        wrapBox.setSelected(source.isWordWrap());
        lineNumbersBox.setSelected(source.isLineNumbers());
        autoIndentBox.setSelected(source.isAutoIndent());
        highlightBox.setSelected(source.isHighlight());
        selectOrAdd(themeModel, themeList, source.getThemeName());
    }

    private static void selectOrAdd(DefaultListModel<String> model,
            JList<String> list, String value) {
        if (!model.contains(value)) {
            model.addElement(value);
        }
        list.setSelectedValue(value, true);
    }

    /** Builds the settings draft described by the form. */
    public EditorSettings edited() {
        EditorSettings settings = new EditorSettings();
        settings.setFontFamily(fontList.getSelectedValue());
        settings.setFontSize((Integer) sizeSpinner.getValue());
        settings.setTabSize((Integer) tabSpinner.getValue());
        settings.setHardTabs(hardTabsBox.isSelected());
        settings.setWordWrap(wrapBox.isSelected());
        settings.setLineNumbers(lineNumbersBox.isSelected());
        settings.setAutoIndent(autoIndentBox.isSelected());
        settings.setHighlight(highlightBox.isSelected());
        settings.setThemeName(themeList.getSelectedValue());
        return settings;
    }
}
