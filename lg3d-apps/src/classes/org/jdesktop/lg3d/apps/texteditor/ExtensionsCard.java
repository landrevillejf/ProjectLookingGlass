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
 * The Extensions card: every action contributed by the installed
 * {@link TextEditorExtension}s, grouped under bold, non-selectable category
 * headers. Each action row is labelled {@code <extension>: <action>} and is
 * bound to its index in the host's flat action list, so selecting an entry and
 * pressing Run (or double-clicking) invokes exactly that action against the
 * current tab. Headers are inert: Run stays disabled while one is selected.
 * The card is a plain {@link JList} view, so it works inside the 3D desktop's
 * offscreen capture like every other in-panel surface.
 */
public final class ExtensionsCard extends JPanel {

    /** The panel-side callbacks for the extensions card. */
    public interface Host {

        /** Runs the action with the given index into the host's action list. */
        void runExtensionAction(int index);

        /** Returns to the editor card. */
        void closeCard();
    }

    /**
     * One row in the list: either a non-selectable category {@code header} or a
     * runnable action bound to {@code actionIndex} in the host's flat action
     * list (headers carry {@code -1}).
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

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        buttons.add(runButton);
        buttons.add(back);

        add(new JLabel("Installed Extension Actions, grouped by category "
                + "(double-click to run)"), BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        runButton.setEnabled(false);
    }

    /** @return true when the row at {@code modelIndex} is a runnable action. */
    private boolean isAction(int modelIndex) {
        return modelIndex >= 0 && modelIndex < model.size()
                && !model.get(modelIndex).header();
    }

    /**
     * Replaces the shown entries with a flat, sequentially-indexed action list
     * (no category headers); index order matches {@code labels}.
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

    /** Replaces the shown entries with grouped rows (headers + actions). */
    public void loadRows(List<Row> rows) {
        model.clear();
        if (rows != null) {
            for (Row row : rows) {
                model.addElement(row);
            }
        }
        runButton.setEnabled(false);
    }

    /** The number of runnable (non-header) entries shown (test seam). */
    public int entryCount() {
        int n = 0;
        for (int i = 0; i < model.size(); i++) {
            if (!model.get(i).header()) {
                n++;
            }
        }
        return n;
    }

    /** Selects a list row programmatically (test seam); headers clear Run. */
    public void select(int index) {
        if (index >= 0 && index < model.size()) {
            list.setSelectedIndex(index);
            runButton.setEnabled(!model.get(index).header());
        } else {
            list.clearSelection();
            runButton.setEnabled(false);
        }
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
}
