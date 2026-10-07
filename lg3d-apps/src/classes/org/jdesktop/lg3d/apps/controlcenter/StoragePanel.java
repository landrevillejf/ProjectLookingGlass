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
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.jdesktop.lg3d.utils.system.LuksService;
import org.jdesktop.lg3d.utils.system.LuksService.Operation;
import org.jdesktop.lg3d.utils.system.PrivilegedRunner;
import org.jdesktop.lg3d.utils.system.ProcessRunner;
import org.jdesktop.lg3d.utils.system.StorageService;
import org.jdesktop.lg3d.utils.system.StorageService.BlockDevice;

/**
 * Storage &amp; LUKS panel (Phase 3 of the LFS system-management contract,
 * {@code system-management-contract.md} §4.5). A thin front-end over
 * {@link StorageService} (read-only {@code lsblk}/{@code blkid} discovery) and
 * {@link LuksService} (the {@code cryptsetup}/{@code lfs-encrypt-disk} vectors):
 * it lists block devices and drives LUKS status / open / close / add-key /
 * format and the installer's full-disk-encryption helper. It never re-implements
 * encryption logic and never edits {@code /etc/crypttab} or {@code /etc/fstab}
 * directly - all writes go through the owning CLI (§6).
 *
 * <p>Read-only views ({@code lsblk}, {@code blkid}, {@code cryptsetup status})
 * run unprivileged through {@link ProcessRunner}. Mutating operations
 * ({@code luksOpen}/{@code luksClose}/{@code luksAddKey}) are confirmed and
 * escalated per-operation through {@link PrivilegedRunner} (polkit); the
 * <em>destructive</em> operations ({@code luksFormat}, {@code lfs-encrypt-disk})
 * erase all data, so they additionally require an explicit <em>typed</em>
 * confirmation of the device path before the polkit prompt (§4.5). Passphrases
 * are collected into a {@link JPasswordField} and fed through stdin, never as
 * command-line arguments (§6).</p>
 *
 * <p>Every operation runs off the EDT behind an indeterminate progress bar with
 * all controls disabled while in flight (serialization, §3.3; long-running ops,
 * §3.6); a non-zero exit is shown verbatim as state, not treated as a crash
 * (§3.2). The device list is a {@link JList} (never a combo box) so the panel
 * keeps working when hosted offscreen in a {@code SwingNode} on the 3D desktop;
 * the same panel serves the 2D desktop. When {@code lsblk} or {@code cryptsetup}
 * is absent the panel degrades to a read-only note and, with {@code lsblk}
 * missing, constructs headless without spawning a process. Like the other
 * control-center panels this one is registered lazily (see
 * {@link ControlPanelRegistry}); {@link #reload()} shells out to {@code lsblk},
 * so it runs only when the category is opened, never up front.</p>
 */
public class StoragePanel implements ControlPanel {

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final DefaultListModel<BlockDevice> devices = new DefaultListModel<>();
    private final JList<BlockDevice> deviceList = new JList<>(devices);
    private final JTextArea details = new JTextArea();
    private final JLabel headerLabel = new JLabel(" ");
    private final JLabel noteLabel = new JLabel(" ");
    private final JTextField nameField = new JTextField(16);
    private final JProgressBar progress = new JProgressBar();

    private final JButton refreshButton = new JButton("Refresh");
    private final JButton statusButton = new JButton("LUKS Status");
    private final JButton uuidButton = new JButton("Show UUID");
    private final JButton openButton = new JButton("Open...");
    private final JButton closeButton = new JButton("Close");
    private final JButton addKeyButton = new JButton("Add Key...");
    private final JButton formatButton = new JButton("Format (LUKS2)...");
    private final JButton encryptDiskButton = new JButton("Encrypt Disk...");

    /** True while a background operation is in flight (serialization guard). */
    private boolean running;

    public StoragePanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        deviceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deviceList.setVisibleRowCount(12);
        deviceList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateButtons();
            }
        });

        details.setEditable(false);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        progress.setIndeterminate(true);
        progress.setVisible(false);

        nameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateButtons();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateButtons();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateButtons();
            }
        });

        refreshButton.addActionListener(e -> reload());
        statusButton.addActionListener(e -> showStatus());
        uuidButton.addActionListener(e -> showUuid());
        openButton.addActionListener(e -> mutate(Operation.OPEN));
        closeButton.addActionListener(e -> mutate(Operation.CLOSE));
        addKeyButton.addActionListener(e -> mutate(Operation.ADD_KEY));
        formatButton.addActionListener(e -> mutate(Operation.FORMAT));
        encryptDiskButton.addActionListener(e -> mutate(Operation.ENCRYPT_DISK));

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildCenter(), BorderLayout.CENTER);
        root.add(buildSouth(), BorderLayout.SOUTH);

        reload();
    }

    @Override
    public String displayName() {
        return "Storage";
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
        JPanel header = new JPanel(new BorderLayout(4, 4));
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD));
        noteLabel.setForeground(new Color(160, 90, 20));
        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        nameRow.add(new JLabel("LUKS/mapper name:"));
        nameRow.add(nameField);
        nameRow.setOpaque(false);
        JPanel head = new JPanel(new BorderLayout(2, 2));
        head.add(headerLabel, BorderLayout.NORTH);
        head.add(noteLabel, BorderLayout.SOUTH);
        head.setOpaque(false);
        header.add(head, BorderLayout.NORTH);
        header.add(nameRow, BorderLayout.SOUTH);
        header.setOpaque(false);
        return header;
    }

    private JComponent buildCenter() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(deviceList), new JScrollPane(details));
        split.setDividerLocation(300);
        split.setResizeWeight(0.45);
        split.setBorder(BorderFactory.createEmptyBorder());
        return split;
    }

    private JComponent buildSouth() {
        JPanel south = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel(new GridLayout(2, 1, 0, 4));
        JPanel readRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        readRow.add(refreshButton);
        readRow.add(statusButton);
        readRow.add(uuidButton);
        JPanel writeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        writeRow.add(openButton);
        writeRow.add(closeButton);
        writeRow.add(addKeyButton);
        writeRow.add(formatButton);
        writeRow.add(encryptDiskButton);
        buttons.add(readRow);
        buttons.add(writeRow);
        south.add(buttons, BorderLayout.NORTH);
        south.add(progress, BorderLayout.SOUTH);
        return south;
    }

    // ------------------------------------------------------------------

    /**
     * Re-lists the block devices (only when {@code lsblk} is present, so the
     * constructor stays cheap and CI/headless-safe) and updates the header, the
     * read-only/mutating note and the button availability.
     */
    private void reload() {
        if (!StorageService.isAvailable()) {
            headerLabel.setText("Storage: unavailable");
            noteLabel.setText("lsblk was not found on this host; block-device listing "
                    + "and LUKS management are unavailable.");
            devices.clear();
            details.setText("");
            setRunning(false);
            return;
        }

        String selectedPath = null;
        BlockDevice sel = deviceList.getSelectedValue();
        if (sel != null) {
            selectedPath = sel.getPath();
        }

        devices.clear();
        List<BlockDevice> found = StorageService.listDevices();
        for (BlockDevice d : found) {
            devices.addElement(d);
        }
        headerLabel.setText("Block devices: " + found.size());

        if (!LuksService.isAvailable()) {
            noteLabel.setText("cryptsetup was not found; devices are listed read-only "
                    + "and LUKS operations are unavailable.");
        } else if (!PrivilegedRunner.isAvailable()) {
            noteLabel.setText("Privilege escalation (pkexec) is unavailable; LUKS "
                    + "operations are read-only.");
        } else {
            noteLabel.setText(" ");
        }

        // Restore the previous selection by device path, else select the first row.
        if (selectedPath != null) {
            for (int i = 0; i < devices.size(); i++) {
                if (devices.get(i).getPath().equals(selectedPath)) {
                    deviceList.setSelectedIndex(i);
                    updateButtons();
                    return;
                }
            }
        }
        if (!devices.isEmpty()) {
            deviceList.setSelectedIndex(0);
        } else {
            details.setText("");
        }
        updateButtons();
    }

    /** Enables/disables the action buttons for the current backend + selection. */
    private void updateButtons() {
        BlockDevice sel = deviceList.getSelectedValue();
        boolean lsblk = StorageService.isAvailable();
        boolean luks = LuksService.isAvailable();
        boolean writable = PrivilegedRunner.isAvailable();
        boolean helper = LuksService.isEncryptDiskHelperAvailable();
        boolean hasName = !trimmedName().isEmpty();
        boolean idle = !running;

        refreshButton.setEnabled(idle && lsblk);
        statusButton.setEnabled(idle && luks && hasName);
        uuidButton.setEnabled(idle && lsblk && sel != null);
        openButton.setEnabled(idle && luks && writable && sel != null && hasName);
        closeButton.setEnabled(idle && luks && writable && hasName);
        addKeyButton.setEnabled(idle && luks && writable && sel != null);
        formatButton.setEnabled(idle && luks && writable && sel != null);
        encryptDiskButton.setEnabled(idle && luks && writable && helper && sel != null);
    }

    /** Shows/hides the progress bar and refreshes every control's enabled state. */
    private void setRunning(boolean value) {
        running = value;
        progress.setVisible(value);
        updateButtons();
    }

    private String trimmedName() {
        String t = nameField.getText();
        return (t == null) ? "" : t.trim();
    }

    // ------------------------------------------------------------------
    // Read-only actions (off the EDT).

    private void showStatus() {
        String name = trimmedName();
        if (running || name.isEmpty() || !LuksService.isAvailable()) {
            return;
        }
        setRunning(true);
        details.setText("Querying 'cryptsetup status " + name + "'...");
        new SwingWorker<ProcessRunner.Result, Void>() {
            @Override
            protected ProcessRunner.Result doInBackground() {
                return LuksService.runStatus(name);
            }

            @Override
            protected void done() {
                try {
                    showReadResult("cryptsetup status " + name, get());
                } catch (Exception ex) {
                    fail(ex);
                } finally {
                    setRunning(false);
                }
            }
        }.execute();
    }

    private void showUuid() {
        BlockDevice sel = deviceList.getSelectedValue();
        if (running || sel == null || !StorageService.isAvailable()) {
            return;
        }
        String dev = sel.getPath();
        setRunning(true);
        details.setText("Reading the UUID of " + dev + " (blkid)...");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return StorageService.readUuid(dev);
            }

            @Override
            protected void done() {
                try {
                    String uuid = get();
                    details.setText(dev + "\nUUID: "
                            + (uuid.isEmpty() ? "(none reported)" : uuid));
                    details.setCaretPosition(0);
                    noteLabel.setText(uuid.isEmpty()
                            ? "No UUID was reported for " + dev + "."
                            : "UUID read for " + dev + ".");
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
    // Mutating / destructive actions (confirmed, escalated, off the EDT).

    private void mutate(Operation op) {
        if (running || !LuksService.isAvailable() || !PrivilegedRunner.isAvailable()) {
            return;
        }
        BlockDevice sel = deviceList.getSelectedValue();
        String device = (sel == null) ? null : sel.getPath();
        String name = trimmedName();

        // Per-operation argument validation, mirroring LuksService.buildCommand.
        if (LuksService.buildCommand(op, device, name).isEmpty()) {
            noteLabel.setText(missingArgMessage(op));
            return;
        }

        // Confirmation gate: typed for destructive, plain OK/Cancel otherwise.
        if (op.isDestructive()) {
            if (sel == null || !confirmDestructive(sel, op)) {
                noteLabel.setText("Cancelled; the typed confirmation did not match the device.");
                return;
            }
        } else if (!confirmMutating(op, device, name)) {
            return;
        }

        // Passphrase (fed through stdin only, never as an argument, §6).
        String passphrase = null;
        if (needsPassphrase(op)) {
            char[] pw = askPassphrase(passphrasePrompt(op, device));
            if (pw == null) {
                noteLabel.setText("Cancelled; no passphrase was entered.");
                return;
            }
            passphrase = new String(pw);
        }

        final String fDevice = device;
        final String fName = name.isEmpty() ? null : name;
        final String fPass = passphrase;
        setRunning(true);
        details.setText("Running '" + describeVector(op, fDevice, fName) + "'...\n\n"
                + "Approve the administrative-privilege prompt; this can take a while.");
        new SwingWorker<PrivilegedRunner.PrivilegedResult, Void>() {
            @Override
            protected PrivilegedRunner.PrivilegedResult doInBackground() {
                return LuksService.runMutating(op, fDevice, fName, fPass);
            }

            @Override
            protected void done() {
                try {
                    report(get(), op, fDevice);
                } catch (Exception ex) {
                    fail(ex);
                } finally {
                    setRunning(false);
                    reload();
                }
            }
        }.execute();
    }

    private void report(PrivilegedRunner.PrivilegedResult r, Operation op, String device) {
        if (r.isSuccess()) {
            noteLabel.setText(label(op) + " succeeded"
                    + (device != null ? " on " + device : "") + ".");
            details.setText(r.getOutput().isEmpty()
                    ? "(" + label(op) + " completed with no output)"
                    : r.getOutput());
        } else if (r.getStatus() == PrivilegedRunner.Status.CANCELLED) {
            noteLabel.setText("The privilege prompt was cancelled; no change was made.");
        } else if (r.getStatus() == PrivilegedRunner.Status.UNAVAILABLE) {
            noteLabel.setText("Privilege escalation is unavailable; LUKS operations are read-only.");
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
    // Confirmation / prompt helpers.

    /**
     * The typed confirmation required for a destructive op (§4.5): the user must
     * retype the exact device path before the polkit prompt is shown.
     */
    private boolean confirmDestructive(BlockDevice dev, Operation op) {
        String path = dev.getPath();
        String input = JOptionPane.showInputDialog(root,
                label(op) + " " + path + "?\n\n"
                        + "THIS ERASES ALL DATA ON THE DEVICE and cannot be undone.\n"
                        + "Type the device path exactly to confirm; you will then be\n"
                        + "asked for administrative privileges:",
                "Destructive LUKS operation", JOptionPane.WARNING_MESSAGE);
        return path.equals(input == null ? null : input.trim());
    }

    private boolean confirmMutating(Operation op, String device, String name) {
        String msg;
        switch (op) {
            case OPEN:
                msg = "Open LUKS device " + device + " as '" + name + "'?\n\n"
                        + "You will be asked for the passphrase and for administrative privileges.";
                break;
            case CLOSE:
                msg = "Close the LUKS mapping '" + name + "'?\n\n"
                        + "Any filesystem on it becomes inaccessible until it is opened again.";
                break;
            case ADD_KEY:
                msg = "Add a new keyslot to " + device + "?\n\n"
                        + "cryptsetup will ask for the existing passphrase, then the new one.";
                break;
            default:
                return false;
        }
        return JOptionPane.showConfirmDialog(root, msg, "LUKS " + label(op),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private char[] askPassphrase(String prompt) {
        JPasswordField pf = new JPasswordField();
        Object[] body = {prompt, pf};
        int opt = JOptionPane.showConfirmDialog(root, body, "LUKS Passphrase",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        return (opt == JOptionPane.OK_OPTION) ? pf.getPassword() : null;
    }

    private boolean needsPassphrase(Operation op) {
        return op == Operation.OPEN || op == Operation.ADD_KEY
                || op == Operation.FORMAT || op == Operation.ENCRYPT_DISK;
    }

    private String passphrasePrompt(Operation op, String device) {
        switch (op) {
            case OPEN:
                return "Enter the passphrase to open " + device + ":";
            case ADD_KEY:
                return "Enter the existing passphrase for " + device
                        + " (cryptsetup then asks for the new key):";
            case FORMAT:
                return "Enter the new LUKS2 passphrase for " + device + ":";
            case ENCRYPT_DISK:
                return "Enter the passphrase for full-disk encryption of " + device + ":";
            default:
                return "Enter the LUKS passphrase:";
        }
    }

    private String missingArgMessage(Operation op) {
        switch (op) {
            case OPEN:
                return "Open needs a selected device and a LUKS/mapper name.";
            case CLOSE:
                return "Close needs a LUKS/mapper name.";
            case ADD_KEY:
                return "Add key needs a selected device.";
            case FORMAT:
                return "Format needs a selected device.";
            case ENCRYPT_DISK:
                return "Encrypt disk needs a selected device.";
            default:
                return "Select a device (and enter a name where required).";
        }
    }

    private String label(Operation op) {
        switch (op) {
            case STATUS:
                return "LUKS status";
            case OPEN:
                return "Open";
            case CLOSE:
                return "Close";
            case ADD_KEY:
                return "Add key";
            case FORMAT:
                return "Format";
            case ENCRYPT_DISK:
                return "Encrypt disk";
            default:
                return op.name();
        }
    }

    /** The exact argument vector about to run, for transparent display. */
    private String describeVector(Operation op, String device, String name) {
        return String.join(" ", LuksService.buildCommand(op, device, name));
    }
}
