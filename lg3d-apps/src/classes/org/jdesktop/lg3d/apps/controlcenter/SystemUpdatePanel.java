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
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import org.jdesktop.lg3d.utils.system.LfsUpdateService;
import org.jdesktop.lg3d.utils.system.LfsUpdateService.Action;
import org.jdesktop.lg3d.utils.system.LfsUpdateService.CheckVerdict;
import org.jdesktop.lg3d.utils.system.LfsUpdateService.StatusSnapshot;
import org.jdesktop.lg3d.utils.system.PrivilegedRunner;
import org.jdesktop.lg3d.utils.system.ProcessRunner;

/**
 * Whole-system update panel (Phase 2 of the LFS system-management contract,
 * {@code system-management-contract.md} §4.2). A thin front-end over
 * {@link LfsUpdateService}: it checks for updates, shows the reported system
 * status and applies {@code lfs-update upgrade}. It never re-implements the
 * updater - {@code lfs-update upgrade} itself backs up {@code /etc}+{@code /boot},
 * drives {@code lpm update-db}/{@code upgrade} and rebuilds the kernel + GRUB.
 *
 * <p>Read-only actions ({@code check}, {@code status}) run unprivileged through
 * {@link ProcessRunner}; the mutating {@code upgrade} is confirmed and escalated
 * per-operation through {@link PrivilegedRunner} (polkit). Both run off the EDT
 * behind an indeterminate progress bar, and every control is disabled while an
 * operation is in flight (serialization, §3.3). Output is ANSI-stripped (§3.1)
 * and a non-zero exit is shown verbatim as state, not treated as a crash
 * (§3.2).</p>
 *
 * <p>The panel states the three-layer update model the contract requires: the
 * lg3d bundle auto-update ({@code update-manager}), package management
 * ({@code lpm}) and this whole-system updater ({@code lfs-update}) are distinct.
 * When {@code /usr/bin/lfs-update} is absent (a non-LFS host) the panel degrades
 * to an explanatory read-only note.</p>
 */
public class SystemUpdatePanel implements ControlPanel {

    private static final String THREE_LAYER_NOTE =
            "<html>This updates the <b>whole LFS/BLFS system</b> via <code>lfs-update</code>. "
            + "It is distinct from the <b>lg3d desktop</b> auto-update "
            + "(<code>update-manager</code>, Software Update) and from <b>individual "
            + "packages</b> (<code>lpm</code>, LPM Console).</html>";

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final JLabel versionLabel = new JLabel(" ");
    private final JLabel noteLabel = new JLabel(" ");
    private final JTextArea output = new JTextArea();
    private final JProgressBar progress = new JProgressBar();

    private final JButton checkButton = new JButton("Check for Updates");
    private final JButton statusButton = new JButton("Refresh Status");
    private final JButton upgradeButton = new JButton("Upgrade System...");

    /** True while a background operation is in flight (serialization guard). */
    private boolean running;

    public SystemUpdatePanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        progress.setIndeterminate(true);
        progress.setVisible(false);

        checkButton.addActionListener(e -> check());
        statusButton.addActionListener(e -> refreshStatus());
        upgradeButton.addActionListener(e -> upgrade());

        root.add(buildNorth(), BorderLayout.NORTH);
        root.add(new JScrollPane(output), BorderLayout.CENTER);
        root.add(buildSouth(), BorderLayout.SOUTH);

        reload();
    }

    @Override
    public String displayName() {
        return "System Update";
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

    private JComponent buildNorth() {
        JPanel north = new JPanel(new BorderLayout(4, 4));
        JLabel layers = new JLabel(THREE_LAYER_NOTE);
        layers.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        versionLabel.setFont(versionLabel.getFont().deriveFont(Font.BOLD));
        noteLabel.setForeground(new Color(160, 90, 20));
        JPanel head = new JPanel(new BorderLayout(2, 2));
        head.add(versionLabel, BorderLayout.NORTH);
        head.add(noteLabel, BorderLayout.SOUTH);
        head.setOpaque(false);
        north.add(layers, BorderLayout.NORTH);
        north.add(head, BorderLayout.SOUTH);
        north.setOpaque(false);
        return north;
    }

    private JComponent buildSouth() {
        JPanel south = new JPanel(new BorderLayout());
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(checkButton);
        row.add(statusButton);
        row.add(upgradeButton);
        south.add(row, BorderLayout.NORTH);
        south.add(progress, BorderLayout.SOUTH);
        return south;
    }

    /**
     * Reads the reported status (only when {@code lfs-update} is present, so the
     * constructor stays cheap and CI/headless-safe) and updates the labels and
     * the read-only/mutating button availability.
     */
    private void reload() {
        if (!LfsUpdateService.isAvailable()) {
            versionLabel.setText("System version: unknown");
            noteLabel.setText("lfs-update was not found on this host; whole-system "
                    + "updates are unavailable (this is not an LFS/BLFS target).");
            output.setText("");
            setRunning(false);
            checkButton.setEnabled(false);
            statusButton.setEnabled(false);
            upgradeButton.setEnabled(false);
            return;
        }
        boolean writable = PrivilegedRunner.isAvailable();
        upgradeButton.setEnabled(writable && !running);
        checkButton.setEnabled(!running);
        statusButton.setEnabled(!running);
        if (!writable) {
            noteLabel.setText("Privilege escalation (pkexec) is unavailable; "
                    + "the system can be checked but not upgraded.");
        } else if (noteLabel.getText().isEmpty() || noteLabel.getText().equals(" ")) {
            noteLabel.setText(" ");
        }
        if (!running) {
            refreshStatus();
        }
    }

    /** Disables every control and shows/hides the progress bar. */
    private void setRunning(boolean value) {
        running = value;
        progress.setVisible(value);
        boolean writable = PrivilegedRunner.isAvailable();
        checkButton.setEnabled(!value && LfsUpdateService.isAvailable());
        statusButton.setEnabled(!value && LfsUpdateService.isAvailable());
        upgradeButton.setEnabled(!value && writable && LfsUpdateService.isAvailable());
    }

    // ------------------------------------------------------------------
    // Read-only actions (off the EDT).

    private void check() {
        if (running || !LfsUpdateService.isAvailable()) {
            return;
        }
        setRunning(true);
        output.setText("Checking for updates (lfs-update check)...");
        new SwingWorker<ProcessRunner.Result, Void>() {
            @Override
            protected ProcessRunner.Result doInBackground() {
                return LfsUpdateService.runRead(Action.CHECK);
            }

            @Override
            protected void done() {
                try {
                    showCheck(get());
                } catch (Exception ex) {
                    fail(ex);
                } finally {
                    setRunning(false);
                }
            }
        }.execute();
    }

    private void showCheck(ProcessRunner.Result r) {
        if (!r.isStarted()) {
            noteLabel.setText("Could not run lfs-update check: " + r.getStderr());
            output.setText(LfsUpdateService.stripAnsi(r.getStderr()));
            return;
        }
        CheckVerdict verdict = LfsUpdateService.interpretCheck(r.getExitCode());
        switch (verdict) {
            case UP_TO_DATE:
                noteLabel.setText("The system is up to date.");
                break;
            case UPDATES_AVAILABLE:
                noteLabel.setText("Updates are available. Run 'Upgrade System' to apply them.");
                break;
            case ERROR:
            default:
                noteLabel.setText("The update check failed (exit " + r.getExitCode() + ").");
                break;
        }
        output.setText(render(r));
        output.setCaretPosition(0);
    }

    private void refreshStatus() {
        if (running || !LfsUpdateService.isAvailable()) {
            return;
        }
        setRunning(true);
        new SwingWorker<ProcessRunner.Result, Void>() {
            @Override
            protected ProcessRunner.Result doInBackground() {
                return LfsUpdateService.runRead(Action.STATUS);
            }

            @Override
            protected void done() {
                try {
                    showStatus(get());
                } catch (Exception ex) {
                    fail(ex);
                } finally {
                    setRunning(false);
                }
            }
        }.execute();
    }

    private void showStatus(ProcessRunner.Result r) {
        if (!r.isStarted()) {
            versionLabel.setText("System version: unknown");
            return;
        }
        StatusSnapshot s = LfsUpdateService.parseStatus(r.getStdout());
        String version = s.version();
        if (version == null || version.isEmpty()) {
            version = LfsUpdateService.readLfsVersion();
        }
        versionLabel.setText("System version: "
                + ((version == null || version.isEmpty()) ? "unknown" : version));
        StringBuilder sb = new StringBuilder();
        Integer installed = s.installed();
        Integer upgradable = s.upgradable();
        if (installed != null) {
            sb.append("Installed packages: ").append(installed).append('\n');
        }
        if (upgradable != null) {
            sb.append("Upgradable packages: ").append(upgradable).append('\n');
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(s.raw().isEmpty() ? r.getStdout() : s.raw());
        output.setText(LfsUpdateService.stripAnsi(sb.toString()));
        output.setCaretPosition(0);
    }

    // ------------------------------------------------------------------
    // The mutating upgrade (confirmed, escalated, off the EDT).

    private void upgrade() {
        if (running || !LfsUpdateService.isAvailable() || !PrivilegedRunner.isAvailable()) {
            return;
        }
        if (JOptionPane.showConfirmDialog(root,
                "Run 'lfs-update upgrade'?\n\n"
                        + "This backs up /etc and /boot, updates all packages via lpm,\n"
                        + "and may rebuild the kernel and GRUB configuration.\n\n"
                        + "You will be asked for administrative privileges.",
                "Upgrade System", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        setRunning(true);
        output.setText("Running lfs-update upgrade (this can take a while)...");
        new SwingWorker<PrivilegedRunner.PrivilegedResult, Void>() {
            @Override
            protected PrivilegedRunner.PrivilegedResult doInBackground() {
                return LfsUpdateService.runUpgrade();
            }

            @Override
            protected void done() {
                try {
                    reportUpgrade(get());
                } catch (Exception ex) {
                    fail(ex);
                } finally {
                    setRunning(false);
                    refreshStatus();
                }
            }
        }.execute();
    }

    private void reportUpgrade(PrivilegedRunner.PrivilegedResult r) {
        if (r.isSuccess()) {
            noteLabel.setText("The system upgrade completed successfully.");
            output.setText(LfsUpdateService.stripAnsi(
                    r.getOutput().isEmpty() ? "(lfs-update produced no output)" : r.getOutput()));
        } else if (r.getStatus() == PrivilegedRunner.Status.CANCELLED) {
            noteLabel.setText("The privilege prompt was cancelled; no change was made.");
        } else if (r.getStatus() == PrivilegedRunner.Status.UNAVAILABLE) {
            noteLabel.setText("Privilege escalation is unavailable; the system cannot be upgraded.");
        } else {
            noteLabel.setText("The upgrade failed: " + r.getMessage());
            output.setText(LfsUpdateService.stripAnsi(r.getMessage()));
        }
        output.setCaretPosition(0);
    }

    private void fail(Exception ex) {
        noteLabel.setText("The operation could not be completed: " + ex.getMessage());
    }

    /** ANSI-stripped stdout+stderr of a read-only result, for verbatim display. */
    private String render(ProcessRunner.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("exit code ").append(r.getExitCode()).append('\n');
        if (!r.getStdout().isEmpty()) {
            sb.append(r.getStdout());
        }
        if (!r.getStderr().isEmpty()) {
            sb.append(sb.length() > 0 ? "\n" : "").append(r.getStderr());
        }
        return LfsUpdateService.stripAnsi(sb.toString());
    }
}
