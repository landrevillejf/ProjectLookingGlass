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
package org.jdesktop.lg3d.apps.firewall;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import org.jdesktop.lg3d.utils.system.PrivilegedRunner;
import org.jdesktop.lg3d.utils.system.ProcessRunner;
import org.jdesktop.lg3d.utils.system.SecurityService;

/**
 * The firewall's Swing UI. It has two views:
 *
 * <ul>
 *   <li><b>Rules</b> - the legacy firewalld/iptables status and rule table,
 *       refreshed on demand from {@link FirewallService}, with Enable / Disable
 *       controls;</li>
 *   <li><b>nftables</b> - the modern backend the LFS/BLFS contract §4.6 drives:
 *       a read-only {@code nft list ruleset} view plus a confirmed,
 *       polkit-escalated "apply {@code /etc/nftables.conf}" action, all through
 *       the shared {@link SecurityService}.</li>
 * </ul>
 *
 * <p>The nftables view never re-implements firewall logic: it only builds the
 * argument vector and renders the tool's output verbatim (§6). The read runs
 * unprivileged through {@link ProcessRunner}; the apply is confirmed first and
 * then escalated through {@link PrivilegedRunner} (polkit), so a cancelling user
 * aborts cleanly with no partial change (§7 items 4-5). Both nft operations run
 * off the EDT behind an indeterminate progress bar with the controls disabled
 * while in flight (serialization, §3.3), and a non-zero exit is shown as state,
 * not treated as a crash (§3.2).</p>
 *
 * <p>The panel is plain Swing so the same UI drives the 3D desktop (hosted in a
 * {@code SwingNode}) and the 2D desktop. It owns a five-second refresh
 * {@link Timer} and exposes {@link #setOnClose(Runnable)} / {@link #stop()} so
 * the host can disable the {@code Frame3D} and the timer is always stopped on
 * close (leak guard). Construction is headless-safe: no external command runs
 * until the user presses Refresh / Reload / Apply.</p>
 */
public class FirewallPanel extends JPanel {

    private static final int PANEL_W = 700;
    private static final int PANEL_H = 500;
    private static final int REFRESH_MS = 5000;
    private static final Color BG = new Color(238, 240, 244);

    // --- Rules tab (legacy firewalld / iptables) ---
    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Protocol", "Source", "Destination", "Port", "Action", "Target"}, 0);
    private final JTable table = new JTable(model);
    private final JLabel statusLabel = new JLabel("Loading...");
    private final JLabel summaryLabel = new JLabel(" ");
    private final Timer timer;

    private boolean refreshing;
    private Runnable onClose;

    // --- nftables tab (contract §4.6, over SecurityService) ---
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel nftSummaryLabel = new JLabel("nftables: -");
    private final JLabel nftNote = new JLabel(" ");
    private final JTextArea nftArea = new JTextArea();
    private final JProgressBar nftProgress = new JProgressBar();
    private final JButton nftReloadBtn = new JButton("Reload Ruleset");
    private final JButton nftApplyBtn = new JButton("Apply /etc/nftables.conf");

    /** True while an nft background operation is in flight (serialization guard). */
    private boolean nftBusy;
    /** Whether the {@code nft} backend is installed (resolved once, headless-safe). */
    private final boolean nftAvailable;

    public FirewallPanel() {
        super(new BorderLayout(6, 6));
        setPreferredSize(new Dimension(PANEL_W, PANEL_H));
        setBackground(BG);
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        buildTable();

        tabs.addTab("Rules", new JScrollPane(table));
        tabs.addTab("nftables", buildNftTab());

        add(buildHeader(), BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(buildControls(), BorderLayout.SOUTH);

        // Resolve nft availability without spawning a process (PATH probe only),
        // so the panel still constructs headless.
        nftAvailable = SecurityService.isNftAvailable();
        if (!nftAvailable) {
            nftSummaryLabel.setText("nftables: not installed");
            nftNote.setText("The 'nft' tool was not found, so the nftables view is "
                    + "unavailable. The Rules tab still shows firewalld/iptables.");
        }
        updateNftButtons();

        timer = new Timer(REFRESH_MS, e -> refresh());
        timer.setInitialDelay(0);
        // Timer not started automatically to avoid repeated authentication prompts
        // User can manually refresh via the "Refresh Now" button
        // Initial status set to "Click Refresh to load"
        statusLabel.setText("Click Refresh to load");
        statusLabel.setForeground(Color.GRAY);
    }

    /** Sets the callback invoked when the user presses Close. */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /** Stops the refresh timer (call when the window closes). */
    public void stop() {
        timer.stop();
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

        table.getColumnModel().getColumn(0).setPreferredWidth(70);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);
        table.getColumnModel().getColumn(4).setPreferredWidth(70);
        table.getColumnModel().getColumn(5).setPreferredWidth(80);

        table.getColumnModel().getColumn(4).setCellRenderer(new ActionRenderer());
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(6, 6));
        header.setBackground(BG);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        statusPanel.setBackground(BG);
        statusPanel.add(new JLabel("Status:"));
        statusPanel.add(statusLabel);
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));

        header.add(statusPanel, BorderLayout.WEST);
        header.add(summaryLabel, BorderLayout.EAST);

        return header;
    }

    private JPanel buildControls() {
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        controls.setBackground(BG);
        controls.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

        JButton enableBtn = new JButton("Enable Firewall");
        JButton disableBtn = new JButton("Disable Firewall");
        JButton refreshBtn = new JButton("Refresh Now");
        JButton closeBtn = new JButton("Close");

        enableBtn.addActionListener(e -> enableFirewall());
        disableBtn.addActionListener(e -> disableFirewall());
        refreshBtn.addActionListener(e -> refresh());
        closeBtn.addActionListener(e -> {
            stop();
            if (onClose != null) {
                onClose.run();
            }
        });

        controls.add(enableBtn);
        controls.add(disableBtn);
        controls.add(refreshBtn);
        controls.add(closeBtn);

        return controls;
    }

    /** Builds the read-only nftables ruleset view plus the confirmed apply action. */
    private Component buildNftTab() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBackground(BG);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel header = new JPanel(new BorderLayout(2, 2));
        header.setBackground(BG);
        nftSummaryLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        nftNote.setForeground(new Color(160, 90, 20));
        header.add(nftSummaryLabel, BorderLayout.NORTH);
        header.add(nftNote, BorderLayout.SOUTH);
        panel.add(header, BorderLayout.NORTH);

        nftArea.setEditable(false);
        nftArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JScrollPane(nftArea), BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(BG);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        buttons.setBackground(BG);
        nftReloadBtn.addActionListener(e -> reloadNft());
        nftApplyBtn.addActionListener(e -> applyNft());
        buttons.add(nftReloadBtn);
        buttons.add(nftApplyBtn);
        south.add(buttons, BorderLayout.NORTH);

        nftProgress.setIndeterminate(true);
        nftProgress.setVisible(false);
        south.add(nftProgress, BorderLayout.SOUTH);
        panel.add(south, BorderLayout.SOUTH);

        return panel;
    }

    // ------------------------------------------------------------------
    // Rules-tab actions (legacy firewalld / iptables)

    private void enableFirewall() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Enable the firewall? This may affect network connectivity.",
                "Enable Firewall",
                JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    FirewallService.enableFirewall();
                    return null;
                }

                @Override
                protected void done() {
                    refresh();
                }
            }.execute();
        }
    }

    private void disableFirewall() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Disable the firewall? This may expose your system to network threats.",
                "Disable Firewall",
                JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    FirewallService.disableFirewall();
                    return null;
                }

                @Override
                protected void done() {
                    refresh();
                }
            }.execute();
        }
    }

    private void refresh() {
        if (refreshing) {
            return;
        }
        refreshing = true;

        new SwingWorker<FirewallStatus, Void>() {
            @Override
            protected FirewallStatus doInBackground() throws Exception {
                return FirewallService.getStatus();
            }

            @Override
            protected void done() {
                try {
                    FirewallStatus status = get();
                    updateUI(status);
                } catch (Exception ex) {
                    statusLabel.setText("Error: " + ex.getMessage());
                    statusLabel.setForeground(Color.RED);
                } finally {
                    refreshing = false;
                }
            }
        }.execute();
    }

    private void updateUI(FirewallStatus status) {
        SwingUtilities.invokeLater(() -> {
            // Update status label
            if (status.enabled) {
                statusLabel.setText("Active");
                statusLabel.setForeground(new Color(0, 128, 0));
            } else {
                statusLabel.setText("Inactive");
                statusLabel.setForeground(Color.RED);
            }

            // Update summary
            summaryLabel.setText(String.format("%d rules", status.rules.size()));

            // Update table
            model.setRowCount(0);
            for (FirewallRule rule : status.rules) {
                model.addRow(new Object[]{
                    rule.protocol,
                    rule.source,
                    rule.destination,
                    rule.port,
                    rule.action,
                    rule.target
                });
            }
        });
    }

    // ------------------------------------------------------------------
    // nftables-tab actions (contract §4.6, over SecurityService)

    /** Reads the live ruleset unprivileged, off the EDT, and renders it verbatim. */
    private void reloadNft() {
        if (nftBusy || !nftAvailable) {
            return;
        }
        setNftBusy(true);
        nftNote.setText(" ");
        nftArea.setText("Running 'nft list ruleset'...");
        new SwingWorker<ProcessRunner.Result, Void>() {
            @Override
            protected ProcessRunner.Result doInBackground() {
                return SecurityService.runRead(SecurityService.Operation.NFT_LIST);
            }

            @Override
            protected void done() {
                try {
                    renderNftRead(get());
                } catch (Exception ex) {
                    nftNote.setText("Could not read the ruleset: " + ex.getMessage());
                } finally {
                    setNftBusy(false);
                }
            }
        }.execute();
    }

    /** Renders a read-only {@code nft list ruleset} result, errors verbatim (§3.2). */
    private void renderNftRead(ProcessRunner.Result r) {
        if (!r.isStarted()) {
            nftSummaryLabel.setText("nftables: unavailable");
            nftArea.setText("The ruleset could not be read: " + r.getStderr());
        } else if (r.isSuccess()) {
            SecurityService.NftSummary summary = SecurityService.summarizeNft(r.getStdout());
            nftSummaryLabel.setText("nftables: " + summary.describe());
            nftArea.setText(r.getStdout().isEmpty() ? "(empty ruleset)" : r.getStdout());
        } else {
            // A non-zero exit is a result, not a crash: show it verbatim. Listing
            // the ruleset often needs privileges; surface nft's own message.
            nftSummaryLabel.setText("nftables: exit code " + r.getExitCode());
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
            nftArea.setText(sb.length() == 0 ? "(no output)" : sb.toString());
        }
        nftArea.setCaretPosition(0);
    }

    /** Applies {@code /etc/nftables.conf} after confirmation, escalated via polkit. */
    private void applyNft() {
        if (nftBusy || !nftAvailable) {
            return;
        }
        if (!PrivilegedRunner.isAvailable()) {
            nftNote.setText("Privilege escalation (pkexec) is unavailable; cannot apply.");
            return;
        }
        if (!confirmApply()) {
            nftNote.setText("Cancelled; no change was made.");
            return;
        }
        setNftBusy(true);
        nftArea.setText("Running 'nft -f /etc/nftables.conf'...\n\n"
                + "Approve the administrative-privilege prompt.");
        new SwingWorker<PrivilegedRunner.PrivilegedResult, Void>() {
            @Override
            protected PrivilegedRunner.PrivilegedResult doInBackground() {
                return SecurityService.runMutating(SecurityService.Operation.NFT_APPLY);
            }

            @Override
            protected void done() {
                try {
                    reportNftApply(get());
                } catch (Exception ex) {
                    nftNote.setText("Apply failed: " + ex.getMessage());
                } finally {
                    setNftBusy(false);
                    reloadNft();
                }
            }
        }.execute();
    }

    /** Reports an apply result verbatim, distinguishing a cancelled prompt (§7 item 5). */
    private void reportNftApply(PrivilegedRunner.PrivilegedResult r) {
        if (r.isSuccess()) {
            nftNote.setText("Applied /etc/nftables.conf successfully.");
            nftArea.setText(r.getOutput().isEmpty()
                    ? "(apply completed with no output)" : r.getOutput());
        } else if (r.getStatus() == PrivilegedRunner.Status.CANCELLED) {
            nftNote.setText("The privilege prompt was cancelled; no change was made.");
        } else if (r.getStatus() == PrivilegedRunner.Status.UNAVAILABLE) {
            nftNote.setText("Privilege escalation is unavailable; cannot apply.");
        } else {
            nftNote.setText("Apply failed: " + r.getMessage());
            nftArea.setText(r.getMessage());
        }
        nftArea.setCaretPosition(0);
    }

    private boolean confirmApply() {
        String msg = "Apply (reload) the firewall ruleset from\n"
                + "    /etc/nftables.conf ?\n\n"
                + "This runs 'nft -f /etc/nftables.conf' and replaces the live\n"
                + "ruleset. You will be asked for administrative privileges.";
        return JOptionPane.showConfirmDialog(this, msg, "Apply nftables ruleset",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    /** Shows/hides the nft progress bar and refreshes the nft button availability. */
    private void setNftBusy(boolean value) {
        nftBusy = value;
        nftProgress.setVisible(value);
        updateNftButtons();
    }

    /** Enables the nft controls only when idle, the backend exists and apply can escalate. */
    private void updateNftButtons() {
        boolean idle = !nftBusy;
        nftReloadBtn.setEnabled(idle && nftAvailable);
        nftApplyBtn.setEnabled(idle && nftAvailable && PrivilegedRunner.isAvailable());
    }

    // ------------------------------------------------------------------
    // Test hooks (package-visible; the panel is plain Swing, so it constructs
    // headless and never spawns a process until the user acts).

    /** The number of tabs (Rules + nftables). */
    int tabCount() {
        return tabs.getTabCount();
    }

    /** The title of tab {@code i}. */
    String tabTitle(int i) {
        return tabs.getTitleAt(i);
    }

    /** Whether the nftables backend was detected at construction. */
    boolean nftAvailable() {
        return nftAvailable;
    }

    /** Whether the nft "Reload Ruleset" control is enabled. */
    boolean nftReloadEnabled() {
        return nftReloadBtn.isEnabled();
    }

    /** Whether the nft "Apply /etc/nftables.conf" control is enabled. */
    boolean nftApplyEnabled() {
        return nftApplyBtn.isEnabled();
    }

    /** The nft summary header text. */
    String nftSummaryText() {
        return nftSummaryLabel.getText();
    }

    // ------------------------------------------------------------------
    // Renderers

    private static class ActionRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value,
                    isSelected, hasFocus, row, column);
            if ("ACCEPT".equals(value)) {
                setForeground(new Color(0, 128, 0));
            } else if ("DROP".equals(value) || "REJECT".equals(value)) {
                setForeground(Color.RED);
            } else {
                setForeground(Color.BLACK);
            }
            return c;
        }
    }
}
