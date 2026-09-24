/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.dbmanager.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import org.jdesktop.lg3d.dbmanager.jdbc.QueryResult;
import org.jdesktop.lg3d.dbmanager.model.AppSettings;

/**
 * The results grid: a {@link JTable} over a paging {@link ResultTableModel},
 * with First/Prev/Next/Last controls, a page-position label and a one-line
 * message showing the last result's row count / update count / error.
 */
public final class ResultsTable extends JPanel {

    private final ResultTableModel model = new ResultTableModel();
    private final JTable table = new JTable(model);
    private final JLabel pageLabel = new JLabel("0 rows", SwingConstants.CENTER);
    private final JLabel messageLabel = new JLabel(" ", SwingConstants.LEFT);
    private final JButton first = new JButton("|<");
    private final JButton prev = new JButton("<");
    private final JButton next = new JButton(">");
    private final JButton last = new JButton(">|");

    /** Builds the grid and its paging bar. */
    public ResultsTable() {
        super(new BorderLayout());
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setAutoCreateRowSorter(false);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buildPagingBar(), BorderLayout.SOUTH);
        wirePaging();
        updatePagingState();
    }

    private JPanel buildPagingBar() {
        JPanel bar = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.add(first);
        buttons.add(prev);
        buttons.add(next);
        buttons.add(last);
        bar.add(buttons, BorderLayout.WEST);
        bar.add(pageLabel, BorderLayout.CENTER);
        messageLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        bar.add(messageLabel, BorderLayout.EAST);
        return bar;
    }

    private void wirePaging() {
        first.addActionListener(e -> {
            model.firstPage();
            updatePagingState();
        });
        prev.addActionListener(e -> {
            model.previousPage();
            updatePagingState();
        });
        next.addActionListener(e -> {
            model.nextPage();
            updatePagingState();
        });
        last.addActionListener(e -> {
            model.lastPage();
            updatePagingState();
        });
    }

    /**
     * Displays a query result.
     *
     * @param result the result to show; {@code null} clears the grid
     */
    public void setQueryResult(QueryResult result) {
        model.setQueryResult(result);
        messageLabel.setText(result != null ? result.summarize() : " ");
        updatePagingState();
    }

    /** @return the currently displayed result, or {@code null}. */
    public QueryResult getResult() {
        return model.getQueryResult();
    }

    /** Applies the page size and null text from the settings. */
    public void configure(AppSettings settings) {
        if (settings != null) {
            model.setPageSize(settings.getPageSize());
            model.setNullText(settings.getNullText());
        }
        updatePagingState();
    }

    private void updatePagingState() {
        pageLabel.setText(model.getPageLabel() + "  (page "
                + model.getCurrentPage() + "/" + model.getPageCount() + ")");
        boolean hasRows = model.getRowCount() > 0;
        first.setEnabled(hasRows && model.getCurrentPage() > 1);
        prev.setEnabled(first.isEnabled());
        next.setEnabled(hasRows && model.getCurrentPage() < model.getPageCount());
        last.setEnabled(next.isEnabled());
    }
}
