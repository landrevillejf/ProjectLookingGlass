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
import java.awt.FlowLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import org.jdesktop.lg3d.utils.system.InitSystemService;
import org.jdesktop.lg3d.utils.system.InitSystemService.InitSystem;
import org.jdesktop.lg3d.utils.system.InitSystemService.Operation;
import org.jdesktop.lg3d.utils.system.InitSystemService.ServiceEntry;
import org.jdesktop.lg3d.utils.system.PrivilegedRunner;
import org.jdesktop.lg3d.utils.system.ProcessRunner;

/**
 * Service management panel (Phase 1 of the LFS system-management contract,
 * {@code system-management-contract.md} §4.1). It is a thin front-end over
 * {@link InitSystemService}: it lists the units of whatever service supervisor
 * the running system uses (systemd / openrc / runit / s6 / sysvinit) and drives
 * start / stop / restart / enable / disable / status through that supervisor's
 * native CLI. It never re-implements service logic.
 *
 * <p>Read-only operations (status, list) run unprivileged through
 * {@link ProcessRunner}; mutating operations are confirmed with the user and
 * escalated per-operation through {@link PrivilegedRunner} (polkit). When
 * {@code pkexec} is unavailable, or the operation is unsupported for the
 * detected init (sysvinit has no enable/disable), the corresponding buttons are
 * disabled and the panel is effectively read-only. The list is a {@link JList}
 * (never a combo box) so the panel keeps working when hosted offscreen in a
 * {@code SwingNode} on the 3D desktop; the same panel serves the 2D desktop.</p>
 *
 * <p>Like the other control-center panels this one is registered lazily (see
 * {@link ControlPanelRegistry}); {@link #reload()} shells out to the supervisor,
 * so it runs only when the category is opened, never up front.</p>
 */
public class ServicesPanel implements ControlPanel {

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final DefaultListModel<ServiceEntry> services = new DefaultListModel<>();
    private final JList<ServiceEntry> serviceList = new JList<>(services);
    private final JTextArea details = new JTextArea();
    private final JLabel initLabel = new JLabel(" ");
    private final JLabel noteLabel = new JLabel(" ");

    private final JButton statusButton = new JButton("Status");
    private final JButton startButton = new JButton("Start");
    private final JButton stopButton = new JButton("Stop");
    private final JButton restartButton = new JButton("Restart");
    private final JButton enableButton = new JButton("Enable");
    private final JButton disableButton = new JButton("Disable");
    private final JButton refreshButton = new JButton("Refresh");

    /** The supervisor detected when the panel was last reloaded. */
    private InitSystem init = InitSystem.UNKNOWN;

    public ServicesPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        serviceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serviceList.setVisibleRowCount(12);
        serviceList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateButtons();
            }
        });

        details.setEditable(false);
        details.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));

        statusButton.addActionListener(e -> showStatus());
        startButton.addActionListener(e -> mutate(Operation.START));
        stopButton.addActionListener(e -> mutate(Operation.STOP));
        restartButton.addActionListener(e -> mutate(Operation.RESTART));
        enableButton.addActionListener(e -> mutate(Operation.ENABLE));
        disableButton.addActionListener(e -> mutate(Operation.DISABLE));
        refreshButton.addActionListener(e -> reload());

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildCenter(), BorderLayout.CENTER);
        root.add(buildSouth(), BorderLayout.SOUTH);

        reload();
    }

    @Override
    public String displayName() {
        return "Services";
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

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        initLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        noteLabel.setForeground(new java.awt.Color(160, 90, 20));
        header.add(initLabel, BorderLayout.NORTH);
        header.add(noteLabel, BorderLayout.SOUTH);
        header.setOpaque(false);
        return header;
    }

    private JComponent buildCenter() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(serviceList), new JScrollPane(details));
        split.setDividerLocation(280);
        split.setResizeWeight(0.4);
        split.setBorder(BorderFactory.createEmptyBorder());
        return split;
    }

    private JComponent buildSouth() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(refreshButton);
        row.add(statusButton);
        row.add(startButton);
        row.add(stopButton);
        row.add(restartButton);
        row.add(enableButton);
        row.add(disableButton);
        return row;
    }

    // ------------------------------------------------------------------

    /** Detects the supervisor and re-lists its services. */
    private void reload() {
        init = InitSystemService.detect();
        if (init == InitSystem.UNKNOWN) {
            initLabel.setText("Init system: none detected");
        } else {
            initLabel.setText("Init system: " + init.name().toLowerCase(java.util.Locale.ROOT));
        }

        String selectedName = null;
        ServiceEntry sel = serviceList.getSelectedValue();
        if (sel != null) {
            selectedName = sel.getName();
        }

        services.clear();
        List<ServiceEntry> entries = InitSystemService.listServiceEntries(init);
        for (ServiceEntry e : entries) {
            services.addElement(e);
        }

        boolean writable = PrivilegedRunner.isAvailable();
        if (!writable) {
            noteLabel.setText("Privilege escalation (pkexec) is unavailable; services are read-only.");
        } else if (entries.isEmpty()) {
            noteLabel.setText(init == InitSystem.UNKNOWN
                    ? "No service supervisor was detected on this host."
                    : "No services were reported by the supervisor.");
        } else {
            noteLabel.setText(" ");
        }

        // Restore the previous selection by name, else select the first row.
        if (selectedName != null) {
            for (int i = 0; i < services.size(); i++) {
                if (services.get(i).getName().equals(selectedName)) {
                    serviceList.setSelectedIndex(i);
                    updateButtons();
                    return;
                }
            }
        }
        if (!services.isEmpty()) {
            serviceList.setSelectedIndex(0);
        } else {
            details.setText("");
        }
        updateButtons();
    }

    /** Enables/disables the action buttons for the current init + selection. */
    private void updateButtons() {
        ServiceEntry sel = serviceList.getSelectedValue();
        boolean writable = PrivilegedRunner.isAvailable();
        statusButton.setEnabled(sel != null);
        refreshButton.setEnabled(true);
        startButton.setEnabled(sel != null && writable
                && InitSystemService.isSupported(init, Operation.START));
        stopButton.setEnabled(sel != null && writable
                && InitSystemService.isSupported(init, Operation.STOP));
        restartButton.setEnabled(sel != null && writable
                && InitSystemService.isSupported(init, Operation.RESTART));
        enableButton.setEnabled(sel != null && writable
                && InitSystemService.isSupported(init, Operation.ENABLE));
        disableButton.setEnabled(sel != null && writable
                && InitSystemService.isSupported(init, Operation.DISABLE));
    }

    /** Runs a read-only status query and shows its verbatim output. */
    private void showStatus() {
        ServiceEntry sel = serviceList.getSelectedValue();
        if (sel == null) {
            return;
        }
        ProcessRunner.Result r =
                InitSystemService.runRead(init, Operation.STATUS, sel.getName());
        details.setText(describe(sel, r));
        details.setCaretPosition(0);
    }

    /**
     * Confirms and runs a mutating operation (start/stop/restart/enable/disable)
     * with polkit escalation, then reports the outcome verbatim and reloads.
     */
    private void mutate(Operation op) {
        ServiceEntry sel = serviceList.getSelectedValue();
        if (sel == null) {
            return;
        }
        if (JOptionPane.showConfirmDialog(root,
                op.name().charAt(0) + op.name().substring(1).toLowerCase(java.util.Locale.ROOT)
                        + " service '" + sel.getName() + "'?",
                "Services", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        PrivilegedRunner.PrivilegedResult r =
                InitSystemService.runMutating(init, op, sel.getName());
        report(r, sel, op);
        reload();
    }

    private void report(PrivilegedRunner.PrivilegedResult r, ServiceEntry sel, Operation op) {
        if (r.isSuccess()) {
            noteLabel.setText(op + " on '" + sel.getName() + "' succeeded.");
            details.setText(r.getOutput().isEmpty()
                    ? "(" + op + " completed with no output)"
                    : r.getOutput());
            details.setCaretPosition(0);
        } else if (r.getStatus() == PrivilegedRunner.Status.CANCELLED) {
            noteLabel.setText("The privilege prompt was cancelled; no change was made.");
        } else if (r.getStatus() == PrivilegedRunner.Status.UNAVAILABLE) {
            noteLabel.setText("Privilege escalation is unavailable; services are read-only.");
        } else {
            noteLabel.setText("Operation failed: " + r.getMessage());
            details.setText(r.getMessage());
            details.setCaretPosition(0);
        }
    }

    /** Formats a read-only status result for the details pane, errors verbatim. */
    private String describe(ServiceEntry sel, ProcessRunner.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append(sel.getName());
        if (!sel.getState().isEmpty()) {
            sb.append("  \u2014  ").append(sel.getState());
        }
        sb.append("\n\n");
        if (!r.isStarted()) {
            sb.append("The status command could not be started: ").append(r.getStderr());
        } else if (r.isSuccess()) {
            sb.append(r.getStdout().isEmpty() ? "(no output)" : r.getStdout());
        } else {
            // Non-zero exit is a result, not a crash: show it verbatim.
            sb.append("exit code ").append(r.getExitCode()).append('\n');
            if (!r.getStdout().isEmpty()) {
                sb.append(r.getStdout());
            }
            if (!r.getStderr().isEmpty()) {
                sb.append('\n').append(r.getStderr());
            }
        }
        return sb.toString();
    }
}
