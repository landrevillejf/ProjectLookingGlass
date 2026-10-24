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
import org.jdesktop.lg3d.utils.system.PrivilegedRunner;
import org.jdesktop.lg3d.utils.system.ProcessRunner;
import org.jdesktop.lg3d.utils.system.SecurityService;
import org.jdesktop.lg3d.utils.system.SecurityService.PolicyOperation;
import org.jdesktop.lg3d.utils.system.SecurityService.PolicyTool;

/**
 * The Security Center's <em>SELinux policy</em> section (inventory
 * {@code lg3d-agent-package-inventory.md} §6.11, gap G2): a thin front-end over
 * the policy half of the shared {@link SecurityService}. The Security Overview
 * tab already reports the SELinux <em>state</em> ({@code getenforce}); this tab
 * adds <em>management</em> - {@code semanage} (file contexts, booleans, ports),
 * {@code restorecon}, {@code semodule}, {@code load_policy} and the
 * {@code ausearch -> audit2allow} review pipe.
 *
 * <p>The desktop is a front-end only: it builds the exact argument vectors (never
 * a shell string) and drives the owning CLI. Reads ({@code semanage ... -l},
 * {@code semodule -l}, {@code ausearch -m avc}, the audit2allow draft) run
 * unprivileged through {@link ProcessRunner}; every policy write is destructive,
 * so it is guarded by a <em>typed</em> confirmation and then escalated through
 * {@link PrivilegedRunner} (polkit). Nothing here writes {@code /etc}: a policy
 * change goes through {@code semanage} / {@code semodule} / {@code load_policy}
 * under polkit (§6). The audit2allow output is a <em>draft for review</em> and is
 * never installed automatically - applying it is an explicit
 * {@code semodule -i} the admin confirms separately.</p>
 *
 * <p>Every operation runs off the EDT behind an indeterminate progress bar with
 * the controls disabled while in flight (serialization, §3.3); the Cancel control
 * interrupts the background worker, which destroys the child process. A non-zero
 * exit, an absent tool or a cancelled prompt is shown verbatim as state, never
 * treated as a crash (§3.2), and a tool that is not installed lists its area with
 * the actions disabled rather than raising an error.</p>
 *
 * <p>Like the rest of the Security Center this is plain Swing using only labels,
 * buttons, a list and text fields/area (never a {@code JComboBox}), so it renders
 * correctly when hosted offscreen in a {@code SwingNode} on the 3D desktop and
 * identically in the 2D desktop. Construction is headless-safe: availability is
 * resolved with non-spawning PATH probes and no command runs until the user acts;
 * the vectors, validation and parsers behind it are covered by
 * {@code SecurityServiceTest} and {@code selinux-policy.feature} in lg3d-core.</p>
 */
public class SelinuxPolicyPanel extends JPanel {

    /** The policy areas the tab lists; each maps to the tool that drives it. */
    enum Area {
        /** {@code semanage fcontext} file-context rules. */
        FILE_CONTEXT("File contexts (semanage fcontext)", PolicyTool.SEMANAGE),
        /** {@code semanage boolean} policy booleans. */
        BOOLEAN("Booleans (semanage boolean)", PolicyTool.SEMANAGE),
        /** {@code semanage port} port contexts. */
        PORT("Ports (semanage port)", PolicyTool.SEMANAGE),
        /** {@code restorecon} file relabelling. */
        RELABEL("Relabel files (restorecon)", PolicyTool.RESTORECON),
        /** {@code semodule} policy modules. */
        MODULE("Policy modules (semodule)", PolicyTool.SEMODULE),
        /** {@code load_policy} kernel policy reload. */
        RELOAD("Reload policy (load_policy)", PolicyTool.LOAD_POLICY),
        /** {@code ausearch -> audit2allow} denial review and module draft. */
        AVC("AVC denials (ausearch to audit2allow)", PolicyTool.AUSEARCH);

        private final String label;
        private final PolicyTool tool;

        Area(String label, PolicyTool tool) {
            this.label = label;
            this.tool = tool;
        }

        String label() {
            return label;
        }

        PolicyTool tool() {
            return tool;
        }
    }

    private final JLabel titleLabel = new JLabel("SELinux policy management");
    private final JLabel noteLabel = new JLabel(" ");
    private final JTextArea viewer = new JTextArea();
    private final JProgressBar progress = new JProgressBar();

    private final DefaultListModel<Area> areaModel = new DefaultListModel<>();
    private final JList<Area> areaList = new JList<>(areaModel);
    private final JLabel rendererLabel = new JLabel();

    private final JLabel arg1Label = new JLabel("Parameter:");
    private final JTextField arg1Field = new JTextField(16);
    private final JLabel arg2Label = new JLabel("Value:");
    private final JTextField arg2Field = new JTextField(16);
    private final JLabel arg3Label = new JLabel("Port:");
    private final JTextField arg3Field = new JTextField(6);

    // file-context actions
    private final JButton fcListBtn = new JButton("List");
    private final JButton fcAddBtn = new JButton("Add rule");
    private final JButton fcDelBtn = new JButton("Delete rule");
    // boolean actions
    private final JButton boolListBtn = new JButton("List");
    private final JButton boolSetBtn = new JButton("Set boolean");
    // port actions
    private final JButton portListBtn = new JButton("List");
    private final JButton portAddBtn = new JButton("Add port");
    private final JButton portDelBtn = new JButton("Delete port");
    // relabel action
    private final JButton relabelBtn = new JButton("Relabel (restorecon -Rv)");
    // module actions
    private final JButton modListBtn = new JButton("List");
    private final JButton modInstallBtn = new JButton("Install module");
    private final JButton modRemoveBtn = new JButton("Remove module");
    // reload action
    private final JButton reloadBtn = new JButton("Reload policy");
    // avc actions
    private final JButton avcReadBtn = new JButton("Read recent denials");
    private final JButton avcDraftBtn = new JButton("Draft module (review)");

    private final JButton cancelBtn = new JButton("Cancel");
    private final JPanel actionsCard = new JPanel(new CardLayout());

    /** True while a background operation is in flight (serialization guard). */
    private boolean busy;
    /** The in-flight worker, so Cancel can interrupt (and thus destroy) it. */
    private SwingWorker<?, ?> worker;
    /** The AVC denials from the last successful {@code ausearch}, for the draft. */
    private String lastDenials;

    // Availability, resolved once at construction without spawning a process.
    private final SecurityService.PolicyProbes probes;
    private final boolean polkitAvailable;

    /** Builds the SELinux policy section over a live (non-spawning) probe. */
    public SelinuxPolicyPanel() {
        super(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        this.probes = SecurityService.probePolicy();
        this.polkitAvailable = PrivilegedRunner.isAvailable();

        add(buildNorth(), BorderLayout.NORTH);
        add(buildAreaList(), BorderLayout.WEST);
        add(buildViewer(), BorderLayout.CENTER);
        add(buildSouth(), BorderLayout.SOUTH);

        for (Area a : Area.values()) {
            areaModel.addElement(a);
        }
        wireListeners();
        if (areaModel.size() > 0) {
            areaList.setSelectedIndex(0);
        }
        if (!anyToolPresent()) {
            noteLabel.setText("No SELinux policy tools (semanage, restorecon, semodule, "
                    + "load_policy, ausearch, audit2allow) are installed on this host; "
                    + "install policycoreutils to use this section.");
        } else if (!polkitAvailable) {
            noteLabel.setText("Privilege escalation (pkexec) is unavailable; read-only queries "
                    + "work, but policy writes are disabled.");
        }
        onAreaSelected();
        updateButtons();
    }

    private boolean anyToolPresent() {
        for (PolicyTool tool : PolicyTool.values()) {
            if (SecurityService.isPolicyToolPresent(probes, tool)) {
                return true;
            }
        }
        return false;
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

    private Component buildAreaList() {
        areaList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        areaList.setCellRenderer((list, value, index, selected, focus) -> {
            rendererLabel.setText(labelFor(value));
            rendererLabel.setOpaque(true);
            rendererLabel.setBackground(selected
                    ? list.getSelectionBackground() : list.getBackground());
            rendererLabel.setForeground(selected
                    ? list.getSelectionForeground() : list.getForeground());
            rendererLabel.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            return rendererLabel;
        });
        JScrollPane scroll = new JScrollPane(areaList);
        scroll.setBorder(BorderFactory.createTitledBorder("Policy area"));
        scroll.setPreferredSize(new Dimension(280, 100));
        return scroll;
    }

    private String labelFor(Area area) {
        String base = area.label();
        return SecurityService.isPolicyToolPresent(probes, area.tool())
                ? base : (base + " - not installed");
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
        paramRow.add(arg1Label);
        paramRow.add(arg1Field);
        paramRow.add(arg2Label);
        paramRow.add(arg2Field);
        paramRow.add(arg3Label);
        paramRow.add(arg3Field);
        south.add(paramRow);

        actionsCard.add(fileContextCard(), Area.FILE_CONTEXT.name());
        actionsCard.add(booleanCard(), Area.BOOLEAN.name());
        actionsCard.add(portCard(), Area.PORT.name());
        actionsCard.add(relabelCard(), Area.RELABEL.name());
        actionsCard.add(moduleCard(), Area.MODULE.name());
        actionsCard.add(reloadCard(), Area.RELOAD.name());
        actionsCard.add(avcCard(), Area.AVC.name());
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

    private Component fileContextCard() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setBorder(BorderFactory.createTitledBorder("semanage fcontext"));
        p.add(fcListBtn);
        p.add(fcAddBtn);
        p.add(fcDelBtn);
        return p;
    }

    private Component booleanCard() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setBorder(BorderFactory.createTitledBorder("semanage boolean"));
        p.add(boolListBtn);
        p.add(boolSetBtn);
        return p;
    }

    private Component portCard() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setBorder(BorderFactory.createTitledBorder("semanage port"));
        p.add(portListBtn);
        p.add(portAddBtn);
        p.add(portDelBtn);
        return p;
    }

    private Component relabelCard() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setBorder(BorderFactory.createTitledBorder("restorecon"));
        p.add(relabelBtn);
        return p;
    }

    private Component moduleCard() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setBorder(BorderFactory.createTitledBorder("semodule"));
        p.add(modListBtn);
        p.add(modInstallBtn);
        p.add(modRemoveBtn);
        return p;
    }

    private Component reloadCard() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setBorder(BorderFactory.createTitledBorder("load_policy"));
        p.add(reloadBtn);
        return p;
    }

    private Component avcCard() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setBorder(BorderFactory.createTitledBorder("ausearch to audit2allow (review only)"));
        p.add(avcReadBtn);
        p.add(avcDraftBtn);
        return p;
    }

    // ------------------------------------------------------------------
    // Wiring / serialization

    private void wireListeners() {
        areaList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onAreaSelected();
            }
        });
        fcListBtn.addActionListener(e -> runRead("semanage fcontext -l",
                PolicyOperation.SEMANAGE_FCONTEXT_LIST));
        fcAddBtn.addActionListener(e -> addFileContext());
        fcDelBtn.addActionListener(e -> delFileContext());
        boolListBtn.addActionListener(e -> runRead("semanage boolean -l",
                PolicyOperation.SEMANAGE_BOOLEAN_LIST));
        boolSetBtn.addActionListener(e -> setBoolean());
        portListBtn.addActionListener(e -> runRead("semanage port -l",
                PolicyOperation.SEMANAGE_PORT_LIST));
        portAddBtn.addActionListener(e -> port(true));
        portDelBtn.addActionListener(e -> port(false));
        relabelBtn.addActionListener(e -> relabel());
        modListBtn.addActionListener(e -> runRead("semodule -l", PolicyOperation.SEMODULE_LIST));
        modInstallBtn.addActionListener(e -> installModule());
        modRemoveBtn.addActionListener(e -> removeModule());
        reloadBtn.addActionListener(e -> runMutating("load_policy", PolicyOperation.LOAD_POLICY));
        avcReadBtn.addActionListener(e -> readDenials());
        avcDraftBtn.addActionListener(e -> generateDraft());
        cancelBtn.addActionListener(e -> cancel());
    }

    /** Shows the selected area's action card and adapts the parameter fields. */
    private void onAreaSelected() {
        Area sel = selectedArea();
        ((CardLayout) actionsCard.getLayout())
                .show(actionsCard, sel == null ? Area.FILE_CONTEXT.name() : sel.name());
        boolean show2 = false;
        boolean show3 = false;
        if (sel == Area.FILE_CONTEXT) {
            arg1Label.setText("Type:");
            arg2Label.setText("Path regex:");
            show2 = true;
        } else if (sel == Area.BOOLEAN) {
            arg1Label.setText("Boolean name:");
            arg2Label.setText("Value (on/off):");
            show2 = true;
        } else if (sel == Area.PORT) {
            arg1Label.setText("Type:");
            arg2Label.setText("Protocol:");
            arg3Label.setText("Port:");
            show2 = true;
            show3 = true;
        } else if (sel == Area.RELABEL) {
            arg1Label.setText("Path:");
        } else if (sel == Area.MODULE) {
            arg1Label.setText("Module path / name:");
        }
        boolean show1 = sel == Area.FILE_CONTEXT || sel == Area.BOOLEAN || sel == Area.PORT
                || sel == Area.RELABEL || sel == Area.MODULE;
        arg1Label.setVisible(show1);
        arg1Field.setVisible(show1);
        arg2Label.setVisible(show2);
        arg2Field.setVisible(show2);
        arg3Label.setVisible(show3);
        arg3Field.setVisible(show3);
        updateButtons();
    }

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisible(value);
        updateButtons();
    }

    /** Gates each control on the selection, its tool's availability and busy. */
    private void updateButtons() {
        boolean idle = !busy;
        Area sel = selectedArea();
        setEnabled(fcListBtn, idle, sel, Area.FILE_CONTEXT, PolicyTool.SEMANAGE, false);
        setEnabled(fcAddBtn, idle, sel, Area.FILE_CONTEXT, PolicyTool.SEMANAGE, true);
        setEnabled(fcDelBtn, idle, sel, Area.FILE_CONTEXT, PolicyTool.SEMANAGE, true);
        setEnabled(boolListBtn, idle, sel, Area.BOOLEAN, PolicyTool.SEMANAGE, false);
        setEnabled(boolSetBtn, idle, sel, Area.BOOLEAN, PolicyTool.SEMANAGE, true);
        setEnabled(portListBtn, idle, sel, Area.PORT, PolicyTool.SEMANAGE, false);
        setEnabled(portAddBtn, idle, sel, Area.PORT, PolicyTool.SEMANAGE, true);
        setEnabled(portDelBtn, idle, sel, Area.PORT, PolicyTool.SEMANAGE, true);
        setEnabled(relabelBtn, idle, sel, Area.RELABEL, PolicyTool.RESTORECON, true);
        setEnabled(modListBtn, idle, sel, Area.MODULE, PolicyTool.SEMODULE, false);
        setEnabled(modInstallBtn, idle, sel, Area.MODULE, PolicyTool.SEMODULE, true);
        setEnabled(modRemoveBtn, idle, sel, Area.MODULE, PolicyTool.SEMODULE, true);
        setEnabled(reloadBtn, idle, sel, Area.RELOAD, PolicyTool.LOAD_POLICY, true);
        setEnabled(avcReadBtn, idle, sel, Area.AVC, PolicyTool.AUSEARCH, false);
        setEnabled(avcDraftBtn, idle, sel, Area.AVC, PolicyTool.AUDIT2ALLOW, false);
        cancelBtn.setEnabled(busy);
    }

    private void setEnabled(JButton b, boolean idle, Area sel, Area owner,
                            PolicyTool tool, boolean mutating) {
        boolean available = SecurityService.isPolicyToolPresent(probes, tool)
                && (!mutating || polkitAvailable);
        b.setEnabled(idle && sel == owner && available);
    }

    private void cancel() {
        SwingWorker<?, ?> w = worker;
        if (w != null) {
            w.cancel(true);
        }
    }

    // ------------------------------------------------------------------
    // Parameter gathering for the destructive writes.

    private void addFileContext() {
        String type = arg1Field.getText().trim();
        String regex = arg2Field.getText().trim();
        if (type.isEmpty() || regex.isEmpty()) {
            noteLabel.setText("Enter both a context type and a path regex first.");
            return;
        }
        runMutating("semanage fcontext -a -t " + type + " '" + regex + "'",
                PolicyOperation.SEMANAGE_FCONTEXT_ADD, type, regex);
    }

    private void delFileContext() {
        String regex = arg2Field.getText().trim();
        if (regex.isEmpty()) {
            noteLabel.setText("Enter the path regex to delete first.");
            return;
        }
        runMutating("semanage fcontext -d '" + regex + "'",
                PolicyOperation.SEMANAGE_FCONTEXT_DEL, regex);
    }

    private void setBoolean() {
        String name = arg1Field.getText().trim();
        String value = arg2Field.getText().trim();
        if (name.isEmpty() || value.isEmpty()) {
            noteLabel.setText("Enter both a boolean name and a value (on or off) first.");
            return;
        }
        runMutating("semanage boolean -m " + value + " " + name,
                PolicyOperation.SEMANAGE_BOOLEAN_SET, name, value);
    }

    private void port(boolean add) {
        String type = arg1Field.getText().trim();
        String proto = arg2Field.getText().trim();
        String port = arg3Field.getText().trim();
        if (type.isEmpty() || proto.isEmpty() || port.isEmpty()) {
            noteLabel.setText("Enter a context type, a protocol (tcp/udp) and a port first.");
            return;
        }
        PolicyOperation op = add ? PolicyOperation.SEMANAGE_PORT_ADD : PolicyOperation.SEMANAGE_PORT_DEL;
        runMutating("semanage port " + (add ? "-a" : "-d") + " -t " + type
                + " -p " + proto + " " + port, op, type, proto, port);
    }

    private void relabel() {
        String path = arg1Field.getText().trim();
        if (path.isEmpty()) {
            noteLabel.setText("Enter the path to relabel first.");
            return;
        }
        runMutating("restorecon -Rv " + path, PolicyOperation.RESTORECON, path);
    }

    private void installModule() {
        String path = arg1Field.getText().trim();
        if (path.isEmpty()) {
            noteLabel.setText("Enter the module path (.pp) to install first.");
            return;
        }
        runMutating("semodule -i " + path, PolicyOperation.SEMODULE_INSTALL, path);
    }

    private void removeModule() {
        String name = arg1Field.getText().trim();
        if (name.isEmpty()) {
            noteLabel.setText("Enter the module name to remove first.");
            return;
        }
        runMutating("semodule -r " + name, PolicyOperation.SEMODULE_REMOVE, name);
    }

    // ------------------------------------------------------------------
    // Read-only actions (off the EDT, unprivileged).

    private void readDenials() {
        runRead("ausearch -m avc -ts recent", PolicyOperation.AUSEARCH_AVC);
    }

    private void runRead(String label, PolicyOperation op, String... params) {
        if (busy) {
            return;
        }
        setBusy(true);
        noteLabel.setText(" ");
        viewer.setText("Running '" + label + "'...");
        SwingWorker<ProcessRunner.Result, Void> w = new SwingWorker<>() {
            @Override
            protected ProcessRunner.Result doInBackground() {
                return SecurityService.runPolicyRead(op, params);
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

    private void renderRead(String label, PolicyOperation op, ProcessRunner.Result r) {
        if (!r.isStarted()) {
            noteLabel.setText(label + ": unavailable");
            viewer.setText("The command could not be run: " + r.getStderr());
            return;
        }
        String out = combine(r.getStdout(), r.getStderr());
        viewer.setText(out.isEmpty() ? "(no output)" : out);
        viewer.setCaretPosition(0);
        if (op == PolicyOperation.AUSEARCH_AVC) {
            lastDenials = r.getStdout();
            noteLabel.setText(label + ": " + SecurityService.countAvcDenials(out)
                    + " AVC denial(s); use 'Draft module' to review a fix.");
        } else {
            noteLabel.setText(label + ": " + ((r.getExitCode() == 0) ? "ok" : ("exit " + r.getExitCode())));
        }
    }

    /**
     * Generates an audit2allow module <em>draft</em> from the denials captured by
     * the last {@code ausearch}. Read-only: audit2allow only prints a draft, it
     * never installs one - applying it is an explicit {@code semodule -i}.
     */
    private void generateDraft() {
        if (busy) {
            return;
        }
        if (lastDenials == null || lastDenials.isBlank()) {
            noteLabel.setText("Read the recent AVC denials first; the draft is generated from them.");
            return;
        }
        setBusy(true);
        noteLabel.setText(" ");
        viewer.setText("Generating a module draft from the last AVC denials (review only)...");
        SwingWorker<ProcessRunner.Result, Void> w = new SwingWorker<>() {
            @Override
            protected ProcessRunner.Result doInBackground() {
                return SecurityService.runAudit2Allow(lastDenials);
            }

            @Override
            protected void done() {
                try {
                    if (isCancelled()) {
                        noteLabel.setText("Cancelled.");
                        return;
                    }
                    renderDraft(get());
                } catch (Exception ex) {
                    noteLabel.setText("Could not generate the draft: " + ex.getMessage());
                } finally {
                    setBusy(false);
                }
            }
        };
        worker = w;
        w.execute();
    }

    private void renderDraft(ProcessRunner.Result r) {
        if (!r.isStarted()) {
            noteLabel.setText("audit2allow: unavailable");
            viewer.setText("The command could not be run: " + r.getStderr());
            return;
        }
        String out = combine(r.getStdout(), r.getStderr());
        viewer.setText(out.isEmpty() ? "(audit2allow produced no draft)" : out);
        viewer.setCaretPosition(0);
        noteLabel.setText("Module draft (review only) - "
                + SecurityService.summarizeAudit2Allow(r.getStdout()).describe()
                + ". Install it from the Policy modules area if you accept it.");
    }

    // ------------------------------------------------------------------
    // Destructive writes (typed confirmation, escalated via polkit, off the EDT).

    private void runMutating(String label, PolicyOperation op, String... params) {
        if (busy) {
            return;
        }
        if (!polkitAvailable) {
            noteLabel.setText("Privilege escalation (pkexec) is unavailable; cannot " + label + ".");
            return;
        }
        if (!confirmDestructive(label)) {
            noteLabel.setText("Cancelled; no policy change was made.");
            return;
        }
        setBusy(true);
        noteLabel.setText(" ");
        viewer.setText("Running '" + label + "'...\n\n"
                + "Approve the administrative-privilege prompt.");
        SwingWorker<PrivilegedRunner.PrivilegedResult, Void> w = new SwingWorker<>() {
            @Override
            protected PrivilegedRunner.PrivilegedResult doInBackground() {
                return SecurityService.runPolicyMutating(op, params);
            }

            @Override
            protected void done() {
                try {
                    if (isCancelled()) {
                        noteLabel.setText("Cancelled; the operation was interrupted.");
                        viewer.setText("Cancelled.");
                        return;
                    }
                    renderMutate(label, get());
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

    private void renderMutate(String label, PrivilegedRunner.PrivilegedResult r) {
        if (r.isSuccess()) {
            String out = r.getOutput();
            noteLabel.setText(label + " succeeded.");
            viewer.setText(out.isEmpty() ? "(completed with no output)" : out);
        } else if (r.getStatus() == PrivilegedRunner.Status.CANCELLED) {
            noteLabel.setText("The privilege prompt was cancelled; no policy change was made.");
        } else if (r.getStatus() == PrivilegedRunner.Status.UNAVAILABLE) {
            noteLabel.setText("Privilege escalation is unavailable; cannot " + label + ".");
        } else {
            noteLabel.setText(label + " failed: " + r.getMessage());
            viewer.setText(combine(r.getMessage(), r.getOutput()));
        }
        viewer.setCaretPosition(0);
    }

    /**
     * A destructive policy write is confirmed by typing, not by a single click:
     * the admin must type {@code yes} before the polkit prompt is even raised.
     */
    private boolean confirmDestructive(String label) {
        String msg = "'" + label + "' changes the SELinux policy.\n\n"
                + "This is destructive and runs the owning tool with administrative\n"
                + "privileges. Type 'yes' to continue, then approve the privilege prompt.";
        String input = JOptionPane.showInputDialog(this, msg, "SELinux policy: " + label,
                JOptionPane.WARNING_MESSAGE);
        return "yes".equalsIgnoreCase(input == null ? "" : input.trim());
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

    /** The currently selected area, or null when nothing is selected. */
    Area selectedArea() {
        return areaList.getSelectedValue();
    }

    /** Selects an area in the list (drives the card and the button gating). */
    void selectArea(Area area) {
        areaList.setSelectedValue(area, false);
    }

    /** The guidance note text. */
    String noteText() {
        return noteLabel.getText();
    }

    /** The detail viewer text. */
    String viewerText() {
        return viewer.getText();
    }

    /** Whether a policy tool's binary was detected at construction. */
    boolean isToolPresent(PolicyTool tool) {
        return SecurityService.isPolicyToolPresent(probes, tool);
    }

    /** Whether privilege escalation (pkexec) was available at construction. */
    boolean polkitAvailable() {
        return polkitAvailable;
    }

    /** Whether the file-context "List" (read) control is enabled. */
    boolean fcListEnabled() {
        return fcListBtn.isEnabled();
    }

    /** Whether the file-context "Add rule" (destructive) control is enabled. */
    boolean fcAddEnabled() {
        return fcAddBtn.isEnabled();
    }

    /** Whether the boolean "Set boolean" (destructive) control is enabled. */
    boolean boolSetEnabled() {
        return boolSetBtn.isEnabled();
    }

    /** Whether the "Relabel" (destructive) control is enabled. */
    boolean relabelEnabled() {
        return relabelBtn.isEnabled();
    }

    /** Whether the module "Install module" (destructive) control is enabled. */
    boolean modInstallEnabled() {
        return modInstallBtn.isEnabled();
    }

    /** Whether the module "List" (read) control is enabled. */
    boolean modListEnabled() {
        return modListBtn.isEnabled();
    }

    /** Whether the "Reload policy" (destructive) control is enabled. */
    boolean reloadEnabled() {
        return reloadBtn.isEnabled();
    }

    /** Whether the AVC "Read recent denials" (read) control is enabled. */
    boolean avcReadEnabled() {
        return avcReadBtn.isEnabled();
    }

    /** Whether the AVC "Draft module" (read) control is enabled. */
    boolean avcDraftEnabled() {
        return avcDraftBtn.isEnabled();
    }

    /** Whether the Cancel control is enabled (only while busy). */
    boolean cancelEnabled() {
        return cancelBtn.isEnabled();
    }

    /** The number of areas listed (all seven, present or not). */
    int areaCount() {
        return areaModel.size();
    }
}
