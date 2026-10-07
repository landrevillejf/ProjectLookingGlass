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
package org.jdesktop.lg3d.apps.securitycenter;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import org.jdesktop.lg3d.utils.system.PrivacyService;
import org.jdesktop.lg3d.utils.system.PrivacyService.Operation;
import org.jdesktop.lg3d.utils.system.PrivilegedRunner;
import org.jdesktop.lg3d.utils.system.ProcessRunner;

/**
 * The Security Center's <em>Privacy</em> section (LFS/BLFS system-management
 * contract §4.7): a thin front-end over the shared {@link PrivacyService}. Tor
 * is a service, so its status / start / stop / restart are driven through the
 * §4.1 init abstraction (no tor-specific reimplementation, §6); the tor config
 * ({@code /etc/tor/torrc}) and notices log ({@code /var/log/tor/notices.log}) are
 * shown read-only. The panel never writes {@code /etc} or {@code /var/log}.
 *
 * <p>The status read and both file views run unprivileged through
 * {@link ProcessRunner} / plain file reads; the lifecycle actions are confirmed
 * first and then escalated through {@link PrivilegedRunner} (polkit), so a
 * cancelling user aborts cleanly with no partial change (§7 items 4-5). Every
 * operation runs off the EDT behind an indeterminate progress bar with the
 * controls disabled while in flight (serialization, §3.3), and a non-zero exit
 * or an unreadable file is shown verbatim as state, never treated as a crash
 * (§3.2). A missing tor, an undetected init system or an absent polkit agent
 * degrades the buttons and shows honest guidance rather than a false result.</p>
 *
 * <p>Like the rest of the Security Center this is plain Swing using only labels,
 * buttons and a text area (never a {@code JComboBox}), so it renders correctly
 * when hosted offscreen in a {@code SwingNode} on the 3D desktop and identically
 * in the 2D desktop. Construction is headless-safe: availability is resolved with
 * non-spawning PATH/filesystem probes and no command runs until the user acts.</p>
 */
public class PrivacyPanel extends JPanel {

    private final JLabel torStatusLabel = new JLabel("tor: -");
    private final JLabel privacyNote = new JLabel(" ");
    private final JTextArea viewer = new JTextArea();
    private final JProgressBar progress = new JProgressBar();

    private final JButton refreshBtn = new JButton("Refresh Status");
    private final JButton viewConfigBtn = new JButton("View Config (torrc)");
    private final JButton viewLogBtn = new JButton("View Log");
    private final JButton startBtn = new JButton("Start");
    private final JButton stopBtn = new JButton("Stop");
    private final JButton restartBtn = new JButton("Restart");

    /** True while a background operation is in flight (serialization guard). */
    private boolean busy;

    // Availability, resolved once at construction without spawning a process.
    private final boolean torInstalled;
    private final boolean initKnown;
    private final boolean torManageable;
    private final boolean torrcPresent;
    private final boolean torLogPresent;
    private final boolean polkitAvailable;

    /** Builds the Privacy section over a live (non-spawning) probe of this host. */
    public PrivacyPanel() {
        super(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        PrivacyService.Probes probes = PrivacyService.probe();
        torInstalled = PrivacyService.isTorInstalled(probes);
        initKnown = PrivacyService.isInitKnown(probes);
        torManageable = PrivacyService.isTorManageable(probes);
        torrcPresent = PrivacyService.hasTorrc(probes);
        torLogPresent = PrivacyService.hasTorLog(probes);
        polkitAvailable = PrivilegedRunner.isAvailable();

        add(buildHeader(), BorderLayout.NORTH);
        add(buildViewer(), BorderLayout.CENTER);
        add(buildSouth(), BorderLayout.SOUTH);

        if (!torInstalled) {
            torStatusLabel.setText("tor: not installed");
            privacyNote.setText("The 'tor' binary was not found, so the tor service cannot "
                    + "be shown or managed. Install tor to use this section.");
        } else if (!initKnown) {
            torStatusLabel.setText("tor: unavailable");
            privacyNote.setText("No supported init system was detected, so the tor service "
                    + "cannot be queried or managed.");
        } else if (!polkitAvailable) {
            privacyNote.setText("Privilege escalation (pkexec) is unavailable; tor status and "
                    + "the read-only views work, but start/stop/restart are disabled.");
        }
        wireListeners();
        updateButtons();
    }

    // ------------------------------------------------------------------
    // UI construction

    private Component buildHeader() {
        JPanel header = new JPanel(new BorderLayout(2, 2));
        torStatusLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        privacyNote.setForeground(new Color(160, 90, 20));
        header.add(torStatusLabel, BorderLayout.NORTH);
        header.add(privacyNote, BorderLayout.SOUTH);
        return header;
    }

    private Component buildViewer() {
        viewer.setEditable(false);
        viewer.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(viewer);
        scroll.setBorder(BorderFactory.createTitledBorder("Tor output"));
        return scroll;
    }

    private Component buildSouth() {
        JPanel south = new JPanel(new BorderLayout());
        JPanel reads = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        reads.add(refreshBtn);
        reads.add(viewConfigBtn);
        reads.add(viewLogBtn);

        JPanel lifecycle = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        lifecycle.add(startBtn);
        lifecycle.add(stopBtn);
        lifecycle.add(restartBtn);

        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(reads, BorderLayout.WEST);
        buttons.add(lifecycle, BorderLayout.EAST);
        south.add(buttons, BorderLayout.NORTH);

        progress.setIndeterminate(true);
        progress.setVisible(false);
        south.add(progress, BorderLayout.SOUTH);
        return south;
    }

    // ------------------------------------------------------------------
    // Read-only actions (off the EDT, unprivileged).

    /** Reads the tor service state through the init abstraction (§4.1). */
    private void refreshStatus() {
        if (busy || !initKnown) {
            return;
        }
        setBusy(true);
        privacyNote.setText(" ");
        viewer.setText("Querying 'status tor'...");
        new SwingWorker<ProcessRunner.Result, Void>() {
            @Override
            protected ProcessRunner.Result doInBackground() {
                return PrivacyService.runRead(Operation.TOR_STATUS);
            }

            @Override
            protected void done() {
                try {
                    renderStatus(get());
                } catch (Exception ex) {
                    privacyNote.setText("Could not read the tor status: " + ex.getMessage());
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    /** Renders an init-system status result, errors verbatim (§3.2). */
    private void renderStatus(ProcessRunner.Result r) {
        if (!r.isStarted()) {
            torStatusLabel.setText("tor: unavailable");
            viewer.setText("The tor status could not be read: " + r.getStderr());
        } else {
            String out = r.getStdout().isEmpty() ? r.getStderr() : r.getStdout();
            torStatusLabel.setText("tor: "
                    + PrivacyService.describeTor(PrivacyService.parseTorState(r.getExitCode(), out)));
            StringBuilder sb = new StringBuilder();
            if (!r.getStdout().isEmpty()) {
                sb.append(r.getStdout());
            }
            if (!r.getStderr().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(r.getStderr());
            }
            viewer.setText(sb.length() == 0 ? "(no output)" : sb.toString());
        }
        viewer.setCaretPosition(0);
    }

    /** Shows the read-only tor config ({@code /etc/tor/torrc}). */
    private void viewConfig() {
        viewFile(true);
    }

    /** Shows the read-only tor notices log ({@code /var/log/tor/notices.log}). */
    private void viewLog() {
        viewFile(false);
    }

    private void viewFile(boolean config) {
        if (busy) {
            return;
        }
        if (config && !torrcPresent) {
            privacyNote.setText("The tor config (" + PrivacyService.TORRC + ") is not present.");
            return;
        }
        if (!config && !torLogPresent) {
            privacyNote.setText("The tor log (" + PrivacyService.TOR_LOG + ") is not present.");
            return;
        }
        setBusy(true);
        privacyNote.setText(" ");
        final String title = config
                ? ("tor config - " + PrivacyService.TORRC)
                : ("tor log - " + PrivacyService.TOR_LOG);
        viewer.setText("Reading " + title + "...");
        new SwingWorker<PrivacyService.FileContent, Void>() {
            @Override
            protected PrivacyService.FileContent doInBackground() {
                return config ? PrivacyService.readTorrc() : PrivacyService.readTorLog();
            }

            @Override
            protected void done() {
                try {
                    renderFile(title, get());
                } catch (Exception ex) {
                    privacyNote.setText("Could not read the file: " + ex.getMessage());
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    /** Renders a read-only file view; an unreadable file is reported honestly. */
    private void renderFile(String title, PrivacyService.FileContent fc) {
        privacyNote.setText(title + "  -  " + fc.describe());
        if (!fc.isExists()) {
            viewer.setText(title + " is not present on this system.");
        } else if (!fc.isReadable()) {
            viewer.setText(title + " exists but could not be read without privileges.\n"
                    + "Tor's log is often restricted to root; the desktop reads it "
                    + "unprivileged and never escalates for a read-only view (§4.7).");
        } else {
            viewer.setText(fc.getText().isEmpty() ? "(empty file)" : fc.getText());
        }
        viewer.setCaretPosition(0);
    }

    // ------------------------------------------------------------------
    // Mutating actions (confirmed, escalated via polkit, off the EDT).

    private void mutateTor(Operation op) {
        if (busy) {
            return;
        }
        if (!torManageable) {
            privacyNote.setText("tor cannot be managed here (tor is not installed or no "
                    + "supported init system was detected).");
            return;
        }
        if (!polkitAvailable) {
            privacyNote.setText("Privilege escalation (pkexec) is unavailable; cannot "
                    + opLabel(op) + " tor.");
            return;
        }
        if (!confirmTor(op)) {
            privacyNote.setText("Cancelled; no change was made.");
            return;
        }
        setBusy(true);
        viewer.setText("Running '" + opLabel(op) + " tor'...\n\n"
                + "Approve the administrative-privilege prompt.");
        new SwingWorker<PrivilegedRunner.PrivilegedResult, Void>() {
            @Override
            protected PrivilegedRunner.PrivilegedResult doInBackground() {
                return PrivacyService.runMutating(op);
            }

            @Override
            protected void done() {
                try {
                    reportMutate(op, get());
                } catch (Exception ex) {
                    privacyNote.setText("The tor operation failed: " + ex.getMessage());
                } finally {
                    setBusy(false);
                    refreshStatus();
                }
            }
        }.execute();
    }

    /** Reports a lifecycle result verbatim, distinguishing a cancelled prompt (§7 item 5). */
    private void reportMutate(Operation op, PrivilegedRunner.PrivilegedResult r) {
        if (r.isSuccess()) {
            privacyNote.setText("tor " + opLabel(op) + " succeeded.");
            viewer.setText(r.getOutput().isEmpty()
                    ? "(completed with no output)" : r.getOutput());
        } else if (r.getStatus() == PrivilegedRunner.Status.CANCELLED) {
            privacyNote.setText("The privilege prompt was cancelled; no change was made.");
        } else if (r.getStatus() == PrivilegedRunner.Status.UNAVAILABLE) {
            privacyNote.setText("Privilege escalation is unavailable; cannot " + opLabel(op) + " tor.");
        } else {
            privacyNote.setText("tor " + opLabel(op) + " failed: " + r.getMessage());
            viewer.setText(r.getMessage());
        }
        viewer.setCaretPosition(0);
    }

    private boolean confirmTor(Operation op) {
        String verb = opLabel(op);
        String msg = "Do you want to " + verb + " the tor service?\n\n"
                + "This runs the init system's '" + verb + " tor' command.\n"
                + "You will be asked for administrative privileges.";
        return JOptionPane.showConfirmDialog(this, msg, "Tor: " + verb,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private static String opLabel(Operation op) {
        return switch (op) {
            case TOR_START -> "start";
            case TOR_STOP -> "stop";
            case TOR_RESTART -> "restart";
            case TOR_STATUS -> "status";
        };
    }

    // ------------------------------------------------------------------
    // Button wiring / serialization

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisible(value);
        updateButtons();
    }

    /** Gates each control on availability and the in-flight serialization flag. */
    private void updateButtons() {
        boolean idle = !busy;
        refreshBtn.setEnabled(idle && initKnown);
        viewConfigBtn.setEnabled(idle && torrcPresent);
        viewLogBtn.setEnabled(idle && torLogPresent);
        boolean canManage = idle && torManageable && polkitAvailable;
        startBtn.setEnabled(canManage);
        stopBtn.setEnabled(canManage);
        restartBtn.setEnabled(canManage);
    }

    /** Wires the section's buttons (called once from the constructor). */
    private void wireListeners() {
        refreshBtn.addActionListener(e -> refreshStatus());
        viewConfigBtn.addActionListener(e -> viewConfig());
        viewLogBtn.addActionListener(e -> viewLog());
        startBtn.addActionListener(e -> mutateTor(Operation.TOR_START));
        stopBtn.addActionListener(e -> mutateTor(Operation.TOR_STOP));
        restartBtn.addActionListener(e -> mutateTor(Operation.TOR_RESTART));
    }

    // ------------------------------------------------------------------
    // Test hooks (package-visible; the panel is plain Swing, so it constructs
    // headless and never spawns a process until the user acts).

    /** The tor status header text. */
    String torStatusText() {
        return torStatusLabel.getText();
    }

    /** The guidance note text. */
    String noteText() {
        return privacyNote.getText();
    }

    /** The viewer (status / config / log) text. */
    String viewerText() {
        return viewer.getText();
    }

    /** Whether the tor binary was detected at construction. */
    boolean torInstalled() {
        return torInstalled;
    }

    /** Whether a supported init system was detected at construction. */
    boolean initKnown() {
        return initKnown;
    }

    /** Whether tor can be driven (installed and an init detected). */
    boolean torManageable() {
        return torManageable;
    }

    /** Whether {@code /etc/tor/torrc} was present at construction. */
    boolean torrcPresent() {
        return torrcPresent;
    }

    /** Whether {@code /var/log/tor/notices.log} was present at construction. */
    boolean torLogPresent() {
        return torLogPresent;
    }

    /** Whether privilege escalation (pkexec) was available at construction. */
    boolean polkitAvailable() {
        return polkitAvailable;
    }

    /** Whether the "Refresh Status" control is enabled. */
    boolean refreshEnabled() {
        return refreshBtn.isEnabled();
    }

    /** Whether the "View Config" control is enabled. */
    boolean viewConfigEnabled() {
        return viewConfigBtn.isEnabled();
    }

    /** Whether the "View Log" control is enabled. */
    boolean viewLogEnabled() {
        return viewLogBtn.isEnabled();
    }

    /** Whether the "Start" control is enabled. */
    boolean startEnabled() {
        return startBtn.isEnabled();
    }

    /** Whether the "Stop" control is enabled. */
    boolean stopEnabled() {
        return stopBtn.isEnabled();
    }

    /** Whether the "Restart" control is enabled. */
    boolean restartEnabled() {
        return restartBtn.isEnabled();
    }
}
