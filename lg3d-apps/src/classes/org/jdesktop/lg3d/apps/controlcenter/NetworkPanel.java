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
import java.util.List;
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
import org.jdesktop.lg3d.utils.system.NetworkService;
import org.jdesktop.lg3d.utils.system.NetworkService.Backend;
import org.jdesktop.lg3d.utils.system.NetworkService.NetInterface;
import org.jdesktop.lg3d.utils.system.NetworkService.Operation;
import org.jdesktop.lg3d.utils.system.PrivilegedRunner;
import org.jdesktop.lg3d.utils.system.ProcessRunner;

/**
 * Network panel (Phase 3 of the LFS system-management contract,
 * {@code system-management-contract.md} §4.4). A thin front-end over
 * {@link NetworkService}: it detects the running network backend
 * (NetworkManager &rarr; dhcpcd &rarr; systemd-networkd &rarr; a read-only
 * {@code ip} fallback), lists its connections/links and shows read-only
 * link/address state, and drives connect (up) / disconnect (down) through that
 * backend's native CLI. It never re-implements networking logic and never edits
 * network config directly - all mutations go through the owning CLI (§6).
 *
 * <p>Read-only views ({@code nmcli device status}, {@code networkctl status},
 * {@code ip addr}, the {@code /etc/dhcpcd.conf} interface list) run unprivileged
 * through {@link ProcessRunner} (§4.4: read-only probes MUST be unprivileged).
 * Mutating operations are confirmed first; on {@code dhcpcd}/{@code networkd}
 * they are escalated per-operation through {@link PrivilegedRunner} (polkit),
 * while on NetworkManager {@code nmcli} runs unprivileged and the daemon raises
 * its own single authorization prompt (§7 item 5). The read-only {@code ip}
 * fallback and a {@link Backend#NONE} host cannot mutate, so connect/disconnect
 * are disabled there.</p>
 *
 * <p>Every operation runs off the EDT behind an indeterminate progress bar with
 * all controls disabled while in flight (serialization, §3.3; long-running ops,
 * §3.6); a non-zero exit is shown verbatim as state, not treated as a crash
 * (§3.2). The connection list is a {@link JList} (never a combo box) so the
 * panel keeps working when hosted offscreen in a {@code SwingNode} on the 3D
 * desktop; the same panel serves the 2D desktop. Like the other control-center
 * panels this one is registered lazily (see {@link ControlPanelRegistry});
 * {@link #reload()} detects the backend and shells out to the read-only list
 * command, so it runs only when the category is opened, never up front.</p>
 */
public class NetworkPanel implements ControlPanel {

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final DefaultListModel<NetInterface> model = new DefaultListModel<>();
    private final JList<NetInterface> ifaceList = new JList<>(model);
    private final JTextArea details = new JTextArea();
    private final JLabel headerLabel = new JLabel(" ");
    private final JLabel noteLabel = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar();

    private final JButton refreshButton = new JButton("Refresh");
    private final JButton statusButton = new JButton("Status");
    private final JButton connectButton = new JButton("Connect");
    private final JButton disconnectButton = new JButton("Disconnect");

    /** True while a background operation is in flight (serialization guard). */
    private boolean running;

    /** The backend detected by the most recent {@link #reload()}. */
    private Backend backend = Backend.NONE;

    public NetworkPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        ifaceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ifaceList.setVisibleRowCount(10);
        ifaceList.addListSelectionListener(e -> {
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
        connectButton.addActionListener(e -> mutate(Operation.UP));
        disconnectButton.addActionListener(e -> mutate(Operation.DOWN));

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildCenter(), BorderLayout.CENTER);
        root.add(buildSouth(), BorderLayout.SOUTH);

        reload();
    }

    @Override
    public String displayName() {
        return "Network";
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
                new JScrollPane(ifaceList), new JScrollPane(details));
        split.setDividerLocation(300);
        split.setResizeWeight(0.45);
        split.setBorder(BorderFactory.createEmptyBorder());
        return split;
    }

    private JComponent buildSouth() {
        JPanel south = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(refreshButton);
        buttons.add(statusButton);
        buttons.add(connectButton);
        buttons.add(disconnectButton);
        south.add(buttons, BorderLayout.NORTH);
        south.add(progress, BorderLayout.SOUTH);
        return south;
    }

    // ------------------------------------------------------------------

    /**
     * Re-detects the backend and re-lists its connections/links, updating the
     * header, the read-only/mutating note and the button availability. Detection
     * only probes {@code PATH} (headless-safe); a {@link Backend#NONE} host lists
     * nothing and spawns no process.
     */
    private void reload() {
        backend = NetworkService.detect();
        if (backend == Backend.NONE) {
            headerLabel.setText("Network: unavailable");
            noteLabel.setText("No network manager was found (nmcli, dhcpcd, networkctl "
                    + "and ip are all absent); networking cannot be shown.");
            model.clear();
            details.setText("");
            setRunning(false);
            return;
        }

        String selectedName = null;
        NetInterface sel = ifaceList.getSelectedValue();
        if (sel != null) {
            selectedName = sel.getName();
        }

        model.clear();
        List<NetInterface> found = NetworkService.listInterfaces(backend);
        for (NetInterface n : found) {
            model.addElement(n);
        }
        headerLabel.setText("Network: " + backendLabel(backend)
                + "   -   " + found.size() + " connection(s)/link(s)");

        if (backend == Backend.IP) {
            noteLabel.setText("Read-only fallback: no network manager was detected; link "
                    + "state is shown via 'ip' and connect/disconnect are unavailable.");
        } else if (NetworkService.needsPrivileges(backend, Operation.UP)
                && !PrivilegedRunner.isAvailable()) {
            noteLabel.setText("Privilege escalation (pkexec) is unavailable; "
                    + "connect/disconnect are read-only.");
        } else {
            noteLabel.setText(" ");
        }

        // Restore the previous selection by name, else select the first row.
        if (selectedName != null) {
            for (int i = 0; i < model.size(); i++) {
                if (model.get(i).getName().equals(selectedName)) {
                    ifaceList.setSelectedIndex(i);
                    updateButtons();
                    return;
                }
            }
        }
        if (!model.isEmpty()) {
            ifaceList.setSelectedIndex(0);
        } else {
            details.setText("");
        }
        updateButtons();
    }

    /** Enables/disables the action buttons for the current backend + selection. */
    private void updateButtons() {
        NetInterface sel = ifaceList.getSelectedValue();
        boolean idle = !running;
        boolean readable = backend != Backend.NONE;
        boolean canMutate = NetworkService.isSupported(backend, Operation.UP)
                && (!NetworkService.needsPrivileges(backend, Operation.UP)
                        || PrivilegedRunner.isAvailable());
        boolean statusNeedsTarget = NetworkService.requiresTarget(backend, Operation.STATUS);

        refreshButton.setEnabled(idle && readable);
        statusButton.setEnabled(idle && readable && (!statusNeedsTarget || sel != null));
        connectButton.setEnabled(idle && canMutate && sel != null);
        disconnectButton.setEnabled(idle && canMutate && sel != null);
    }

    /** Shows/hides the progress bar and refreshes every control's enabled state. */
    private void setRunning(boolean value) {
        running = value;
        progress.setVisible(value);
        updateButtons();
    }

    // ------------------------------------------------------------------
    // Read-only actions (off the EDT, unprivileged).

    private void showStatus() {
        if (running || backend == Backend.NONE) {
            return;
        }
        NetInterface sel = ifaceList.getSelectedValue();
        boolean needsTarget = NetworkService.requiresTarget(backend, Operation.STATUS);
        if (needsTarget && sel == null) {
            noteLabel.setText("Select an interface first.");
            return;
        }
        final String target = needsTarget ? sel.getName() : null;
        final String title = describeVector(Operation.STATUS, target);
        setRunning(true);
        details.setText("Querying '" + title + "'...");
        new SwingWorker<ProcessRunner.Result, Void>() {
            @Override
            protected ProcessRunner.Result doInBackground() {
                return NetworkService.runRead(backend, Operation.STATUS, target);
            }

            @Override
            protected void done() {
                try {
                    showReadResult(title, get());
                } catch (Exception ex) {
                    fail(ex);
                } finally {
                    setRunning(false);
                }
            }
        }.execute();
    }

    /** Renders a read-only result for the details pane, errors verbatim (§3.2). */
    private void showReadResult(String title, ProcessRunner.Result r) {
        StringBuilder sb = new StringBuilder(title).append("\n\n");
        if (!r.isStarted()) {
            sb.append("The command could not be started: ").append(r.getStderr());
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

    // ------------------------------------------------------------------
    // Mutating actions (confirmed, escalated per backend, off the EDT).

    private void mutate(Operation op) {
        if (running) {
            return;
        }
        if (!NetworkService.isSupported(backend, op)) {
            noteLabel.setText(label(op) + " is not supported on the "
                    + backendLabel(backend) + " backend.");
            return;
        }
        NetInterface sel = ifaceList.getSelectedValue();
        if (sel == null) {
            noteLabel.setText("Select a connection/link first.");
            return;
        }
        final String target = sel.getName();
        if (NetworkService.buildCommand(backend, op, target).isEmpty()) {
            noteLabel.setText("Could not build a command for " + target + ".");
            return;
        }
        boolean escalate = NetworkService.needsPrivileges(backend, op);
        if (escalate && !PrivilegedRunner.isAvailable()) {
            noteLabel.setText("Privilege escalation (pkexec) is unavailable; "
                    + "connect/disconnect are read-only.");
            return;
        }
        if (!confirmMutating(op, target, escalate)) {
            noteLabel.setText("Cancelled; no change was made.");
            return;
        }

        setRunning(true);
        details.setText("Running '" + describeVector(op, target) + "'...\n\n"
                + (escalate
                        ? "Approve the administrative-privilege prompt."
                        : "NetworkManager will ask for authorization if required."));
        new SwingWorker<PrivilegedRunner.PrivilegedResult, Void>() {
            @Override
            protected PrivilegedRunner.PrivilegedResult doInBackground() {
                return NetworkService.runMutating(backend, op, target);
            }

            @Override
            protected void done() {
                try {
                    report(get(), op, target);
                } catch (Exception ex) {
                    fail(ex);
                } finally {
                    setRunning(false);
                    reload();
                }
            }
        }.execute();
    }

    private void report(PrivilegedRunner.PrivilegedResult r, Operation op, String target) {
        if (r.isSuccess()) {
            noteLabel.setText(label(op) + " succeeded on " + target + ".");
            details.setText(r.getOutput().isEmpty()
                    ? "(" + label(op) + " completed with no output)"
                    : r.getOutput());
        } else if (r.getStatus() == PrivilegedRunner.Status.CANCELLED) {
            noteLabel.setText("The privilege prompt was cancelled; no change was made.");
        } else if (r.getStatus() == PrivilegedRunner.Status.UNAVAILABLE) {
            noteLabel.setText("Privilege escalation is unavailable; connect/disconnect are read-only.");
        } else {
            noteLabel.setText(label(op) + " failed: " + r.getMessage());
            details.setText(r.getMessage());
        }
        details.setCaretPosition(0);
    }

    private void fail(Exception ex) {
        noteLabel.setText("The operation could not be completed: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Confirmation / label helpers.

    private boolean confirmMutating(Operation op, String target, boolean escalate) {
        String msg = label(op) + " '" + target + "' on the " + backendLabel(backend)
                + " backend?\n\n"
                + (escalate
                        ? "You will be asked for administrative privileges."
                        : "NetworkManager will ask for authorization if required.");
        return JOptionPane.showConfirmDialog(root, msg, label(op),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private String backendLabel(Backend b) {
        if (b == null) {
            return "none";
        }
        switch (b) {
            case NETWORKMANAGER:
                return "NetworkManager (nmcli)";
            case DHCPCD:
                return "dhcpcd";
            case NETWORKD:
                return "systemd-networkd (networkctl)";
            case IP:
                return "ip (read-only)";
            case NONE:
            default:
                return "none";
        }
    }

    private String label(Operation op) {
        if (op == null) {
            return "";
        }
        switch (op) {
            case LIST:
                return "List";
            case STATUS:
                return "Status";
            case UP:
                return "Connect";
            case DOWN:
                return "Disconnect";
            default:
                return op.name();
        }
    }

    /** The exact argument vector about to run, for transparent display. */
    private String describeVector(Operation op, String target) {
        return String.join(" ", NetworkService.buildCommand(backend, op, target));
    }
}
