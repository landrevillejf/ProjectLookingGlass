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
package org.lpmconsole;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;

/**
 * The LPM Console's graphical user interface: a package-manager window, not a
 * terminal. A category rail (Installed / All / Upgradable / Held / History)
 * drives a sortable, searchable package table with a details pane, an action
 * toolbar, a collapsible log of {@code lpm} output and a status bar.
 *
 * <p>It is a plain {@link JPanel} so the desktop can host it two ways: as a
 * {@code JInternalFrame} in the 2D/Swing desktop (via {@code
 * Desktop2DAppRegistry.PANEL_APPS}) and, in the 3D desktop, wrapped in the
 * {@link LPMConsole} {@code JFrame} that {@code SwingNodeWindowCapture}
 * textures into the scene.</p>
 *
 * <p>Contract compliance: package listings are read read-only from LPM's
 * database (§5.5); every mutation shells out to {@code /usr/bin/lpm} through
 * {@link LPMExecutor} with {@code --no-color} (§5.1), always previews with
 * {@code --dry-run} (§4.1), confirms destructive actions (§4.2), runs off the
 * EDT and streams output live (§5.6), and surfaces LPM's verbatim errors
 * (§5.2). Modal dialogs are deliberately avoided: they escape the SwingNode
 * offscreen capture, so confirmations use an in-panel overlay instead.</p>
 */
public class LPMConsolePanel extends JPanel {

    /** The browsing categories shown in the left rail. */
    private enum Category {
        INSTALLED("Installed"),
        AVAILABLE("All packages"),
        UPGRADABLE("Upgradable"),
        HELD("Held"),
        HISTORY("History");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final String[] PACKAGE_COLUMNS =
            {"Package", "Version", "Status", "Description"};
    private static final String[] HISTORY_COLUMNS =
            {"Date", "Action", "Package", "Version"};
    private static final int HISTORY_LIMIT = 200;

    private final LpmDatabase db = new LpmDatabase();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lpm-console-worker");
        t.setDaemon(true);
        return t;
    });

    private final List<JButton> actionButtons = new ArrayList<>();

    private final DefaultTableModel tableModel = new DefaultTableModel();
    private final JTable table = new JTable(tableModel);
    private final TableRowSorter<DefaultTableModel> sorter =
            new TableRowSorter<>(tableModel);
    private final DefaultListModel<Category> categoryListModel =
            new DefaultListModel<>();
    private final JList<Category> categoryList = new JList<>(categoryListModel);

    private final JTextArea detailArea = new JTextArea();
    private final JTextArea logArea = new JTextArea();
    private final JScrollPane logScroll = new JScrollPane(logArea);
    private final JTextField searchField = new JTextField(18);
    private final JLabel banner = new JLabel(" ");
    private final JLabel summaryLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel("Ready");
    private final JProgressBar progressBar = new JProgressBar();
    private final JToggleButton logToggle = new JToggleButton("Log", false);

    private final JLayeredPane layers = new JLayeredPane();
    private final JPanel content = new JPanel(new BorderLayout(6, 6));
    private final JPanel overlay = new DimmingPanel();
    private final JTextArea overlayMessage = new JTextArea();
    private Runnable pendingConfirm;

    private Category category = Category.INSTALLED;
    private List<LpmPackage> packages = new ArrayList<>();
    private boolean busy;
    private Runnable onClose;

    public LPMConsolePanel() {
        super(new BorderLayout());
        setBorder(new EmptyBorder(8, 8, 8, 8));
        setPreferredSize(new Dimension(880, 620));

        buildContent();
        buildOverlay();

        layers.add(content, JLayeredPane.DEFAULT_LAYER);
        layers.add(overlay, JLayeredPane.MODAL_LAYER);
        overlay.setVisible(false);
        layers.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                layoutLayers();
            }
        });
        add(layers, BorderLayout.CENTER);

        initAvailability();
        selectCategory(Category.INSTALLED);
        loadLpmVersion();
    }

    /** Wires the window decoration's Close button (called by the host). */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private void buildContent() {
        content.add(buildHeader(), BorderLayout.NORTH);
        content.add(buildCategoryRail(), BorderLayout.WEST);

        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(table), new JScrollPane(detailArea));
        center.setResizeWeight(0.72);
        center.setContinuousLayout(true);
        center.setBorder(BorderFactory.createEmptyBorder());
        content.add(center, BorderLayout.CENTER);

        content.add(buildSouth(), BorderLayout.SOUTH);

        table.setRowSorter(sorter);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelectedDetail();
            }
        });

        detailArea.setEditable(false);
        detailArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        detailArea.setLineWrap(true);
        detailArea.setWrapStyleWord(true);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        logScroll.setPreferredSize(new Dimension(100, 130));
        logScroll.setVisible(false);

        categoryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        categoryList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                Category c = categoryList.getSelectedValue();
                if (c != null && c != category) {
                    selectCategory(c);
                }
            }
        });

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });

        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        progressBar.setPreferredSize(new Dimension(140, 16));
    }

    private JComponent buildHeader() {
        JPanel north = new JPanel(new BorderLayout(6, 4));
        banner.setFont(banner.getFont().deriveFont(Font.BOLD));
        banner.setVisible(false);
        north.add(banner, BorderLayout.NORTH);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        toolbar.add(new JLabel("Search:"));
        toolbar.add(searchField);
        toolbar.add(smallButton("Refresh", e -> reload()));

        toolbar.add(actionButton("Install", e -> actOnSelected(LPMCommand.INSTALL)));
        toolbar.add(actionButton("Remove", e -> actOnSelected(LPMCommand.REMOVE)));
        toolbar.add(actionButton("Upgrade", e -> actOnSelected(LPMCommand.UPDATE)));
        toolbar.add(actionButton("Reinstall", e -> actOnSelected(LPMCommand.REINSTALL)));
        toolbar.add(actionButton("Hold", e -> actOnSelected(LPMCommand.HOLD)));
        toolbar.add(actionButton("Unhold", e -> actOnSelected(LPMCommand.UNHOLD)));
        toolbar.add(actionButton("Verify", e -> actOnSelected(LPMCommand.VERIFY)));
        toolbar.add(actionButton("Why", e -> whySelected()));

        toolbar.add(globalButton("Update DB", LPMCommand.UPDATE_DB));
        toolbar.add(globalButton("Upgrade All", LPMCommand.UPGRADE));
        toolbar.add(globalButton("Autoremove", LPMCommand.AUTOREMOVE));
        toolbar.add(globalButton("Clean", LPMCommand.CLEAN));

        JPanel rows = new JPanel(new BorderLayout(0, 2));
        rows.add(toolbar, BorderLayout.NORTH);
        rows.add(summaryLabel, BorderLayout.SOUTH);
        north.add(rows, BorderLayout.CENTER);
        return north;
    }

    private JComponent buildCategoryRail() {
        for (Category c : Category.values()) {
            categoryListModel.addElement(c);
        }
        JScrollPane scroll = new JScrollPane(categoryList);
        scroll.setPreferredSize(new Dimension(140, 0));
        scroll.setBorder(BorderFactory.createTitledBorder("Categories"));
        return scroll;
    }

    private JComponent buildSouth() {
        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.add(logScroll, BorderLayout.CENTER);

        JPanel status = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        status.add(progressBar);
        status.add(statusLabel);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        logToggle.addActionListener(e -> {
            logScroll.setVisible(logToggle.isSelected());
            south.revalidate();
            south.repaint();
        });
        right.add(logToggle);
        JButton close = smallButton("Close", e -> close());
        right.add(close);

        JPanel bar = new JPanel(new BorderLayout());
        bar.add(status, BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);
        south.add(bar, BorderLayout.SOUTH);
        return south;
    }

    private JButton actionButton(String text, java.awt.event.ActionListener l) {
        JButton b = smallButton(text, l);
        actionButtons.add(b);
        return b;
    }

    private JButton globalButton(String text, LPMCommand command) {
        JButton b = smallButton(text, e -> runGlobal(command));
        actionButtons.add(b);
        return b;
    }

    private JButton smallButton(String text, java.awt.event.ActionListener l) {
        JButton b = new JButton(text);
        b.setMargin(new Insets(2, 6, 2, 6));
        b.setFocusPainted(false);
        b.addActionListener(l);
        return b;
    }

    // ------------------------------------------------------------------
    // Confirmation overlay (replaces modal dialogs, which escape capture)
    // ------------------------------------------------------------------

    private void buildOverlay() {
        overlay.setLayout(new GridBagLayout());
        overlayMessage.setEditable(false);
        overlayMessage.setLineWrap(true);
        overlayMessage.setWrapStyleWord(true);
        overlayMessage.setOpaque(false);
        overlayMessage.setFont(overlayMessage.getFont().deriveFont(Font.PLAIN, 12f));
        overlayMessage.setPreferredSize(new Dimension(420, 180));

        JPanel box = new JPanel(new BorderLayout(6, 8));
        box.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(90, 90, 90)),
                new EmptyBorder(12, 12, 12, 12)));
        box.setBackground(new Color(245, 245, 245));
        box.add(new JScrollPane(overlayMessage), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton ok = new JButton("Confirm");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            Runnable r = pendingConfirm;
            hideOverlay();
            if (r != null) {
                r.run();
            }
        });
        cancel.addActionListener(e -> {
            pendingConfirm = null;
            hideOverlay();
            appendLog("Cancelled by user.");
            setBusy(false);
        });
        buttons.add(ok);
        buttons.add(cancel);
        box.add(buttons, BorderLayout.SOUTH);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1;
        gbc.weighty = 1;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.CENTER;
        overlay.add(box, gbc);
    }

    private void showConfirm(String message, Runnable onConfirm) {
        pendingConfirm = onConfirm;
        overlayMessage.setText(message);
        overlayMessage.setCaretPosition(0);
        overlay.setVisible(true);
        layoutLayers();
        overlay.repaint();
    }

    private void hideOverlay() {
        overlay.setVisible(false);
        pendingConfirm = null;
    }

    private void layoutLayers() {
        int w = layers.getWidth();
        int h = layers.getHeight();
        content.setBounds(0, 0, w, h);
        overlay.setBounds(0, 0, w, h);
    }

    /** A translucent panel that dims the UI beneath a confirmation. */
    private static final class DimmingPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(new Color(0, 0, 0, 110));
            g.fillRect(0, 0, getWidth(), getHeight());
            super.paintComponent(g);
        }
    }

    // ------------------------------------------------------------------
    // Availability / version
    // ------------------------------------------------------------------

    private void initAvailability() {
        boolean binary = LPMExecutor.isLPMAvailable();
        boolean database = db.isAvailable();
        if (!binary && !database) {
            setBanner("LPM not found at /usr/bin/lpm and no database at "
                    + db.getDirectory() + " - browsing and actions are unavailable "
                    + "on this machine.", true);
        } else if (!binary) {
            setBanner("LPM binary not found at /usr/bin/lpm - the database can be "
                    + "browsed but no action can run.", true);
        } else if (!database) {
            setBanner("LPM database not found at " + db.getDirectory()
                    + " - run \"Update DB\" to populate it.", false);
        }
        setActionsEnabled(binary);
    }

    private void setBanner(String text, boolean error) {
        banner.setText(text);
        banner.setForeground(error ? new Color(160, 30, 30) : new Color(40, 80, 140));
        banner.setVisible(true);
    }

    private void loadLpmVersion() {
        if (!LPMExecutor.isLPMAvailable()) {
            return;
        }
        executor.submit(() -> {
            try {
                String version = LPMExecutor.getLPMVersion();
                SwingUtilities.invokeLater(() -> statusLabel.setText("LPM " + version));
            } catch (LPMExecutionException ex) {
                // Non-fatal: leave the status label as-is.
            }
        });
    }

    // ------------------------------------------------------------------
    // Category loading
    // ------------------------------------------------------------------

    private void selectCategory(Category c) {
        category = c;
        categoryList.setSelectedValue(c, true);
        reload();
    }

    private void reload() {
        if (category == Category.UPGRADABLE) {
            loadUpgradable();
        } else {
            loadFromDatabase();
        }
    }

    private void loadFromDatabase() {
        setBusy(true);
        final Category c = category;
        executor.submit(() -> {
            if (c == Category.HISTORY) {
                List<String[]> rows = db.readHistory(HISTORY_LIMIT);
                SwingUtilities.invokeLater(() -> {
                    setColumns(HISTORY_COLUMNS);
                    tableModel.setRowCount(0);
                    for (String[] r : rows) {
                        tableModel.addRow(new Object[] {r[0], r[1], r[2], r[3]});
                    }
                    summarize(rows.size() + " history entries");
                    setBusy(false);
                });
                return;
            }

            List<LpmPackage> list;
            switch (c) {
                case AVAILABLE:
                    list = db.readPackages();
                    break;
                case HELD:
                    list = db.readHeld();
                    break;
                case INSTALLED:
                default:
                    list = db.readInstalled();
                    break;
            }
            final List<LpmPackage> pkgs = list;
            SwingUtilities.invokeLater(() -> {
                packages = pkgs;
                setColumns(PACKAGE_COLUMNS);
                tableModel.setRowCount(0);
                for (LpmPackage p : pkgs) {
                    tableModel.addRow(new Object[] {
                            p.getName(), p.getVersion(), p.getStatus(),
                            p.getDescription()});
                }
                summarize(pkgs.size() + " packages");
                setBusy(false);
            });
        });
    }

    private void loadUpgradable() {
        if (!LPMExecutor.isLPMAvailable()) {
            setColumns(PACKAGE_COLUMNS);
            tableModel.setRowCount(0);
            summarize("LPM binary unavailable");
            return;
        }
        setBusy(true);
        executor.submit(() -> {
            try {
                LPMExecutor ex = new LPMExecutor(
                        line -> SwingUtilities.invokeLater(() -> appendLog(line)),
                        line -> SwingUtilities.invokeLater(() -> appendLog("[lpm] " + line)),
                        false);
                OperationResult r = ex.execute(LPMCommand.UPGRADABLE,
                        new ArrayList<>(), false);
                List<LpmPackage> upgradable = parseUpgradable(r.getStdout());
                SwingUtilities.invokeLater(() -> {
                    packages = upgradable;
                    setColumns(PACKAGE_COLUMNS);
                    tableModel.setRowCount(0);
                    for (LpmPackage p : upgradable) {
                        tableModel.addRow(new Object[] {
                                p.getName(), p.getVersion(), "upgradable",
                                p.getDescription()});
                    }
                    summarize(upgradable.size() + " upgradable");
                    setBusy(false);
                });
            } catch (LPMExecutionException ex) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("upgradable failed: " + ex.getMessage());
                    summarize("Error");
                    setBusy(false);
                });
            }
        });
    }

    /** Tolerant parse of {@code lpm --no-color upgradable} output lines. */
    private List<LpmPackage> parseUpgradable(String stdout) {
        List<LpmPackage> result = new ArrayList<>();
        if (stdout == null) {
            return result;
        }
        for (String raw : stdout.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("[")) {
                continue;
            }
            boolean held = line.contains("(held)");
            String[] tok = line.replaceAll("\\(held\\)", " ").trim().split("\\s+");
            if (tok.length == 0 || tok[0].isEmpty()) {
                continue;
            }
            String name = tok[0];
            String version = tok.length > 1 ? tok[1] : "";
            result.add(new LpmPackage(name, version,
                    held ? "held - skipped by upgrade" : "", "", "", true, held));
        }
        return result;
    }

    private void setColumns(String[] columns) {
        // Detach the sorter while the model shape changes, then reattach so no
        // stale view->model index survives the column swap.
        table.setRowSorter(null);
        tableModel.setColumnIdentifiers(columns);
        table.setRowSorter(sorter);
        applyFilter();
    }

    private void applyFilter() {
        String text = searchField.getText().trim();
        if (text.isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(RowFilter.regexFilter("(?i)" + java.util.regex
                    .Pattern.quote(text)));
        }
    }

    private void summarize(String text) {
        summaryLabel.setText(text);
    }

    // ------------------------------------------------------------------
    // Details
    // ------------------------------------------------------------------

    private LpmPackage selectedPackage() {
        int view = table.getSelectedRow();
        if (view < 0 || category == Category.HISTORY) {
            return null;
        }
        int model = table.convertRowIndexToModel(view);
        if (model < 0 || model >= packages.size()) {
            return null;
        }
        return packages.get(model);
    }

    private void showSelectedDetail() {
        LpmPackage p = selectedPackage();
        if (p == null) {
            if (category == Category.HISTORY) {
                int view = table.getSelectedRow();
                if (view >= 0) {
                    int m = table.convertRowIndexToModel(view);
                    detailArea.setText("History entry " + (m + 1)
                            + "\nSelect a package category for details.");
                }
            } else {
                detailArea.setText("Select a package to see its details.");
            }
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Package:  ").append(p.getName()).append('\n');
        sb.append("Version:  ").append(p.getVersion()).append('\n');
        sb.append("Status:   ").append(p.getStatus()).append('\n');
        sb.append("Deps:     ").append(p.getDeps().isEmpty() ? "-" : p.getDeps()).append('\n');
        sb.append("Checksum: ").append(p.getChecksum().isEmpty() ? "-" : p.getChecksum()).append('\n');
        sb.append('\n').append(p.getDescription()).append('\n');
        detailArea.setText(sb.toString());
        detailArea.setCaretPosition(0);
    }

    private void whySelected() {
        LpmPackage p = selectedPackage();
        if (p == null) {
            statusLabel.setText("Select a package first");
            return;
        }
        runReadOnly(LPMCommand.WHY, List.of(p.getName()),
                "Reverse dependencies of " + p.getName());
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void actOnSelected(LPMCommand command) {
        LpmPackage p = selectedPackage();
        if (p == null) {
            statusLabel.setText("Select a package first");
            return;
        }
        dispatch(command, List.of(p.getName()));
    }

    private void runGlobal(LPMCommand command) {
        dispatch(command, new ArrayList<>());
    }

    /**
     * Routes a command through the contract flow: mutating commands preview
     * with {@code --dry-run} first (§4.1); destructive ones then require an
     * in-panel confirmation (§4.2); read-only commands run straight away.
     */
    private void dispatch(LPMCommand command, List<String> args) {
        if (!LPMExecutor.isLPMAvailable()) {
            statusLabel.setText("LPM binary unavailable");
            return;
        }
        if (busy) {
            statusLabel.setText("An operation is already running");
            return;
        }
        if (!command.isMutating()) {
            runReadOnly(command, args, describe(command, args));
            return;
        }
        previewThenRun(command, args);
    }

    private void previewThenRun(LPMCommand command, List<String> args) {
        setBusy(true);
        clearLog();
        appendLog("=== Dry-run preview: lpm --dry-run "
                + command.getCommandName() + " " + String.join(" ", args) + " ===");
        executor.submit(() -> {
            try {
                LPMExecutor ex = newExecutor(command);
                ex.execute(command, args, true);
                SwingUtilities.invokeLater(() -> {
                    appendLog("--- Preview complete ---");
                    if (command.requiresConfirmation()) {
                        showConfirm("LPM will run:\n\n  lpm "
                                + command.getCommandName() + " "
                                + String.join(" ", args)
                                + "\n\nThe dry-run preview above shows exactly what "
                                + "will change.\nProceed?",
                                () -> commit(command, args));
                    } else {
                        commit(command, args);
                    }
                });
            } catch (LPMExecutionException ex) {
                SwingUtilities.invokeLater(() -> previewFailed(ex));
            }
        });
    }

    private void commit(LPMCommand command, List<String> args) {
        appendLog("=== Committing: lpm " + command.getCommandName()
                + " " + String.join(" ", args) + " ===");
        executor.submit(() -> {
            try {
                LPMExecutor ex = newExecutor(command);
                OperationResult r = ex.execute(command, args, false);
                SwingUtilities.invokeLater(() -> {
                    appendLog("--- Done (exit " + r.getExitCode() + ") ---");
                    if (command == LPMCommand.VERIFY && !r.isSuccess()) {
                        appendLog("Integrity issues found (expected result state for verify).");
                    }
                    setBusy(false);
                    statusLabel.setText("Ready");
                    reload();
                });
            } catch (LPMExecutionException ex) {
                SwingUtilities.invokeLater(() -> operationFailed(command, args, ex));
            }
        });
    }

    private void runReadOnly(LPMCommand command, List<String> args, String title) {
        setBusy(true);
        clearLog();
        appendLog("=== " + title + " ===");
        executor.submit(() -> {
            try {
                LPMExecutor ex = new LPMExecutor(
                        line -> SwingUtilities.invokeLater(() -> appendLog(line)),
                        line -> SwingUtilities.invokeLater(() -> appendLog("[lpm] " + line)),
                        false);
                OperationResult r = ex.execute(command, args, false);
                SwingUtilities.invokeLater(() -> {
                    if (command == LPMCommand.WHY || command == LPMCommand.INFO) {
                        detailArea.setText(r.getStdout() == null ? "" : r.getStdout());
                        detailArea.setCaretPosition(0);
                    }
                    appendLog("--- Done (exit " + r.getExitCode() + ") ---");
                    setBusy(false);
                    statusLabel.setText("Ready");
                });
            } catch (LPMExecutionException ex) {
                SwingUtilities.invokeLater(() -> {
                    appendLog("Failed: " + ex.getMessage());
                    if (ex.getStderr() != null) {
                        appendLog(ex.getStderr());
                    }
                    setBusy(false);
                    statusLabel.setText("Failed");
                });
            }
        });
    }

    private LPMExecutor newExecutor(LPMCommand command) {
        return new LPMExecutor(
                line -> SwingUtilities.invokeLater(() -> appendLog(line)),
                line -> SwingUtilities.invokeLater(() -> appendLog("[lpm] " + line)),
                command.isMutating());
    }

    private void previewFailed(LPMExecutionException ex) {
        appendLog("Preview failed: " + ex.getMessage());
        if (ex.getStderr() != null) {
            appendLog(ex.getStderr());
        }
        offerRetryOrStop(ex, null, null);
    }

    private void operationFailed(LPMCommand command, List<String> args,
                                 LPMExecutionException ex) {
        appendLog("Operation failed: " + ex.getMessage());
        if (ex.getStderr() != null) {
            appendLog(ex.getStderr());
        }
        offerRetryOrStop(ex, command, args);
    }

    /**
     * Surfaces LPM's verbatim error (§5.2). A lock contention error offers a
     * retry affordance (§5.3) instead of a dead end.
     */
    private void offerRetryOrStop(LPMExecutionException ex, LPMCommand command,
                                  List<String> args) {
        String stderr = ex.getStderr() == null ? "" : ex.getStderr();
        boolean locked = stderr.contains("Another lpm instance is running")
                || (ex.getMessage() != null
                    && ex.getMessage().contains("already in progress"));
        if (locked && command != null) {
            showConfirm("Another LPM instance holds the lock:\n\n  "
                    + stderr.trim() + "\n\nRetry the operation?",
                    () -> commit(command, args));
        } else {
            setBusy(false);
            statusLabel.setText("Failed");
        }
    }

    private String describe(LPMCommand command, List<String> args) {
        return "lpm " + command.getCommandName()
                + (args.isEmpty() ? "" : " " + String.join(" ", args));
    }

    // ------------------------------------------------------------------
    // Log / status
    // ------------------------------------------------------------------

    private void appendLog(String text) {
        if (text == null) {
            return;
        }
        logArea.append(text);
        if (!text.endsWith("\n")) {
            logArea.append("\n");
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
        // Reveal the log pane the first time an operation produces output, so
        // the streamed lpm output is never silently hidden.
        if (!logToggle.isSelected()) {
            logToggle.setSelected(true);
            logScroll.setVisible(true);
            content.revalidate();
            content.repaint();
        }
    }

    private void clearLog() {
        logArea.setText("");
    }

    private void setBusy(boolean b) {
        busy = b;
        progressBar.setVisible(b);
        setActionsEnabled(!b && LPMExecutor.isLPMAvailable());
        statusLabel.setText(b ? "Working..." : "Ready");
    }

    private void setActionsEnabled(boolean enabled) {
        for (JButton b : actionButtons) {
            b.setEnabled(enabled);
        }
        searchField.setEnabled(true);
    }

    private void close() {
        executor.shutdownNow();
        if (onClose != null) {
            onClose.run();
        }
    }
}
