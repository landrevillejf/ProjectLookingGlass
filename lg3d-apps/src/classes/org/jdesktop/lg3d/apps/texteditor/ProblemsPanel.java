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
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import org.jdesktop.lg3d.apps.texteditor.ext.Diagnostic;

/**
 * The bottom Problems tab: a sortable, read-only table of every
 * {@link Diagnostic} the editor has been fed through
 * {@link org.jdesktop.lg3d.apps.texteditor.ext.EditorContext#reportDiagnostics}.
 * Double-clicking a row asks the host to open that diagnostic's location.
 *
 * <p>Plain Swing, no popups and no dialogs, so it renders correctly when the
 * panel is captured offscreen. EDT-only, like every other surface.</p>
 */
final class ProblemsPanel extends JPanel {

    private static final String[] COLUMNS =
            {"Severity", "File", "Line", "Col", "Message"};

    private final JLabel countLabel = new JLabel("No problems");
    private final DefaultTableModel model =
            new DefaultTableModel(COLUMNS, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
    private final JTable table = new JTable(model);
    private final List<Diagnostic> rows = new ArrayList<>();
    private Consumer<Diagnostic> onActivate = d -> { };

    ProblemsPanel() {
        super(new BorderLayout());
        setName("problemsPanel");

        JPanel header = new JPanel(new BorderLayout());
        header.add(countLabel, BorderLayout.WEST);
        JButton clear = new JButton("Clear");
        clear.setToolTipText("Empty the problems list");
        clear.setFocusable(false);
        clear.addActionListener(e -> clear());
        header.add(clear, BorderLayout.EAST);

        table.setName("problemsTable");
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    activateSelected();
                }
            }
        });

        add(header, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        setPreferredSize(new Dimension(420, 160));
    }

    /**
     * Replaces the whole list with {@code diagnostics}. Null is treated as empty.
     */
    void setDiagnostics(List<Diagnostic> diagnostics) {
        rows.clear();
        model.setRowCount(0);
        if (diagnostics != null) {
            for (Diagnostic d : diagnostics) {
                if (d == null) {
                    continue;
                }
                rows.add(d);
                model.addRow(new Object[] {
                    d.severityLabel(),
                    shortName(d.path()),
                    d.line(),
                    d.col(),
                    d.message(),
                });
            }
        }
        refreshCount();
    }

    /** Empties the list. */
    void clear() {
        rows.clear();
        model.setRowCount(0);
        refreshCount();
    }

    /** Host hook: invoked on double-click with the selected diagnostic. */
    void setOnActivate(Consumer<Diagnostic> handler) {
        this.onActivate = (handler != null) ? handler : d -> { };
    }

    private void activateSelected() {
        int view = table.getSelectedRow();
        if (view >= 0 && view < rows.size()) {
            onActivate.accept(rows.get(table.convertRowIndexToModel(view)));
        }
    }

    private void refreshCount() {
        int errors = errorCount();
        int warnings = warningCount();
        if (rows.isEmpty()) {
            countLabel.setText("No problems");
        } else {
            countLabel.setText(errors + " error(s), " + warnings
                    + " warning(s), " + rows.size() + " total");
        }
    }

    /** @return the number of ERROR rows (test seam). */
    int errorCount() {
        int n = 0;
        for (Diagnostic d : rows) {
            if (d.kind() == Diagnostic.Kind.ERROR) {
                n++;
            }
        }
        return n;
    }

    /** @return the number of WARNING rows (test seam). */
    int warningCount() {
        int n = 0;
        for (Diagnostic d : rows) {
            if (d.kind() == Diagnostic.Kind.WARNING) {
                n++;
            }
        }
        return n;
    }

    /** @return how many rows are listed (test seam). */
    int problemCount() {
        return rows.size();
    }

    /** @return the row at the view index, clamped (test seam). */
    Diagnostic diagnosticAt(int index) {
        if (index < 0 || index >= table.getRowCount()) {
            return null;
        }
        return rows.get(table.convertRowIndexToModel(index));
    }

    private static String shortName(String path) {
        if (path == null || path.isEmpty()) {
            return "(current)";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return (slash >= 0 && slash < path.length() - 1)
                ? path.substring(slash + 1) : path;
    }
}
