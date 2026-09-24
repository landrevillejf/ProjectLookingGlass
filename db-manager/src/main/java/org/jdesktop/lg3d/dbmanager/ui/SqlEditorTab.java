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
import java.awt.Font;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextPane;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import org.jdesktop.lg3d.dbmanager.jdbc.QueryResult;
import org.jdesktop.lg3d.dbmanager.model.AppSettings;
import org.jdesktop.lg3d.dbmanager.session.DbSession;

/**
 * One SQL editor tab: a syntax-highlighted query pane on top, a Run/Stop bar, and
 * the results grid below. Executing splits the buffer into statements and runs
 * them on the active connection in a background worker, so the EDT stays
 * responsive and Stop can cancel a long-running statement.
 */
public final class SqlEditorTab extends JPanel {

    /** Supplies the currently active connection and settings to the tab. */
    public interface Runner {
        /** @return the session to run against, or {@code null} when disconnected. */
        DbSession activeSession();

        /** @return the live app settings (page size, null text). */
        AppSettings settings();

        /** Appends a line to the message log. */
        void log(String message);
    }

    private final Runner runner;
    private final JTextPane editor = new JTextPane();
    private final ResultsTable results = new ResultsTable();
    private final JButton run = new JButton("Run");
    private final JButton stop = new JButton("Stop");
    private volatile DbSession running;

    /**
     * Creates an editor tab.
     *
     * @param runner      the host callback supplying the active session
     * @param initialSql  the starting buffer text (may be {@code null})
     */
    public SqlEditorTab(Runner runner, String initialSql) {
        super(new BorderLayout());
        this.runner = runner;

        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        if (initialSql != null) {
            editor.setText(initialSql);
        }
        new SqlHighlighter(editor);

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        run.setToolTipText("Execute (Ctrl+Enter)");
        stop.setEnabled(false);
        bar.add(run);
        bar.add(stop);
        run.addActionListener(e -> execute());
        stop.addActionListener(e -> cancel());

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(editor), results);
        split.setResizeWeight(0.4);
        split.setDividerLocation(160);

        add(bar, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        // Ctrl+Enter runs the query from anywhere in the editor.
        editor.getInputMap().put(KeyStroke.getKeyStroke("control ENTER"), "run");
        editor.getActionMap().put("run", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                execute();
            }
        });
        applySettings();
    }

    /** Applies the current settings (grid page size, null text, font size). */
    public void applySettings() {
        AppSettings s = runner.settings();
        if (s != null) {
            results.configure(s);
            int size = Math.max(8, s.getEditorFontSize());
            editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, size));
        }
    }

    public String getSql() {
        return editor.getText();
    }

    public void setSql(String sql) {
        editor.setText(sql == null ? "" : sql);
        editor.setCaretPosition(0);
    }

    /** @return the results grid (for CSV export by the host). */
    public ResultsTable getResults() {
        return results;
    }

    /** Executes the buffer against the active session on a worker thread. */
    public void execute() {
        DbSession session = runner.activeSession();
        if (session == null) {
            runner.log("No active connection. Connect to a database first.");
            return;
        }
        String sql = editor.getText();
        if (sql == null || sql.isBlank()) {
            runner.log("Nothing to run: the editor is empty.");
            return;
        }
        run.setEnabled(false);
        stop.setEnabled(true);
        running = session;
        applySettings();

        new SwingWorker<List<QueryResult>, Void>() {
            @Override
            protected List<QueryResult> doInBackground() {
                return session.executeScript(sql);
            }

            @Override
            protected void done() {
                run.setEnabled(true);
                stop.setEnabled(false);
                running = null;
                try {
                    List<QueryResult> list = get();
                    present(list);
                } catch (Exception ex) {
                    runner.log("Execution failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void present(List<QueryResult> list) {
        if (list == null || list.isEmpty()) {
            runner.log("Nothing executed.");
            return;
        }
        QueryResult lastResultSet = null;
        for (QueryResult r : list) {
            runner.log(r.summarize());
            if (r.hasResultSet()) {
                lastResultSet = r; // show the last result set the script produced
            }
        }
        // A null argument clears the grid when the script only ran updates/DDL.
        results.setQueryResult(lastResultSet);
    }

    /** Cancels the statement running on the worker thread. */
    public void cancel() {
        DbSession s = running;
        if (s != null) {
            s.cancel();
            runner.log("Cancelling\u2026");
        }
    }
}
