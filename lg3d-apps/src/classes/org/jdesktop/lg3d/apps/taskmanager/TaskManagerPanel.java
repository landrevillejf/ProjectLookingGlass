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
package org.jdesktop.lg3d.apps.taskmanager;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import org.jdesktop.lg3d.utils.system.Proc;
import org.jdesktop.lg3d.utils.system.ProcessService;

/**
 * The task manager's Swing UI: a live, sortable, filterable process table with
 * an aggregate load header, a details pane for the selected process, and
 * End Task (SIGTERM) / Force Quit (SIGKILL) / Change Priority (renice) actions.
 *
 * <p>The table refreshes every two seconds from {@link ProcessService}; CPU%
 * is derived from procfs deltas between successive snapshots. Signalling a
 * process owned by another user is routed by {@code ProcessService} through
 * {@code pkexec}, so the confirmation dialogs here cover both cases.</p>
 */
public class TaskManagerPanel extends JPanel {

    private static final int PANEL_W = 680;
    private static final int PANEL_H = 480;
    private static final int REFRESH_MS = 2000;

    private static final DateTimeFormatter START_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    private final ProcessTableModel model = new ProcessTableModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<ProcessTableModel> sorter =
            new TableRowSorter<>(model);
    private final JTextArea details = new JTextArea();
    private final JLabel summaryLabel = new JLabel(" ");
    private final JTextField searchField = new JTextField(14);
    private final Timer timer;

    private String query = "";
    private boolean refreshing;
    private Runnable onClose;

    public TaskManagerPanel() {
        super(new BorderLayout(6, 6));
        setPreferredSize(new Dimension(PANEL_W, PANEL_H));
        setBackground(new Color(238, 240, 244));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        details.setEditable(false);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        details.setLineWrap(false);

        buildTable();

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(table), new JScrollPane(details));
        split.setDividerLocation(300);
        split.setResizeWeight(0.7);
        split.setBorder(BorderFactory.createEmptyBorder());

        add(buildHeader(), BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(buildControls(), BorderLayout.SOUTH);

        timer = new Timer(REFRESH_MS, e -> refresh());
        timer.setInitialDelay(0);
        timer.start();
        refresh();
    }

    /** Sets the callback invoked when the user presses Close. */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /** Stops the refresh timer (call when the window closes). */
    public void stop() {
        timer.stop();
        ProcessService.resetSamples();
    }

    // ------------------------------------------------------------------
    // UI construction

    private void buildTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(22);
        table.setFillsViewportHeight(true);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.getTableHeader().setReorderingAllowed(false);
        table.setRowSorter(sorter);

        table.getColumnModel().getColumn(0).setPreferredWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(160);
        table.getColumnModel().getColumn(2).setPreferredWidth(90);
        table.getColumnModel().getColumn(3).setPreferredWidth(70);
        table.getColumnModel().getColumn(4).setPreferredWidth(90);
        table.getColumnModel().getColumn(5).setPreferredWidth(90);

        table.getColumnModel().getColumn(3).setCellRenderer(new PercentRenderer());
        table.getColumnModel().getColumn(4).setCellRenderer(new BytesRenderer());

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateDetails();
            }
        });

        applyFilter();
    }

    private JPanel buildHeader() {
        JPanel north = new JPanel(new BorderLayout(8, 4));
        north.setOpaque(false);
        summaryLabel.setForeground(new Color(40, 48, 62));
        north.add(summaryLabel, BorderLayout.CENTER);

        JPanel filter = new JPanel(new BorderLayout(4, 0));
        filter.setOpaque(false);
        JLabel fl = new JLabel("Filter:");
        filter.add(fl, BorderLayout.WEST);
        filter.add(searchField, BorderLayout.CENTER);
        searchField.setToolTipText("Filter by name, user or PID");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onQuery(); }
            @Override public void removeUpdate(DocumentEvent e) { onQuery(); }
            @Override public void changedUpdate(DocumentEvent e) { onQuery(); }
        });
        north.add(filter, BorderLayout.EAST);
        return north;
    }

    private JPanel buildControls() {
        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        south.setOpaque(false);
        south.add(button("End Task", e -> endTask()));
        south.add(button("Force Quit", e -> forceQuit()));
        south.add(button("Change Priority...", e -> changePriority()));
        south.add(button("Refresh", e -> refresh()));
        JButton close = button("Close", e -> {
            stop();
            if (onClose != null) {
                onClose.run();
            }
        });
        south.add(close);
        return south;
    }

    private JButton button(String label, java.awt.event.ActionListener al) {
        JButton b = new JButton(label);
        b.setFocusable(false);
        b.addActionListener(al);
        return b;
    }

    // ------------------------------------------------------------------
    // Refresh + summary + details

    @SuppressWarnings("unchecked")
    private void refresh() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        final long selectedPid = selectedPid();
        new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() {
                List<ProcessService.ProcessInfo> procs = ProcessService.snapshot();
                ProcessService.SystemLoad load = ProcessService.systemLoad();
                return new Object[] { procs, load };
            }
            @Override
            protected void done() {
                refreshing = false;
                try {
                    Object[] r = get();
                    model.setRows((List<ProcessService.ProcessInfo>) r[0]);
                    updateHeader((ProcessService.SystemLoad) r[1]);
                    restoreSelection(selectedPid);
                    updateDetails();
                } catch (Exception ex) {
                    // snapshot failed; keep the previous rows
                }
            }
        }.execute();
    }

    private void updateHeader(ProcessService.SystemLoad load) {
        double[] la = load.getLoadAverage();
        String loadStr = (la != null && la.length >= 3)
                ? String.format("%.2f  %.2f  %.2f", la[0], la[1], la[2]) : "n/a";
        summaryLabel.setText(String.format(
                "<html>CPU <b>%.0f%%</b>&nbsp;&nbsp;|&nbsp;&nbsp;Memory <b>%s / %s (%.0f%%)</b>"
                + "&nbsp;&nbsp;|&nbsp;&nbsp;Load <b>%s</b>&nbsp;&nbsp;|&nbsp;&nbsp;Uptime <b>%s</b>"
                + "&nbsp;&nbsp;|&nbsp;&nbsp;<b>%d</b> processes</html>",
                load.getCpuPercent(),
                ProcessService.formatBytes(load.getMemUsedKb() * 1024L),
                ProcessService.formatBytes(load.getMemTotalKb() * 1024L),
                load.getMemUsedPercent(),
                loadStr,
                ProcessService.formatUptime(load.getUptimeSeconds()),
                load.getProcessCount()));
    }

    private void updateDetails() {
        ProcessService.ProcessInfo p = selectedProcess();
        if (p == null) {
            details.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("PID:       ").append(p.getPid()).append('\n');
        sb.append("Name:      ").append(p.getName()).append('\n');
        sb.append("Owner:     ").append(p.getUser()).append('\n');
        sb.append("State:     ").append(p.getStateLabel())
                .append(" (").append(p.getState()).append(")\n");
        sb.append("CPU:       ").append(String.format("%.1f%%", p.getCpuPercent())).append('\n');
        sb.append("Memory:    ").append(ProcessService.formatBytes(p.getRssBytes())).append('\n');
        sb.append("Threads:   ").append(p.getThreads()).append('\n');
        if (p.getStartTime() != null) {
            sb.append("Started:   ").append(START_FMT.format(p.getStartTime())).append('\n');
        }
        String cwd = safe(() -> Proc.cwd(p.getPid()));
        sb.append("CWD:       ").append(cwd != null ? cwd : "(unknown)").append('\n');
        String cmd = safe(() -> Proc.cmdline(p.getPid()));
        sb.append("Command:   ").append(wrap(cmd != null && !cmd.isEmpty()
                ? cmd : ("[" + p.getName() + "]"))).append('\n');
        details.setText(sb.toString());
        details.setCaretPosition(0);
    }

    private String wrap(String s) {
        if (s.length() <= 70) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            sb.append(s, i, Math.min(s.length(), i + 70)).append("\n           ");
            i += 70;
        }
        return sb.toString().trim();
    }

    private interface StringSupplier {
        String get();
    }

    private String safe(StringSupplier s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Selection + filter

    private ProcessService.ProcessInfo selectedProcess() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        return model.getProcessAt(table.convertRowIndexToModel(viewRow));
    }

    private long selectedPid() {
        ProcessService.ProcessInfo p = selectedProcess();
        return (p != null) ? p.getPid() : -1L;
    }

    private void restoreSelection(long pid) {
        if (pid < 0) {
            return;
        }
        for (int m = 0; m < model.getRowCount(); m++) {
            ProcessService.ProcessInfo p = model.getProcessAt(m);
            if (p != null && p.getPid() == pid) {
                int view = table.convertRowIndexToView(m);
                if (view >= 0) {
                    table.setRowSelectionInterval(view, view);
                }
                return;
            }
        }
    }

    private void onQuery() {
        query = searchField.getText().trim().toLowerCase();
        applyFilter();
    }

    private void applyFilter() {
        sorter.setRowFilter(new RowFilter<ProcessTableModel, Object>() {
            @Override
            public boolean include(Entry<? extends ProcessTableModel, ? extends Object> entry) {
                if (query.isEmpty()) {
                    return true;
                }
                Integer mi = (Integer) entry.getIdentifier();
                ProcessService.ProcessInfo p = model.getProcessAt(mi.intValue());
                if (p == null) {
                    return false;
                }
                return p.getName().toLowerCase().contains(query)
                        || p.getUser().toLowerCase().contains(query)
                        || String.valueOf(p.getPid()).contains(query);
            }
        });
    }

    // ------------------------------------------------------------------
    // Process control

    private void endTask() {
        ProcessService.ProcessInfo p = selectedProcess();
        if (p == null) {
            return;
        }
        int r = JOptionPane.showConfirmDialog(this,
                "End task '" + p.getName() + "' (PID " + p.getPid() + ")?\n"
                + "This sends SIGTERM and lets the process shut down cleanly.",
                "End Task", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        report(ProcessService.terminate(p.getPid()));
    }

    private void forceQuit() {
        ProcessService.ProcessInfo p = selectedProcess();
        if (p == null) {
            return;
        }
        int r = JOptionPane.showConfirmDialog(this,
                "Force quit '" + p.getName() + "' (PID " + p.getPid() + ")?\n"
                + "SIGKILL cannot be ignored; unsaved data will be lost.",
                "Force Quit", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        report(ProcessService.kill(p.getPid()));
    }

    private void changePriority() {
        ProcessService.ProcessInfo p = selectedProcess();
        if (p == null) {
            return;
        }
        String input = (String) JOptionPane.showInputDialog(this,
                "New niceness for '" + p.getName() + "' (-20 highest .. 19 lowest):",
                "Change Priority", JOptionPane.QUESTION_MESSAGE, null, null, "0");
        if (input == null || input.isBlank()) {
            return;
        }
        int niceness;
        try {
            niceness = Integer.parseInt(input.trim());
        } catch (NumberFormatException nfe) {
            showError("Niceness must be a number between -20 and 19.");
            return;
        }
        reportPrivileged(ProcessService.renice(p.getPid(), niceness));
    }

    private void report(ProcessService.TerminateResult result) {
        if (result.isSuccess()) {
            refresh();
        } else if (result.getKind() == ProcessService.TerminateResult.Kind.NOT_PERMITTED) {
            showError("Not permitted (or the privilege prompt was cancelled):\n"
                    + result.getMessage());
        } else {
            showError("The operation failed:\n" + result.getMessage());
        }
    }

    private void reportPrivileged(org.jdesktop.lg3d.utils.system.PrivilegedRunner.PrivilegedResult r) {
        if (r.isSuccess()) {
            refresh();
        } else {
            showError("Priority change not applied (" + r.getStatus() + "):\n"
                    + r.getMessage());
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Task Manager",
                JOptionPane.WARNING_MESSAGE);
    }

    // ------------------------------------------------------------------
    // Renderers

    /** Right-aligned "12.3%" renderer for the CPU column. */
    private static final class PercentRenderer extends DefaultTableCellRenderer {
        PercentRenderer() {
            setHorizontalAlignment(JLabel.RIGHT);
        }
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Double d = (value instanceof Double) ? (Double) value : null;
            return super.getTableCellRendererComponent(t,
                    (d != null ? String.format("%.1f", d.doubleValue()) : ""),
                    isSelected, hasFocus, row, column);
        }
    }

    /** Right-aligned human-readable byte renderer for the Memory column. */
    private static final class BytesRenderer extends DefaultTableCellRenderer {
        BytesRenderer() {
            setHorizontalAlignment(JLabel.RIGHT);
        }
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Long l = (value instanceof Long) ? (Long) value : null;
            return super.getTableCellRendererComponent(t,
                    (l != null ? ProcessService.formatBytes(l.longValue()) : ""),
                    isSelected, hasFocus, row, column);
        }
    }
}
