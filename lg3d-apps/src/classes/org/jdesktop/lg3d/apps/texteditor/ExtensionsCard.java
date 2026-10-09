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
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

/**
 * The Extensions card. The left half lists every installed
 * {@link TextEditorExtension} with its enable / disable state and offers
 * Enable / Disable buttons (a third-party extension only contributes actions
 * once the user enables it here); the right half lists every action those
 * extensions contribute, grouped under bold, non-selectable category headers.
 * Each action row is labelled {@code <extension>: <action> (accelerator)} and
 * is bound to its index in the host's flat action list, so selecting an entry
 * and pressing Run (or double-clicking) invokes exactly that action against the
 * current tab. Headers are inert: Run stays disabled while one is selected.
 * The card uses plain {@link JList}s, so it works inside the 3D desktop's
 * offscreen capture like every other in-panel surface.
 */
public final class ExtensionsCard extends JPanel {

    /** The panel-side callbacks for the extensions card. */
    public interface Host {

        /** Runs the action with the given index into the host's action list. */
        void runExtensionAction(int index);

        /** Returns to the editor card. */
        void closeCard();

        /** @return one row per installed extension, for the management list. */
        default List<ExtensionInfo> extensionInfos() {
            return List.of();
        }

        /** Enables or disables an extension; the host refreshes the card. */
        default void setExtensionEnabled(String id, boolean enabled) {
            // no-op default keeps third-party hosts source-compatible
        }
    }

    /**
     * One installed extension shown in the management list. {@code enabled}
     * drives the bold / greyed rendering and the Enable vs Disable button.
     */
    public record ExtensionInfo(String id, String name, String version,
                                String category, boolean enabled) {

        @Override
        public String toString() {
            String v = (version == null || version.isBlank()) ? "" : " v" + version;
            String cat = (category == null || category.isBlank()) ? "" : " \u2014 " + category;
            return name + v + cat + "  [" + (enabled ? "Enabled" : "Disabled") + "]";
        }
    }

    /**
     * One row in the actions list: either a non-selectable category
     * {@code header} or a runnable action bound to {@code actionIndex} in the
     * host's flat action list (headers carry {@code -1}).
     */
    public record Row(String text, int actionIndex, boolean header) {

        /** A bold, inert category heading row. */
        public static Row header(String category) {
            return new Row(category, -1, true);
        }

        /** A runnable action row wired to the given host action index. */
        public static Row action(String text, int actionIndex) {
            return new Row(text, actionIndex, false);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private final Host host;
    private final DefaultListModel<Row> model = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(model);
    private final JButton runButton = new JButton("Run");

    private final DefaultListModel<ExtensionInfo> extModel = new DefaultListModel<>();
    private final JList<ExtensionInfo> extList = new JList<>(extModel);
    private final JButton enableButton = new JButton("Enable");
    private final JButton disableButton = new JButton("Disable");

    public ExtensionsCard(Host host) {
        this.host = host;
        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(16);
        list.setCellRenderer(new RowRenderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                runButton.setEnabled(isAction(list.getSelectedIndex()));
            }
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int index = list.getSelectedIndex();
                if (e.getClickCount() == 2 && isAction(index)) {
                    host.runExtensionAction(model.get(index).actionIndex());
                }
            }
        });

        runButton.addActionListener(e -> {
            int index = list.getSelectedIndex();
            if (isAction(index)) {
                host.runExtensionAction(model.get(index).actionIndex());
            }
        });
        JButton back = new JButton("Back to Editor");
        back.addActionListener(e -> host.closeCard());

        extList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        extList.setVisibleRowCount(16);
        extList.setCellRenderer(new ExtensionRenderer());
        extList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                refreshToggleButtons();
            }
        });
        enableButton.addActionListener(e -> applyEnabled(true));
        disableButton.addActionListener(e -> applyEnabled(false));

        JPanel extButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        extButtons.add(enableButton);
        extButtons.add(disableButton);
        JPanel extensions = new JPanel(new BorderLayout(4, 4));
        extensions.add(new JLabel("Installed Extensions"), BorderLayout.NORTH);
        extensions.add(new JScrollPane(extList), BorderLayout.CENTER);
        extensions.add(extButtons, BorderLayout.SOUTH);

        JPanel actions = new JPanel(new BorderLayout(4, 4));
        actions.add(new JLabel("Extension Actions, grouped by category "
                + "(double-click to run)"), BorderLayout.NORTH);
        actions.add(new JScrollPane(list), BorderLayout.CENTER);

        JPanel columns = new JPanel(new GridLayout(1, 2, 8, 0));
        columns.add(extensions);
        columns.add(actions);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        buttons.add(runButton);
        buttons.add(back);

        add(columns, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        runButton.setEnabled(false);
        enableButton.setEnabled(false);
        disableButton.setEnabled(false);
    }

    /** @return true when the row at {@code modelIndex} is a runnable action. */
    private boolean isAction(int modelIndex) {
        return modelIndex >= 0 && modelIndex < model.size()
                && !model.get(modelIndex).header();
    }

    /**
     * Replaces the shown entries with a flat, sequentially-indexed action list
     * (no category headers); index order matches {@code labels}. The management
     * list is left untouched.
     */
    public void load(List<String> labels) {
        List<Row> rows = new ArrayList<>();
        if (labels != null) {
            int i = 0;
            for (String label : labels) {
                rows.add(Row.action(label, i++));
            }
        }
        loadRows(rows);
    }

    /** Replaces the shown action entries with grouped rows (headers + actions). */
    public void loadRows(List<Row> rows) {
        model.clear();
        if (rows != null) {
            for (Row row : rows) {
                model.addElement(row);
            }
        }
        runButton.setEnabled(false);
    }

    /** Replaces both views: the grouped action rows and the extension list. */
    public void show(List<Row> rows, List<ExtensionInfo> infos) {
        loadRows(rows);
        extModel.clear();
        if (infos != null) {
            for (ExtensionInfo info : infos) {
                extModel.addElement(info);
            }
        }
        extList.clearSelection();
        enableButton.setEnabled(false);
        disableButton.setEnabled(false);
    }

    /** The number of runnable (non-header) action entries shown (test seam). */
    public int entryCount() {
        int n = 0;
        for (int i = 0; i < model.size(); i++) {
            if (!model.get(i).header()) {
                n++;
            }
        }
        return n;
    }

    /** The number of installed extensions shown in the management list. */
    public int extensionCount() {
        return extModel.size();
    }

    /** Selects an action row programmatically (test seam); headers clear Run. */
    public void select(int index) {
        if (index >= 0 && index < model.size()) {
            list.setSelectedIndex(index);
            runButton.setEnabled(!model.get(index).header());
        } else {
            list.clearSelection();
            runButton.setEnabled(false);
        }
    }

    /** Selects an extension row programmatically (test seam). */
    public void selectExtension(int index) {
        if (index >= 0 && index < extModel.size()) {
            extList.setSelectedIndex(index);
        } else {
            extList.clearSelection();
        }
        refreshToggleButtons();
    }

    /** Enables the selected extension (test seam mirroring the Enable button). */
    final void enableSelectedExtension() {
        applyEnabled(true);
    }

    /** Disables the selected extension (test seam mirroring the Disable button). */
    final void disableSelectedExtension() {
        applyEnabled(false);
    }

    private void applyEnabled(boolean enabled) {
        ExtensionInfo selected = extList.getSelectedValue();
        if (selected != null) {
            host.setExtensionEnabled(selected.id(), enabled);
        }
    }

    private void refreshToggleButtons() {
        boolean hasSelection = extList.getSelectedIndex() >= 0;
        enableButton.setEnabled(hasSelection);
        disableButton.setEnabled(hasSelection);
    }

    /** Renders category headers bold and greyed, actions as normal rows. */
    private static final class RowRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value,
                int index, boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    l, value, index, selected, focus);
            Row row = (Row) value;
            label.setText(row.text());
            if (row.header()) {
                label.setFont(label.getFont().deriveFont(Font.BOLD));
                label.setEnabled(false);
            } else {
                label.setFont(label.getFont().deriveFont(Font.PLAIN));
                label.setEnabled(true);
            }
            return label;
        }
    }

    /** Renders enabled extensions bold, disabled ones greyed. */
    private static final class ExtensionRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value,
                int index, boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    l, value, index, selected, focus);
            ExtensionInfo info = (ExtensionInfo) value;
            label.setText(info.toString());
            label.setEnabled(info.enabled());
            label.setFont(label.getFont().deriveFont(
                    info.enabled() ? Font.BOLD : Font.PLAIN));
            return label;
        }
    }
}
