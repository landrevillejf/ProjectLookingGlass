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
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import org.jdesktop.lg3d.utils.system.ConfigFileReader;
import org.jdesktop.lg3d.utils.system.HidsService;
import org.jdesktop.lg3d.utils.system.HidsService.Operation;
import org.jdesktop.lg3d.utils.system.HidsService.Tool;
import org.jdesktop.lg3d.utils.system.PrivilegedRunner;
import org.jdesktop.lg3d.utils.system.ProcessRunner;

/**
 * The Security Center's <em>HIDS</em> section (LFS/BLFS system-management
 * contract §4.10, inventoried in {@code lg3d-agent-package-inventory.md} §6.11):
 * a thin front-end over the shared {@link HidsService}. It surfaces the four
 * host-intrusion-detection tools the build profile ships - {@code fail2ban}
 * (intrusion prevention), {@code rkhunter} (rootkit scan), {@code lynis}
 * (security audit) and {@code auditd} (kernel audit trail) - as a list; the
 * selected tool's read-only queries run unprivileged through
 * {@link ProcessRunner}, and its mutations and long scans are confirmed first
 * and then escalated through {@link PrivilegedRunner} (polkit). The panel never
 * re-implements a scanner, a jail engine or the audit subsystem, and never
 * writes {@code /etc} (§6): the rkhunter log is shown read-only through
 * {@link ConfigFileReader}.
 *
 * <p>Every operation runs off the EDT behind an indeterminate progress bar with
 * the controls disabled while in flight (serialization, §3.3); the Cancel
 * control interrupts the background worker, which destroys the child process. A
 * non-zero exit, an absent tool or an unreadable log is shown verbatim as state,
 * never treated as a crash (§3.2), and a tool that is not installed is listed as
 * "not installed" with its actions disabled rather than raising an error.</p>
 *
 * <p>Like the rest of the Security Center this is plain Swing using only labels,
 * buttons, a list and a text area (never a {@code JComboBox}), so it renders
 * correctly when hosted offscreen in a {@code SwingNode} on the 3D desktop and
 * identically in the 2D desktop. Construction is headless-safe: availability is
 * resolved with non-spawning PATH probes and no command runs until the user
 * acts; the detection, vectors and parsers behind it are covered by
 * {@code HidsServiceTest} and {@code hids.feature} in lg3d-core.</p>
 */
public class HidsPanel extends JPanel {

    private final JLabel titleLabel = new JLabel("Host intrusion detection");
    private final JLabel noteLabel = new JLabel(" ");
    private final JTextArea viewer = new JTextArea();
    private final JProgressBar progress = new JProgressBar();

    private final DefaultListModel<Tool> toolModel = new DefaultListModel<>();
    private final JList<Tool> toolList = new JList<>(toolModel);
    private final JLabel rendererLabel = new JLabel();

    private final JLabel paramLabel = new JLabel("Parameter:");
    private final JTextField paramField = new JTextField(14);
    private final JLabel ipLabel = new JLabel("IP address:");
    private final JTextField ipField = new JTextField(14);

    // fail2ban actions
    private final JButton f2bStatusBtn = new JButton("Server status");
    private final JButton f2bJailBtn = new JButton("Jail status");
    private final JButton f2bBanBtn = new JButton("Ban IP");
    private final JButton f2bUnbanBtn = new JButton("Unban IP");
    // rkhunter actions
    private final JButton rkhScanBtn = new JButton("Scan (--check)");
    private final JButton rkhPropupdBtn = new JButton("Rebaseline (--propupd)");
    private final JButton rkhLogBtn = new JButton("View log");
    // lynis actions
    private final JButton lynisAuditBtn = new JButton("Audit system");
    // auditd actions
    private final JButton auditStatusBtn = new JButton("Status (auditctl -s)");
    private final JButton auditSearchBtn = new JButton("Search events");

    private final JButton cancelBtn = new JButton("Cancel");
    private final JPanel actionsCard = new JPanel(new CardLayout());

    /** True while a background operation is in flight (serialization guard). */
    private boolean busy;
    /** The in-flight worker, so Cancel can interrupt (and thus destroy) it. */
    private SwingWorker<?, ?> worker;

    // Availability, resolved once at construction without spawning a process.
    private final HidsService.Probes probes;
    private final boolean polkitAvailable;

    /** Builds the HIDS section over a live (non-spawning) probe of this host. */
    public HidsPanel() {
        super(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        this.probes = HidsService.probe();
        this.polkitAvailable = PrivilegedRunner.isAvailable();

        add(buildNorth(), BorderLayout.NORTH);
        add(buildToolList(), BorderLayout.WEST);
        add(buildViewer(), BorderLayout.CENTER);
        add(buildSouth(), BorderLayout.SOUTH);

        for (Tool t : Tool.values()) {
            toolModel.addElement(t);
        }
        wireListeners();
        if (toolModel.size() > 0) {
            toolList.setSelectedIndex(0);
        }
        if (HidsService.installedTools(probes).isEmpty()) {
            noteLabel.setText("None of the HIDS tools (fail2ban, rkhunter, lynis, auditd) "
                    + "are installed on this host; install one to use this section.");
        } else if (!polkitAvailable) {
            noteLabel.setText("Privilege escalation (pkexec) is unavailable; read-only queries "
                    + "work, but scans and bans are disabled.");
        }
        onToolSelected();
        updateButtons();
    }

    // ------------------------------------------------------------------
    // UI construction

    private Component buildNorth() {
        JPanel header = new JPanel(new BorderLayout(2, 2));
        titleLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        noteLabel.setForeground(new Color(160, 90, 20));
        header.add(titleLabel, BorderLayout.NORTH);
        header.add(noteLabel, BorderLayout.SOUTH);
        return header;
    }

    private Component buildToolList() {
        toolList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        toolList.setCellRenderer((list, value, index, selected, focus) -> {
            rendererLabel.setText(labelFor(value));
            rendererLabel.setOpaque(true);
            rendererLabel.setBackground(selected
                    ? list.getSelectionBackground() : list.getBackground());
            rendererLabel.setForeground(selected
                    ? list.getSelectionForeground() : list.getForeground());
            rendererLabel.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            return rendererLabel;
        });
        JScrollPane scroll = new JScrollPane(toolList);
        scroll.setBorder(BorderFactory.createTitledBorder("HIDS tools"));
        scroll.setPreferredSize(new Dimension(230, 100));
        return scroll;
    }

    private String labelFor(Tool tool) {
        String base = HidsService.describeTool(tool);
        return HidsService.isPresent(probes, tool) ? base : (base + " - not installed");
    }

    private Component buildViewer() {
        viewer.setEditable(false);
        viewer.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(viewer);
        scroll.setBorder(BorderFactory.createTitledBorder("Detail (verbatim output)"));
        return scroll;
    }

    private Component buildSouth() {
        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));

        JPanel paramRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        paramRow.add(paramLabel);
        paramRow.add(paramField);
        paramRow.add(ipLabel);
        paramRow.add(ipField);
        south.add(paramRow);

        actionsCard.add(emptyCard(), "NONE");
        actionsCard.add(fail2banCard(), Tool.FAIL2BAN.name());
        actionsCard.add(rkhunterCard(), Tool.RKHUNTER.name());
        actionsCard.add(lynisCard(), Tool.LYNIS.name());
        actionsCard.add(auditdCard(), Tool.AUDITD.name());
        actionsCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        south.add(actionsCard);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        bottom.add(cancelBtn);
        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(200, 20));
        bottom.add(progress);
        bottom.setAlignmentX(Component.LEFT_ALIGNMENT);
        south.add(bottom);
        south.add(Box.createVerticalStrut(2));
        return south;
    }

    private Component emptyCard() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.add(new JLabel("Select a tool."));
        return p;
    }

    private Component fail2banCard() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setBorder(BorderFactory.createTitledBorder("fail2ban"));
        p.add(f2bStatusBtn);
        p.add(f2bJailBtn);
        p.add(f2bBanBtn);
        p.add(f2bUnbanBtn);
        return p;
    }

    private Component rkhunterCard() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setBorder(BorderFactory.createTitledBorder("rkhunter"));
        p.add(rkhScanBtn);
        p.add(rkhPropupdBtn);
        p.add(rkhLogBtn);
        return p;
    }

    private Component lynisCard() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setBorder(BorderFactory.createTitledBorder("lynis"));
        p.add(lynisAuditBtn);
        return p;
    }

    private Component auditdCard() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setBorder(BorderFactory.createTitledBorder("auditd"));
        p.add(auditStatusBtn);
        p.add(auditSearchBtn);
        return p;
    }

    // ------------------------------------------------------------------
    // Wiring / serialization

    private void wireListeners() {
        toolList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onToolSelected();
            }
        });
        f2bStatusBtn.addActionListener(e -> readFail2BanStatus());
        f2bJailBtn.addActionListener(e -> readFail2BanJail());
        f2bBanBtn.addActionListener(e -> ban(false));
        f2bUnbanBtn.addActionListener(e -> ban(true));
        rkhScanBtn.addActionListener(e -> scan(Operation.RKHUNTER_CHECK, "rkhunter scan"));
        rkhPropupdBtn.addActionListener(e -> scan(Operation.RKHUNTER_PROPUPD, "rkhunter rebaseline"));
        rkhLogBtn.addActionListener(e -> viewRkhunterLog());
        lynisAuditBtn.addActionListener(e -> scan(Operation.LYNIS_AUDIT, "lynis audit"));
        auditStatusBtn.addActionListener(e -> readAuditdStatus());
        auditSearchBtn.addActionListener(e -> searchEvents());
        cancelBtn.addActionListener(e -> cancel());
    }

    /** Shows the selected tool's action card and adapts the parameter labels. */
    private void onToolSelected() {
        Tool sel = selectedTool();
        ((CardLayout) actionsCard.getLayout())
                .show(actionsCard, sel == null ? "NONE" : sel.name());
        if (sel == Tool.FAIL2BAN) {
            paramLabel.setText("Jail:");
        } else if (sel == Tool.AUDITD) {
            paramLabel.setText("Event type:");
        } else {
            paramLabel.setText("Parameter:");
        }
        boolean ipRelevant = sel == Tool.FAIL2BAN;
        ipLabel.setVisible(ipRelevant);
        ipField.setVisible(ipRelevant);
        updateButtons();
    }

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisible(value);
        updateButtons();
    }

    /** Gates each control on the selection, its availability and the busy flag. */
    private void updateButtons() {
        boolean idle = !busy;
        Tool sel = selectedTool();
        setEnabled(f2bStatusBtn, idle, sel, Tool.FAIL2BAN, false);
        setEnabled(f2bJailBtn, idle, sel, Tool.FAIL2BAN, false);
        setEnabled(f2bBanBtn, idle, sel, Tool.FAIL2BAN, true);
        setEnabled(f2bUnbanBtn, idle, sel, Tool.FAIL2BAN, true);
        setEnabled(rkhScanBtn, idle, sel, Tool.RKHUNTER, true);
        setEnabled(rkhPropupdBtn, idle, sel, Tool.RKHUNTER, true);
        setEnabled(rkhLogBtn, idle, sel, Tool.RKHUNTER, false);
        setEnabled(lynisAuditBtn, idle, sel, Tool.LYNIS, true);
        setEnabled(auditStatusBtn, idle, sel, Tool.AUDITD, false);
        setEnabled(auditSearchBtn, idle, sel, Tool.AUDITD, false);
        cancelBtn.setEnabled(busy);
    }

    private void setEnabled(JButton b, boolean idle, Tool sel, Tool owner, boolean mutating) {
        boolean available = HidsService.isPresent(probes, owner) && (!mutating || polkitAvailable);
        b.setEnabled(idle && sel == owner && available);
    }

    private void cancel() {
        SwingWorker<?, ?> w = worker;
        if (w != null) {
            w.cancel(true);
        }
    }

    // ------------------------------------------------------------------
    // Read-only actions (off the EDT, unprivileged).

    private void readFail2BanStatus() {
        runRead("fail2ban status", Operation.FAIL2BAN_STATUS);
    }

    private void readFail2BanJail() {
        String jail = paramField.getText().trim();
        if (jail.isEmpty()) {
            noteLabel.setText("Enter a jail name (for example 'sshd') first.");
            return;
        }
        runRead("fail2ban status " + jail, Operation.FAIL2BAN_JAIL_STATUS, jail);
    }

    private void readAuditdStatus() {
        runRead("auditctl -s", Operation.AUDITCTL_STATUS);
    }

    private void searchEvents() {
        String type = paramField.getText().trim();
        if (type.isEmpty()) {
            noteLabel.setText("Enter an audit message type (for example 'USER_AUTH' or 'avc').");
            return;
        }
        runRead("ausearch -m " + type, Operation.AUSEARCH, type);
    }

    private void runRead(String label, Operation op, String... params) {
        if (busy) {
            return;
        }
        setBusy(true);
        noteLabel.setText(" ");
        viewer.setText("Running '" + label + "'...");
        SwingWorker<ProcessRunner.Result, Void> w = new SwingWorker<>() {
            @Override
            protected ProcessRunner.Result doInBackground() {
                return HidsService.runRead(op, params);
            }

            @Override
            protected void done() {
                try {
                    if (isCancelled()) {
                        noteLabel.setText("Cancelled; the query was interrupted.");
                        return;
                    }
                    renderRead(label, op, get());
                } catch (Exception ex) {
                    noteLabel.setText("The query failed: " + ex.getMessage());
                } finally {
                    setBusy(false);
                }
            }
        };
        worker = w;
        w.execute();
    }

    private void renderRead(String label, Operation op, ProcessRunner.Result r) {
        if (!r.isStarted()) {
            noteLabel.setText(label + ": unavailable");
            viewer.setText("The command could not be run: " + r.getStderr());
        } else {
            String out = combine(r.getStdout(), r.getStderr());
            viewer.setText(out.isEmpty() ? "(no output)" : out);
            viewer.setCaretPosition(0);
            noteLabel.setText(label + ": " + summarizeRead(op, out, r.getExitCode()));
        }
    }

    private static String summarizeRead(Operation op, String out, int exit) {
        return switch (op) {
            case FAIL2BAN_STATUS -> HidsService.parseFail2BanStatus(out).describe();
            case FAIL2BAN_JAIL_STATUS -> HidsService.parseFail2BanJail(out).describe();
            case AUDITCTL_STATUS -> HidsService.parseAuditdStatus(out).describe();
            case AUSEARCH -> HidsService.countAusearchEvents(out) + " event(s)";
            default -> (exit == 0) ? "ok" : ("exit " + exit);
        };
    }

    // ------------------------------------------------------------------
    // Read-only rkhunter log view (bounded, never escalated).

    private void viewRkhunterLog() {
        if (busy) {
            return;
        }
        setBusy(true);
        noteLabel.setText(" ");
        viewer.setText("Reading " + HidsService.RKHUNTER_LOG + "...");
        SwingWorker<ConfigFileReader.Content, Void> w = new SwingWorker<>() {
            @Override
            protected ConfigFileReader.Content doInBackground() {
                return ConfigFileReader.read(HidsService.RKHUNTER_LOG);
            }

            @Override
            protected void done() {
                try {
                    if (isCancelled()) {
                        noteLabel.setText("Cancelled.");
                        return;
                    }
                    renderLog(get());
                } catch (Exception ex) {
                    noteLabel.setText("Could not read the log: " + ex.getMessage());
                } finally {
                    setBusy(false);
                }
            }
        };
        worker = w;
        w.execute();
    }

    private void renderLog(ConfigFileReader.Content c) {
        noteLabel.setText("rkhunter log - " + c.describe());
        if (!c.isExists()) {
            viewer.setText(HidsService.RKHUNTER_LOG + " is not present on this system.");
        } else if (!c.isReadable()) {
            viewer.setText(HidsService.RKHUNTER_LOG + " exists but is not readable without "
                    + "privileges (it is usually root-only). The desktop reads it unprivileged "
                    + "and never escalates for a read-only view (§4.10).");
        } else {
            viewer.setText(c.getText().isEmpty() ? "(empty log)" : c.getText());
        }
        viewer.setCaretPosition(0);
    }

    // ------------------------------------------------------------------
    // Mutating / long actions (confirmed, escalated via polkit, off the EDT).

    private void ban(boolean unban) {
        String jail = paramField.getText().trim();
        String ip = ipField.getText().trim();
        if (jail.isEmpty() || ip.isEmpty()) {
            noteLabel.setText("Enter both a jail name and an IP address first.");
            return;
        }
        Operation op = unban ? Operation.FAIL2BAN_UNBAN : Operation.FAIL2BAN_BAN;
        String verb = unban ? "unban" : "ban";
        runMutating("fail2ban " + verb + " " + ip, op, jail, ip);
    }

    private void scan(Operation op, String label) {
        runMutating(label, op);
    }

    private void runMutating(String label, Operation op, String... params) {
        if (busy) {
            return;
        }
        if (!polkitAvailable) {
            noteLabel.setText("Privilege escalation (pkexec) is unavailable; cannot " + label + ".");
            return;
        }
        if (!confirm(label)) {
            noteLabel.setText("Cancelled; no change was made.");
            return;
        }
        setBusy(true);
        noteLabel.setText(" ");
        viewer.setText("Running '" + label + "'...\n\n"
                + "Approve the administrative-privilege prompt.");
        SwingWorker<PrivilegedRunner.PrivilegedResult, Void> w = new SwingWorker<>() {
            @Override
            protected PrivilegedRunner.PrivilegedResult doInBackground() {
                return HidsService.runMutating(op, params);
            }

            @Override
            protected void done() {
                try {
                    if (isCancelled()) {
                        noteLabel.setText("Cancelled; the operation was interrupted.");
                        viewer.setText("Cancelled.");
                        return;
                    }
                    renderMutate(label, op, get());
                } catch (Exception ex) {
                    noteLabel.setText("The operation failed: " + ex.getMessage());
                } finally {
                    setBusy(false);
                }
            }
        };
        worker = w;
        w.execute();
    }

    private void renderMutate(String label, Operation op, PrivilegedRunner.PrivilegedResult r) {
        if (r.isSuccess()) {
            String out = r.getOutput();
            noteLabel.setText(label + " succeeded - " + summarizeMutate(op, out));
            viewer.setText(out.isEmpty() ? "(completed with no output)" : out);
        } else if (r.getStatus() == PrivilegedRunner.Status.CANCELLED) {
            noteLabel.setText("The privilege prompt was cancelled; no change was made.");
        } else if (r.getStatus() == PrivilegedRunner.Status.UNAVAILABLE) {
            noteLabel.setText("Privilege escalation is unavailable; cannot " + label + ".");
        } else {
            noteLabel.setText(label + " failed: " + r.getMessage());
            viewer.setText(combine(r.getMessage(), r.getOutput()));
        }
        viewer.setCaretPosition(0);
    }

    private static String summarizeMutate(Operation op, String out) {
        if (op == Operation.RKHUNTER_CHECK) {
            return HidsService.parseRkhunter(out).describe();
        }
        if (op == Operation.LYNIS_AUDIT) {
            return HidsService.parseLynis(out).describe();
        }
        return "done";
    }

    private boolean confirm(String label) {
        String msg = "Do you want to run '" + label + "'?\n\n"
                + "This runs the owning tool's command with administrative privileges.\n"
                + "You will be asked to approve the privilege prompt.";
        return JOptionPane.showConfirmDialog(this, msg, "HIDS: " + label,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private static String combine(String a, String b) {
        String first = (a == null) ? "" : a;
        String second = (b == null) ? "" : b;
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        return first + "\n" + second;
    }

    // ------------------------------------------------------------------
    // Test hooks (package-visible; the panel is plain Swing, so it constructs
    // headless and never spawns a process until the user acts).

    /** The currently selected tool, or null when nothing is selected. */
    Tool selectedTool() {
        return toolList.getSelectedValue();
    }

    /** Selects a tool in the list (drives the card and the button gating). */
    void selectTool(Tool tool) {
        toolList.setSelectedValue(tool, false);
    }

    /** The guidance note text. */
    String noteText() {
        return noteLabel.getText();
    }

    /** The detail viewer text. */
    String viewerText() {
        return viewer.getText();
    }

    /** Whether a tool's binary was detected at construction. */
    boolean isInstalled(Tool tool) {
        return HidsService.isPresent(probes, tool);
    }

    /** Whether privilege escalation (pkexec) was available at construction. */
    boolean polkitAvailable() {
        return polkitAvailable;
    }

    /** Whether the fail2ban "Server status" control is enabled. */
    boolean f2bStatusEnabled() {
        return f2bStatusBtn.isEnabled();
    }

    /** Whether the fail2ban "Ban IP" control is enabled. */
    boolean f2bBanEnabled() {
        return f2bBanBtn.isEnabled();
    }

    /** Whether the rkhunter "Scan" control is enabled. */
    boolean rkhScanEnabled() {
        return rkhScanBtn.isEnabled();
    }

    /** Whether the rkhunter "View log" control is enabled. */
    boolean rkhLogEnabled() {
        return rkhLogBtn.isEnabled();
    }

    /** Whether the lynis "Audit system" control is enabled. */
    boolean lynisAuditEnabled() {
        return lynisAuditBtn.isEnabled();
    }

    /** Whether the auditd "Status" control is enabled. */
    boolean auditStatusEnabled() {
        return auditStatusBtn.isEnabled();
    }

    /** Whether the Cancel control is enabled (only while busy). */
    boolean cancelEnabled() {
        return cancelBtn.isEnabled();
    }

    /** The number of tools listed (all four, present or not). */
    int toolCount() {
        return toolModel.size();
    }
}
