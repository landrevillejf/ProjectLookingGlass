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
package org.jdesktop.lg3d.dbmanager.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Window;
import java.io.File;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.JLabel;
import org.jdesktop.lg3d.dbmanager.jdbc.CsvExporter;
import org.jdesktop.lg3d.dbmanager.jdbc.QueryResult;
import org.jdesktop.lg3d.dbmanager.model.AppSettings;
import org.jdesktop.lg3d.dbmanager.model.ConnectionProfile;
import org.jdesktop.lg3d.dbmanager.session.ConnectionManager;
import org.jdesktop.lg3d.dbmanager.session.DbSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Database Manager's root panel: a toolbar, the connection navigator on the
 * left, a tabbed SQL editor/results area on the right, and a message log with a
 * status line at the bottom.
 *
 * <p>This is a plain Swing {@link JPanel} with a no-argument constructor and no
 * Java 3D, so the desktop hosts it two ways: in 3D on a {@code SwingNode} inside
 * a {@code Frame3D} (via the {@code lg3d-apps} wrapper), and in the 2D/Swing
 * desktop as an MDI internal frame. Every JDBC call runs on a {@link SwingWorker};
 * the EDT only builds widgets and applies results.</p>
 *
 * <p>Construction never opens a window, so the panel can be built headless in the
 * unit tests; dialogs (new connection, settings, password prompt, file chooser)
 * are created only in response to a user action.</p>
 */
public class DbManagerMainPanel extends JPanel {

    private static final Logger LOG = LoggerFactory.getLogger(DbManagerMainPanel.class);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Preferred panel size in native pixels. */
    public static final int WIDTH_PX = 1000;
    public static final int HEIGHT_PX = 680;

    private final ConnectionManager manager;
    private final NavigatorTree navigator;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextArea logArea = new JTextArea(6, 40);
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton connectButton = new JButton("Connect");
    private final JButton disconnectButton = new JButton("Disconnect");
    private final JButton commitButton = new JButton("Commit");
    private final JButton rollbackButton = new JButton("Rollback");

    private int tabCounter;
    private volatile DbSession activeSession;
    private volatile String activeProfileId;

    /** Creates the panel with a default {@link ConnectionManager}. */
    public DbManagerMainPanel() {
        this(new ConnectionManager());
    }

    /**
     * Creates the panel around an explicit manager (used by the tests).
     *
     * @param manager the connection/profile controller
     */
    public DbManagerMainPanel(ConnectionManager manager) {
        super(new BorderLayout());
        this.manager = manager;
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        this.navigator = new NavigatorTree(manager);
        this.navigator.setActions(new NavigatorActions());

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);

        addTab(null);
        updateStatus();
    }

    // ------------------------------------------------------------------
    // layout
    // ------------------------------------------------------------------

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(button("New", "Create a new connection", this::newConnection));
        bar.add(button("Edit", "Edit the selected connection", this::editConnection));
        bar.add(button("Delete", "Delete the selected connection", this::deleteConnection));
        bar.addSeparator();
        connectButton.setToolTipText("Connect to the selected database");
        connectButton.addActionListener(e -> connectSelected());
        disconnectButton.setToolTipText("Disconnect the active database");
        disconnectButton.addActionListener(e -> disconnectActive());
        bar.add(connectButton);
        bar.add(disconnectButton);
        bar.add(button("Refresh", "Reload the navigator", navigator::refresh));
        bar.addSeparator();
        bar.add(button("New Query", "Open a new SQL editor tab", () -> addTab(null)));
        bar.add(button("Run", "Execute the current editor (Ctrl+Enter)", this::runCurrent));
        bar.add(button("Stop", "Cancel the running statement", this::stopCurrent));
        bar.addSeparator();
        commitButton.setToolTipText("Commit the current transaction");
        commitButton.addActionListener(e -> transaction(true));
        rollbackButton.setToolTipText("Roll back the current transaction");
        rollbackButton.addActionListener(e -> transaction(false));
        bar.add(commitButton);
        bar.add(rollbackButton);
        bar.addSeparator();
        bar.add(button("Export CSV", "Export the current result grid", this::exportCsv));
        bar.add(button("Settings", "Application settings", this::openSettings));
        return bar;
    }

    private JButton button(String label, String tooltip, Runnable action) {
        JButton b = new JButton(label);
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }

    private JSplitPane buildCenter() {
        navigator.setPreferredSize(new Dimension(260, HEIGHT_PX));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, navigator, tabs);
        split.setDividerLocation(260);
        split.setResizeWeight(0.25);
        return split;
    }

    private JPanel buildBottom() {
        JPanel bottom = new JPanel(new BorderLayout());
        logArea.setEditable(false);
        logArea.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Messages"));
        logScroll.setPreferredSize(new Dimension(WIDTH_PX, 130));
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, java.awt.Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        bottom.add(logScroll, BorderLayout.CENTER);
        bottom.add(statusLabel, BorderLayout.SOUTH);
        return bottom;
    }

    // ------------------------------------------------------------------
    // tabs
    // ------------------------------------------------------------------

    private SqlEditorTab addTab(String sql) {
        tabCounter++;
        SqlEditorTab tab = new SqlEditorTab(new EditorRunner(), sql);
        String title = "Query " + tabCounter;
        tabs.addTab(title, tab);
        tabs.setSelectedComponent(tab);
        return tab;
    }

    private SqlEditorTab currentTab() {
        return (SqlEditorTab) tabs.getSelectedComponent();
    }

    private void runCurrent() {
        SqlEditorTab tab = currentTab();
        if (tab != null) {
            tab.execute();
        }
    }

    private void stopCurrent() {
        SqlEditorTab tab = currentTab();
        if (tab != null) {
            tab.cancel();
        }
    }

    // ------------------------------------------------------------------
    // connections
    // ------------------------------------------------------------------

    private void newConnection() {
        ConnectionDialog dlg = new ConnectionDialog(ownerFrame(), manager.getDrivers(),
                manager.getSettings(), null);
        ConnectionProfile p = dlg.showDialog();
        if (p != null) {
            manager.addProfile(p);
            navigator.refresh();
            log("Saved connection '" + p.getName() + "'");
        }
    }

    private void editConnection() {
        String id = navigator.selectedProfileId();
        ConnectionProfile p = (id == null) ? null : manager.getProfile(id).orElse(null);
        if (p == null) {
            log("Select a connection to edit.");
            return;
        }
        ConnectionDialog dlg = new ConnectionDialog(ownerFrame(), manager.getDrivers(),
                manager.getSettings(), p);
        ConnectionProfile edited = dlg.showDialog();
        if (edited != null) {
            manager.updateProfile(edited);
            navigator.refresh();
            log("Updated connection '" + edited.getName() + "'");
        }
    }

    private void deleteConnection() {
        String id = navigator.selectedProfileId();
        if (id == null) {
            log("Select a connection to delete.");
            return;
        }
        ConnectionProfile p = manager.getProfile(id).orElse(null);
        int answer = JOptionPane.showConfirmDialog(this,
                "Delete connection '" + (p != null ? p.getName() : id) + "'?",
                "Delete", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.OK_OPTION) {
            if (id.equals(activeProfileId)) {
                activeSession = null;
                activeProfileId = null;
            }
            manager.removeProfile(id);
            navigator.refresh();
            updateStatus();
            log("Deleted connection.");
        }
    }

    private void connectSelected() {
        String id = navigator.selectedProfileId();
        if (id == null) {
            log("Select a connection first.");
            return;
        }
        if (manager.isConnected(id)) {
            activeProfileId = id;
            activeSession = manager.getSession(id);
            navigator.refresh();
            updateStatus();
            log("Already connected.");
            return;
        }
        ConnectionProfile p = manager.getProfile(id).orElse(null);
        if (p == null) {
            return;
        }
        String password = p.getPassword();
        if (password == null) {
            password = promptPassword(p.getName());
            if (password == null) {
                return; // cancelled
            }
        }
        final String pw = password;
        connectButton.setEnabled(false);
        log("Connecting to '" + p.getName() + "'\u2026");
        new SwingWorker<DbSession, Void>() {
            @Override
            protected DbSession doInBackground() throws SQLException {
                return manager.connect(p, pw);
            }

            @Override
            protected void done() {
                connectButton.setEnabled(true);
                try {
                    DbSession s = get();
                    activeSession = s;
                    activeProfileId = p.getId();
                    navigator.refresh();
                    updateStatus();
                    log("Connected: " + s.describeServer());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    log("Connect interrupted.");
                } catch (ExecutionException ex) {
                    log("Connect failed: " + rootMessage(ex));
                }
            }
        }.execute();
    }

    private void disconnectActive() {
        if (activeProfileId == null) {
            log("No active connection.");
            return;
        }
        manager.disconnect(activeProfileId);
        activeSession = null;
        activeProfileId = null;
        navigator.refresh();
        updateStatus();
        log("Disconnected.");
    }

    private void transaction(boolean commit) {
        DbSession s = activeSession;
        if (s == null || !s.isConnected()) {
            log("No active connection.");
            return;
        }
        if (s.isAutoCommit()) {
            log("Auto-commit is on; nothing to " + (commit ? "commit" : "roll back")
                    + ". Turn auto-commit off in the connection settings.");
            return;
        }
        if (!commit && manager.getSettings().isConfirmOnWrite()) {
            int answer = JOptionPane.showConfirmDialog(this, "Roll back the current transaction?",
                    "Rollback", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }
        }
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    if (commit) {
                        s.commit();
                        return "Committed.";
                    }
                    s.rollback();
                    return "Rolled back.";
                } catch (SQLException e) {
                    return (commit ? "Commit" : "Rollback") + " failed: " + e.getMessage();
                }
            }

            @Override
            protected void done() {
                try {
                    log(get());
                } catch (InterruptedException | ExecutionException e) {
                    log("Transaction error: " + rootMessage(e));
                }
            }
        }.execute();
    }

    private void exportCsv() {
        SqlEditorTab tab = currentTab();
        QueryResult result = (tab != null) ? tab.getResults().getResult() : null;
        if (result == null || !result.hasResultSet()) {
            log("Nothing to export. Run a query that returns rows first.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export result as CSV");
        chooser.setSelectedFile(new File("result.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        try {
            new CsvExporter().write(result, file.toPath());
            log("Exported " + result.getRowCount() + " rows to " + file.getAbsolutePath());
        } catch (java.io.IOException ex) {
            log("Export failed: " + ex.getMessage());
        }
    }

    private void openSettings() {
        SettingsDialog dlg = new SettingsDialog(ownerFrame(), manager.getSettings());
        AppSettings updated = dlg.showDialog();
        if (updated != null) {
            manager.saveSettings(updated);
            applySettingsToTabs();
            log("Settings saved.");
        }
    }

    private void applySettingsToTabs() {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            ((SqlEditorTab) tabs.getComponentAt(i)).applySettings();
        }
    }

    private String promptPassword(String connectionName) {
        JPasswordField pf = new JPasswordField();
        int answer = JOptionPane.showConfirmDialog(this, pf,
                "Password for " + connectionName, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return null;
        }
        return new String(pf.getPassword());
    }

    // ------------------------------------------------------------------
    // status / log
    // ------------------------------------------------------------------

    private void updateStatus() {
        DbSession s = activeSession;
        if (s != null && s.isConnected()) {
            String name = s.getProfile().getName();
            statusLabel.setText("Connected: " + name + "  \u2022  " + s.describeServer()
                    + "  \u2022  auto-commit " + (s.isAutoCommit() ? "on" : "off"));
            disconnectButton.setEnabled(true);
            commitButton.setEnabled(!s.isAutoCommit());
            rollbackButton.setEnabled(!s.isAutoCommit());
        } else {
            statusLabel.setText("Not connected");
            disconnectButton.setEnabled(false);
            commitButton.setEnabled(false);
            rollbackButton.setEnabled(false);
        }
    }

    /** Appends a timestamped line to the message log (bounded). */
    public void log(String message) {
        if (message == null) {
            return;
        }
        String line = LocalTime.now().format(CLOCK) + "  " + message + "\n";
        logArea.append(line);
        // Keep the log from growing without bound over a long session.
        int max = 20_000;
        if (logArea.getDocument().getLength() > max) {
            try {
                logArea.getDocument().remove(0, logArea.getDocument().getLength() - max);
            } catch (javax.swing.text.BadLocationException e) {
                LOG.debug("Could not trim the message log", e);
            }
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private Frame ownerFrame() {
        Window w = SwingUtilities.getWindowAncestor(this);
        return (w instanceof Frame f) ? f : null;
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    /** Releases every open connection; called when the host window closes. */
    public void dispose() {
        manager.shutdown();
    }

    // ------------------------------------------------------------------
    // callbacks
    // ------------------------------------------------------------------

    private final class EditorRunner implements SqlEditorTab.Runner {
        @Override
        public DbSession activeSession() {
            DbSession s = activeSession;
            return (s != null && s.isConnected()) ? s : null;
        }

        @Override
        public AppSettings settings() {
            return manager.getSettings();
        }

        @Override
        public void log(String message) {
            DbManagerMainPanel.this.log(message);
        }
    }

    private final class NavigatorActions implements NavigatorTree.Actions {
        @Override
        public DbSession sessionFor(String profileId) {
            return manager.getSession(profileId);
        }

        @Override
        public void onOpenTable(NavigatorTree.TableSelection sel) {
            String sql = "SELECT * FROM " + qualified(sel);
            SqlEditorTab tab = addTab(sql);
            tab.execute();
        }

        @Override
        public void onProfileSelected(String profileId) {
            DbSession s = manager.getSession(profileId);
            if (s != null) {
                activeSession = s;
                activeProfileId = profileId;
                updateStatus();
            }
        }

        @Override
        public void log(String message) {
            DbManagerMainPanel.this.log(message);
        }
    }

    private static String qualified(NavigatorTree.TableSelection sel) {
        StringBuilder sb = new StringBuilder();
        if (sel.schema() != null && !sel.schema().isBlank()) {
            sb.append(quote(sel.schema())).append('.');
        }
        sb.append(quote(sel.name()));
        return sb.toString();
    }

    private static String quote(String identifier) {
        if (identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return identifier;
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
