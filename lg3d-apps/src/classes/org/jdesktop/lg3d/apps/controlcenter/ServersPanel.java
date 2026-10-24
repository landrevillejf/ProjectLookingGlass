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
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import org.jdesktop.lg3d.utils.system.ConfigFileReader;
import org.jdesktop.lg3d.utils.system.InitSystemService.InitSystem;
import org.jdesktop.lg3d.utils.system.InitSystemService.Operation;
import org.jdesktop.lg3d.utils.system.PrivilegedRunner;
import org.jdesktop.lg3d.utils.system.ProcessRunner;
import org.jdesktop.lg3d.utils.system.ServerService;
import org.jdesktop.lg3d.utils.system.ServerService.ServerDef;

/**
 * Servers panel (Phase B of the LFS system-management contract,
 * {@code system-management-contract.md} §4.9). A thin front-end over
 * {@link ServerService}: it lists the internet servers and databases the
 * {@code lg3d} build profile installs (Apache httpd, BIND, Postfix, Dovecot,
 * Samba, NFS, ProFTPD, vsftpd, OpenLDAP, Tomcat, MariaDB, PostgreSQL - see
 * {@code lg3d-agent-package-inventory.md} §4) that are actually present on this
 * host, shows each one's read-only status and config, and drives its lifecycle
 * (start / stop / restart / enable / disable) through the §4.1
 * {@link org.jdesktop.lg3d.utils.system.InitSystemService} abstraction. It never
 * re-implements server logic and never writes {@code /etc} - the config view is
 * a bounded read through {@link ConfigFileReader} (§3.5, §6).
 *
 * <p>Read-only views (status, config) run unprivileged through
 * {@link ProcessRunner}. Mutating lifecycle operations are confirmed with the
 * user and escalated per-operation through {@link PrivilegedRunner} (polkit);
 * when {@code pkexec} is unavailable, or the operation is unsupported for the
 * detected init (sysvinit has no enable/disable), the corresponding buttons are
 * disabled and the panel is effectively read-only.</p>
 *
 * <p>Every operation runs off the EDT behind an indeterminate progress bar with
 * all controls disabled while in flight (serialization, §3.3; long-running ops,
 * §3.6); a non-zero exit or a cancelled polkit prompt is shown verbatim as
 * state, not treated as a crash (§3.2). The server list is a {@link JList}
 * (never a combo box) so the panel keeps working when hosted offscreen in a
 * {@code SwingNode} on the 3D desktop; the same panel serves the 2D desktop.
 * Construction is headless-safe: {@link #reload()} only probes {@code PATH} and
 * the filesystem (never spawning a server process), so a host with none of these
 * servers installed simply shows an empty list. Like the other control-center
 * panels this one is registered lazily (see {@link ControlPanelRegistry}).</p>
 */
public class ServersPanel implements ControlPanel {

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final DefaultListModel<ServerDef> servers = new DefaultListModel<>();
    private final JList<ServerDef> serverList = new JList<>(servers);
    private final JTextArea details = new JTextArea();
    private final JLabel headerLabel = new JLabel(" ");
    private final JLabel noteLabel = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar();

    private final JButton refreshButton = new JButton("Refresh");
    private final JButton statusButton = new JButton("Status");
    private final JButton configButton = new JButton("View Config");
    private final JButton startButton = new JButton("Start");
    private final JButton stopButton = new JButton("Stop");
    private final JButton restartButton = new JButton("Restart");
    private final JButton enableButton = new JButton("Enable");
    private final JButton disableButton = new JButton("Disable");

    /** The supervisor detected when the panel was last reloaded. */
    private InitSystem init = InitSystem.UNKNOWN;

    /** True while a background operation is in flight (serialization guard). */
    private boolean running;

    public ServersPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        serverList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serverList.setVisibleRowCount(12);
        serverList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateButtons();
            }
        });

        details.setEditable(false);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        progress.setIndeterminate(true);
        progress.setVisible(false);

        refreshButton.addActionListener(e -> reload());
        statusButton.addActionListener(e -> showStatus());
        configButton.addActionListener(e -> viewConfig());
        startButton.addActionListener(e -> mutate(Operation.START));
        stopButton.addActionListener(e -> mutate(Operation.STOP));
        restartButton.addActionListener(e -> mutate(Operation.RESTART));
        enableButton.addActionListener(e -> mutate(Operation.ENABLE));
        disableButton.addActionListener(e -> mutate(Operation.DISABLE));

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildCenter(), BorderLayout.CENTER);
        root.add(buildSouth(), BorderLayout.SOUTH);

        reload();
    }

    @Override
    public String displayName() {
        return "Servers";
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
        JPanel header = new JPanel(new BorderLayout(2, 2));
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD));
        noteLabel.setForeground(new Color(160, 90, 20));
        header.add(headerLabel, BorderLayout.NORTH);
        header.add(noteLabel, BorderLayout.SOUTH);
        header.setOpaque(false);
        return header;
    }

    private JComponent buildCenter() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(serverList), new JScrollPane(details));
        split.setDividerLocation(260);
        split.setResizeWeight(0.4);
        split.setBorder(BorderFactory.createEmptyBorder());
        return split;
    }

    private JComponent buildSouth() {
        JPanel south = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel(new GridLayout(2, 1, 0, 4));
        JPanel readRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        readRow.add(refreshButton);
        readRow.add(statusButton);
        readRow.add(configButton);
        JPanel writeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        writeRow.add(startButton);
        writeRow.add(stopButton);
        writeRow.add(restartButton);
        writeRow.add(enableButton);
        writeRow.add(disableButton);
        buttons.add(readRow);
        buttons.add(writeRow);
        south.add(buttons, BorderLayout.NORTH);
        south.add(progress, BorderLayout.SOUTH);
        return south;
    }

    // ------------------------------------------------------------------

    /**
     * Re-probes which catalog servers are installed (PATH + filesystem only, no
     * process spawned) and updates the header, the read-only/mutating note and
     * the button availability. Cheap and CI/headless-safe.
     */
    private void reload() {
        ServerService.Probes probes = ServerService.probe();
        init = probes.init();

        String selectedUnit = null;
        ServerDef sel = serverList.getSelectedValue();
        if (sel != null) {
            selectedUnit = sel.getServiceUnit();
        }

        servers.clear();
        List<ServerDef> installed = ServerService.installedServers(probes);
        for (ServerDef d : installed) {
            servers.addElement(d);
        }

        if (init == InitSystem.UNKNOWN) {
            headerLabel.setText("Servers: no service supervisor detected");
        } else {
            headerLabel.setText("Servers: " + installed.size()
                    + "  (init: " + init.name().toLowerCase(Locale.ROOT) + ")");
        }

        if (!PrivilegedRunner.isAvailable()) {
            noteLabel.setText("Privilege escalation (pkexec) is unavailable; servers are read-only.");
        } else if (installed.isEmpty()) {
            noteLabel.setText("None of the managed servers were detected on this host; "
                    + "status and config remain available for any that appear.");
        } else {
            noteLabel.setText(" ");
        }

        // Restore the previous selection by service unit, else select the first row.
        if (selectedUnit != null) {
            for (int i = 0; i < servers.size(); i++) {
                if (servers.get(i).getServiceUnit().equals(selectedUnit)) {
                    serverList.setSelectedIndex(i);
                    updateButtons();
                    return;
                }
            }
        }
        if (!servers.isEmpty()) {
            serverList.setSelectedIndex(0);
        } else {
            details.setText("");
        }
        updateButtons();
    }

    /** Enables/disables the action buttons for the current init + selection. */
    private void updateButtons() {
        ServerDef sel = serverList.getSelectedValue();
        boolean writable = PrivilegedRunner.isAvailable();
        boolean idle = !running;

        refreshButton.setEnabled(idle);
        statusButton.setEnabled(idle && sel != null);
        configButton.setEnabled(idle && sel != null && sel.hasConfig());
        startButton.setEnabled(idle && sel != null && writable
                && ServerService.isSupported(init, Operation.START));
        stopButton.setEnabled(idle && sel != null && writable
                && ServerService.isSupported(init, Operation.STOP));
        restartButton.setEnabled(idle && sel != null && writable
                && ServerService.isSupported(init, Operation.RESTART));
        enableButton.setEnabled(idle && sel != null && writable
                && ServerService.isSupported(init, Operation.ENABLE));
        disableButton.setEnabled(idle && sel != null && writable
                && ServerService.isSupported(init, Operation.DISABLE));
    }

    /** Shows/hides the progress bar and refreshes every control's enabled state. */
    private void setRunning(boolean value) {
        running = value;
        progress.setVisible(value);
        updateButtons();
    }

    // ------------------------------------------------------------------
    // Read-only actions (off the EDT).

    private void showStatus() {
        ServerDef sel = serverList.getSelectedValue();
        if (running || sel == null) {
            return;
        }
        setRunning(true);
        details.setText("Querying the status of " + sel.getDisplayName() + "...");
        new SwingWorker<ProcessRunner.Result, Void>() {
            @Override
            protected ProcessRunner.Result doInBackground() {
                return ServerService.runStatus(sel, init);
            }

            @Override
            protected void done() {
                try {
                    showReadResult(sel.getDisplayName() + " status", get());
                } catch (Exception ex) {
                    fail(ex);
                } finally {
                    setRunning(false);
                }
            }
        }.execute();
    }

    private void viewConfig() {
        ServerDef sel = serverList.getSelectedValue();
        if (running || sel == null || !sel.hasConfig()) {
            return;
        }
        setRunning(true);
        details.setText("Reading " + sel.getConfigPath() + "...");
        new SwingWorker<ConfigFileReader.Content, Void>() {
            @Override
            protected ConfigFileReader.Content doInBackground() {
                return ServerService.readConfig(sel);
            }

            @Override
            protected void done() {
                try {
                    showConfig(sel, get());
                } catch (Exception ex) {
                    fail(ex);
                } finally {
                    setRunning(false);
                }
            }
        }.execute();
    }

    /** Renders a read-only status result for the details pane, errors verbatim (§3.2). */
    private void showReadResult(String title, ProcessRunner.Result r) {
        StringBuilder sb = new StringBuilder(title).append("\n\n");
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
        details.setText(sb.toString());
        details.setCaretPosition(0);
    }

    /** Renders a bounded read-only config view, honest about unreadable files. */
    private void showConfig(ServerDef sel, ConfigFileReader.Content c) {
        StringBuilder sb = new StringBuilder(sel.getConfigPath())
                .append("  \u2014  ").append(c.describe()).append("\n\n");
        if (c.isReadable()) {
            sb.append(c.getText().isEmpty() ? "(empty)" : c.getText());
        }
        details.setText(sb.toString());
        details.setCaretPosition(0);
        noteLabel.setText(c.isReadable()
                ? "Showing " + sel.getConfigPath() + " read-only (the GUI never writes it)."
                : sel.getConfigPath() + ": " + c.describe() + ".");
    }

    // ------------------------------------------------------------------
    // Mutating actions (confirmed, escalated, off the EDT).

    private void mutate(Operation op) {
        ServerDef sel = serverList.getSelectedValue();
        if (running || sel == null || !PrivilegedRunner.isAvailable()
                || !ServerService.isSupported(init, op)) {
            return;
        }
        String label = label(op);
        if (JOptionPane.showConfirmDialog(root,
                label + " server '" + sel.getDisplayName() + "'?\n\n"
                        + "You will be asked for administrative privileges.",
                "Servers", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }

        final ServerDef fSel = sel;
        setRunning(true);
        details.setText("Running '" + String.join(" ",
                ServerService.lifecycleCommand(fSel, init, op)) + "'...\n\n"
                + "Approve the administrative-privilege prompt.");
        new SwingWorker<PrivilegedRunner.PrivilegedResult, Void>() {
            @Override
            protected PrivilegedRunner.PrivilegedResult doInBackground() {
                return ServerService.runLifecycle(fSel, init, op);
            }

            @Override
            protected void done() {
                try {
                    report(get(), fSel, op);
                } catch (Exception ex) {
                    fail(ex);
                } finally {
                    setRunning(false);
                    reload();
                }
            }
        }.execute();
    }

    private void report(PrivilegedRunner.PrivilegedResult r, ServerDef sel, Operation op) {
        if (r.isSuccess()) {
            noteLabel.setText(label(op) + " on '" + sel.getDisplayName() + "' succeeded.");
            details.setText(r.getOutput().isEmpty()
                    ? "(" + label(op) + " completed with no output)"
                    : r.getOutput());
            details.setCaretPosition(0);
        } else if (r.getStatus() == PrivilegedRunner.Status.CANCELLED) {
            noteLabel.setText("The privilege prompt was cancelled; no change was made.");
        } else if (r.getStatus() == PrivilegedRunner.Status.UNAVAILABLE) {
            noteLabel.setText("Privilege escalation is unavailable; servers are read-only.");
        } else {
            noteLabel.setText(label(op) + " failed: " + r.getMessage());
            details.setText(r.getMessage());
            details.setCaretPosition(0);
        }
    }

    private void fail(Exception ex) {
        noteLabel.setText("The operation could not be completed: " + ex.getMessage());
    }

    private String label(Operation op) {
        String n = op.name().toLowerCase(Locale.ROOT);
        return n.substring(0, 1).toUpperCase(Locale.ROOT) + n.substring(1);
    }

    // ------------------------------------------------------------------
    // Package-visible test hooks (headless construction/availability smoke).

    /** The number of catalog servers detected as installed on this host. */
    int serverCount() {
        return servers.size();
    }

    /** The current header line, for the availability smoke test. */
    String headerText() {
        return headerLabel.getText();
    }

    /** The current note line, for the availability smoke test. */
    String noteText() {
        return noteLabel.getText();
    }

    /** The current details-pane text, for the availability smoke test. */
    String detailsText() {
        return details.getText();
    }

    /** True when the Status button is currently enabled (a server is selected). */
    boolean isStatusEnabled() {
        return statusButton.isEnabled();
    }
}
