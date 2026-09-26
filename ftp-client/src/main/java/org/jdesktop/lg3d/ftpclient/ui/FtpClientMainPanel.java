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
package org.jdesktop.lg3d.ftpclient.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Window;
import java.io.File;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.jdesktop.lg3d.ftpclient.model.AppSettings;
import org.jdesktop.lg3d.ftpclient.model.Protocol;
import org.jdesktop.lg3d.ftpclient.model.SiteProfile;
import org.jdesktop.lg3d.ftpclient.net.RemoteClient;
import org.jdesktop.lg3d.ftpclient.net.RemoteEntry;
import org.jdesktop.lg3d.ftpclient.net.RemotePaths;
import org.jdesktop.lg3d.ftpclient.session.ConnectionManager;
import org.jdesktop.lg3d.ftpclient.session.TransferJob;
import org.jdesktop.lg3d.ftpclient.session.TransferListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The FTP Client's root panel: a toolbar and site selector along the top, a
 * local browser and a remote browser side by side in the middle, and the
 * transfer queue with a message log and status line at the bottom.
 *
 * <p>This is a plain Swing {@link JPanel} with a no-argument constructor and no
 * Java 3D, so the desktop hosts it two ways: in 3D on a {@code SwingNode} inside
 * a {@code Frame3D} (via the {@code lg3d-apps} wrapper), and in the 2D/Swing
 * desktop as an MDI internal frame. Every blocking network call - connect, list,
 * transfer, mkdir, delete - runs on a {@link SwingWorker}; the EDT only builds
 * widgets and applies results, so the window never freezes mid-transfer.</p>
 *
 * <p>Construction never opens a window, so the panel can be built headless in
 * the unit tests; dialogs (site editor, settings, password prompt, confirmations)
 * are created only in response to a user action.</p>
 */
public class FtpClientMainPanel extends JPanel {

    private static final Logger LOG = LoggerFactory.getLogger(FtpClientMainPanel.class);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Preferred panel size in native pixels. */
    public static final int WIDTH_PX = 1000;
    public static final int HEIGHT_PX = 680;

    private final ConnectionManager manager;
    private final LocalBrowser localBrowser = new LocalBrowser();
    private final RemoteBrowser remoteBrowser;
    private final TransferTableModel transferModel = new TransferTableModel();
    private final JTable transferTable = new JTable(transferModel);
    private final JTextArea logArea = new JTextArea(6, 40);
    private final JLabel statusLabel = new JLabel(" ");

    private final JComboBox<SiteProfile> siteCombo = new JComboBox<>();
    private final JButton connectButton = new JButton("Connect");
    private final JButton disconnectButton = new JButton("Disconnect");
    private final JButton uploadButton = new JButton("Upload");
    private final JButton downloadButton = new JButton("Download");
    private final JButton mkdirButton = new JButton("New Folder");
    private final JButton deleteButton = new JButton("Delete");
    private final JButton renameButton = new JButton("Rename");

    private volatile boolean queueRunning;

    /** Creates the panel with a default {@link ConnectionManager}. */
    public FtpClientMainPanel() {
        this(new ConnectionManager());
    }

    /**
     * Creates the panel around an explicit manager (used by the tests).
     *
     * @param manager the connection/profile controller
     */
    public FtpClientMainPanel(ConnectionManager manager) {
        super(new BorderLayout());
        this.manager = manager;
        this.remoteBrowser = new RemoteBrowser(manager);
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        remoteBrowser.setLogger(this::log);
        remoteBrowser.setOnActivate(this::downloadEntries);
        localBrowser.setOnActivate(this::uploadFiles);
        manager.getTransfers().addListener(new UiTransferListener());

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildBottom(), BorderLayout.SOUTH);

        refreshSites();
        updateStatus();
    }

    // ------------------------------------------------------------------
    // layout
    // ------------------------------------------------------------------

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        siteCombo.setToolTipText("Saved sites");
        siteCombo.setRenderer(new SiteRenderer());
        siteCombo.addActionListener(e -> updateStatus());
        bar.add(siteCombo);
        bar.add(button("New", "Create a new site", this::newSite));
        bar.add(button("Edit", "Edit the selected site", this::editSite));
        bar.add(button("Delete Site", "Delete the selected site", this::deleteSite));
        bar.addSeparator();
        connectButton.setToolTipText("Connect to the selected site");
        connectButton.addActionListener(e -> connectSelected());
        disconnectButton.setToolTipText("Disconnect the active site");
        disconnectButton.addActionListener(e -> disconnectActive());
        bar.add(connectButton);
        bar.add(disconnectButton);
        bar.addSeparator();
        uploadButton.setToolTipText("Upload the selected local files");
        uploadButton.addActionListener(e -> uploadFiles(localBrowser.getSelectedFiles()));
        downloadButton.setToolTipText("Download the selected remote files");
        downloadButton.addActionListener(e -> downloadEntries(remoteBrowser.getSelectedEntries()));
        bar.add(uploadButton);
        bar.add(downloadButton);
        bar.addSeparator();
        mkdirButton.setToolTipText("Create a remote folder");
        mkdirButton.addActionListener(e -> makeRemoteDir());
        renameButton.setToolTipText("Rename the selected remote item");
        renameButton.addActionListener(e -> renameRemote());
        deleteButton.setToolTipText("Delete the selected remote items");
        deleteButton.addActionListener(e -> deleteRemote());
        bar.add(mkdirButton);
        bar.add(renameButton);
        bar.add(deleteButton);
        bar.add(button("Refresh", "Reload both browsers", this::refreshAll));
        bar.addSeparator();
        bar.add(button("Cancel", "Cancel the selected transfer", this::cancelTransfer));
        bar.add(button("Clear", "Remove finished transfers", this::clearCompleted));
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
        localBrowser.setPreferredSize(new Dimension(WIDTH_PX / 2, HEIGHT_PX));
        remoteBrowser.setPreferredSize(new Dimension(WIDTH_PX / 2, HEIGHT_PX));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, localBrowser, remoteBrowser);
        split.setDividerLocation(WIDTH_PX / 2);
        split.setResizeWeight(0.5);
        return split;
    }

    private JPanel buildBottom() {
        JPanel bottom = new JPanel(new BorderLayout());
        transferTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        transferTable.setFillsViewportHeight(true);
        JScrollPane queueScroll = new JScrollPane(transferTable);
        queueScroll.setBorder(BorderFactory.createTitledBorder("Transfer queue"));
        queueScroll.setPreferredSize(new Dimension(WIDTH_PX, 150));

        logArea.setEditable(false);
        logArea.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Messages"));
        logScroll.setPreferredSize(new Dimension(WIDTH_PX, 120));

        JPanel stacked = new JPanel(new BorderLayout());
        stacked.add(queueScroll, BorderLayout.NORTH);
        stacked.add(logScroll, BorderLayout.CENTER);

        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, java.awt.Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        bottom.add(stacked, BorderLayout.CENTER);
        bottom.add(statusLabel, BorderLayout.SOUTH);
        return bottom;
    }

    // ------------------------------------------------------------------
    // sites
    // ------------------------------------------------------------------

    private void refreshSites() {
        SiteProfile current = selectedProfile();
        siteCombo.removeAllItems();
        for (SiteProfile p : manager.getProfiles()) {
            siteCombo.addItem(p);
        }
        if (current != null) {
            siteCombo.setSelectedItem(current);
        }
        updateStatus();
    }

    private SiteProfile selectedProfile() {
        return (SiteProfile) siteCombo.getSelectedItem();
    }

    private void newSite() {
        SiteDialog dlg = new SiteDialog(ownerFrame(), manager.getSettings(), null);
        SiteProfile p = dlg.showDialog();
        if (p != null) {
            manager.addProfile(p);
            refreshSites();
            siteCombo.setSelectedItem(p);
            log("Saved site '" + p.getName() + "'");
        }
    }

    private void editSite() {
        SiteProfile p = selectedProfile();
        if (p == null) {
            log("Select a site to edit.");
            return;
        }
        SiteDialog dlg = new SiteDialog(ownerFrame(), manager.getSettings(), p);
        SiteProfile edited = dlg.showDialog();
        if (edited != null) {
            manager.updateProfile(edited);
            refreshSites();
            log("Updated site '" + edited.getName() + "'");
        }
    }

    private void deleteSite() {
        SiteProfile p = selectedProfile();
        if (p == null) {
            log("Select a site to delete.");
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "Delete site '" + p.getName() + "'?", "Delete",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.OK_OPTION) {
            manager.removeProfile(p.getId());
            refreshSites();
            log("Deleted site.");
        }
    }

    // ------------------------------------------------------------------
    // connection
    // ------------------------------------------------------------------

    private void connectSelected() {
        SiteProfile p = selectedProfile();
        if (p == null) {
            log("Select a site first.");
            return;
        }
        if (manager.isConnected()) {
            log("Already connected. Disconnect first.");
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
        log("Connecting to '" + p.getName() + "' (" + p.getProtocol() + ")\u2026");
        if (!p.isSecure()) {
            log("Warning: this site uses plain FTP; credentials and data are not encrypted.");
        }
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                RemoteClient c = manager.connect(p, pw);
                return c.getWorkingDirectory();
            }

            @Override
            protected void done() {
                connectButton.setEnabled(true);
                try {
                    String wd = get();
                    remoteBrowser.navigate(wd);
                    localBrowser.refresh();
                    updateStatus();
                    log("Connected to '" + p.getName() + "' at " + wd);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    log("Connect interrupted.");
                    updateStatus();
                } catch (ExecutionException ex) {
                    log("Connect failed: " + rootMessage(ex));
                    updateStatus();
                }
            }
        }.execute();
    }

    private void disconnectActive() {
        manager.disconnect();
        remoteBrowser.clear();
        updateStatus();
        log("Disconnected.");
    }

    // ------------------------------------------------------------------
    // transfers
    // ------------------------------------------------------------------

    private void uploadFiles(File[] files) {
        if (requireConnection() || files == null || files.length == 0) {
            return;
        }
        String dir = remoteBrowser.getCurrentPath();
        int queued = 0;
        for (File f : files) {
            if (f.isFile()) {
                manager.getTransfers().enqueueUpload(f.toPath(), RemotePaths.join(dir, f.getName()));
                queued++;
            } else {
                log("Skipping non-file '" + f.getName() + "' (folder upload is not supported).");
            }
        }
        if (queued > 0) {
            transferModel.refresh(manager.getTransfers().getQueue());
            log("Queued " + queued + " file(s) for upload.");
            startQueueWorker();
        }
    }

    private void downloadEntries(List<RemoteEntry> entries) {
        if (requireConnection() || entries == null || entries.isEmpty()) {
            return;
        }
        File dir = localBrowser.getCurrentDirectory();
        boolean overwriteAll = !manager.getSettings().isConfirmOverwrite();
        int queued = 0;
        for (RemoteEntry e : entries) {
            if (e.isDirectory()) {
                log("Skipping folder '" + e.getName() + "' (folder download is not supported).");
                continue;
            }
            File target = new File(dir, e.getName());
            if (target.exists() && !overwriteAll) {
                int answer = JOptionPane.showConfirmDialog(this,
                        "Overwrite existing '" + e.getName() + "'?", "Overwrite",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.OK_OPTION) {
                    continue;
                }
                overwriteAll = true; // do not ask again this batch
            }
            Path local = target.toPath();
            manager.getTransfers().enqueueDownload(
                    RemotePaths.join(remoteBrowser.getCurrentPath(), e.getName()), local);
            queued++;
        }
        if (queued > 0) {
            transferModel.refresh(manager.getTransfers().getQueue());
            log("Queued " + queued + " file(s) for download.");
            startQueueWorker();
        }
    }

    /**
     * Drives the queue from a single worker thread. A second request while one is
     * running is a no-op (the running worker picks up newly queued jobs because
     * {@code processQueue} iterates the live queue).
     */
    private void startQueueWorker() {
        if (queueRunning) {
            return;
        }
        queueRunning = true;
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                manager.getTransfers().processQueue();
                return null;
            }

            @Override
            protected void done() {
                queueRunning = false;
                transferModel.refresh(manager.getTransfers().getQueue());
            }
        }.execute();
    }

    private void cancelTransfer() {
        int row = transferTable.getSelectedRow();
        TransferJob job = (row >= 0) ? transferModel.getJobAt(transferTable.convertRowIndexToModel(row)) : null;
        if (job == null) {
            log("Select a transfer to cancel.");
            return;
        }
        manager.getTransfers().cancel(job.getId());
        transferModel.refresh(manager.getTransfers().getQueue());
        log("Cancellation requested for '" + job.getDisplayName() + "'.");
    }

    private void clearCompleted() {
        manager.getTransfers().clearCompleted();
        transferModel.refresh(manager.getTransfers().getQueue());
    }

    // ------------------------------------------------------------------
    // remote file operations
    // ------------------------------------------------------------------

    private void makeRemoteDir() {
        if (requireConnection()) {
            return;
        }
        String name = JOptionPane.showInputDialog(this, "New folder name:", "New Folder",
                JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) {
            return;
        }
        String path = RemotePaths.join(remoteBrowser.getCurrentPath(), name.trim());
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                manager.makeDirectory(path);
                return null;
            }

            @Override
            protected void done() {
                finish("Created folder " + path);
            }
        }.execute();
    }

    private void renameRemote() {
        if (requireConnection()) {
            return;
        }
        List<RemoteEntry> selected = remoteBrowser.getSelectedEntries();
        if (selected.size() != 1) {
            log("Select exactly one remote item to rename.");
            return;
        }
        RemoteEntry e = selected.get(0);
        String name = JOptionPane.showInputDialog(this, "New name for '" + e.getName() + "':",
                "Rename", JOptionPane.PLAIN_MESSAGE, null, null, e.getName()) instanceof String s ? s : null;
        if (name == null || name.isBlank() || name.equals(e.getName())) {
            return;
        }
        String from = RemotePaths.join(remoteBrowser.getCurrentPath(), e.getName());
        String to = RemotePaths.join(remoteBrowser.getCurrentPath(), name.trim());
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                manager.rename(from, to);
                return null;
            }

            @Override
            protected void done() {
                finish("Renamed to " + to);
            }
        }.execute();
    }

    private void deleteRemote() {
        if (requireConnection()) {
            return;
        }
        List<RemoteEntry> selected = remoteBrowser.getSelectedEntries();
        if (selected.isEmpty()) {
            log("Select remote items to delete.");
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                "Delete " + selected.size() + " remote item(s)?", "Delete",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        String dir = remoteBrowser.getCurrentPath();
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                int ok = 0;
                StringBuilder errors = new StringBuilder();
                for (RemoteEntry e : selected) {
                    String path = RemotePaths.join(dir, e.getName());
                    try {
                        if (e.isDirectory()) {
                            manager.removeDirectory(path);
                        } else {
                            manager.delete(path);
                        }
                        ok++;
                    } catch (java.io.IOException ex) {
                        errors.append(e.getName()).append(": ").append(ex.getMessage()).append("; ");
                    }
                }
                return (errors.length() == 0) ? null : errors.toString();
            }

            @Override
            protected void done() {
                remoteBrowser.refresh();
                try {
                    String err = get();
                    if (err == null) {
                        log("Deleted " + selected.size() + " item(s).");
                    } else {
                        log("Some deletions failed: " + err);
                    }
                } catch (InterruptedException | ExecutionException ex) {
                    log("Delete error: " + rootMessage(ex));
                }
            }
        }.execute();
    }

    private void refreshAll() {
        localBrowser.refresh();
        if (manager.isConnected()) {
            remoteBrowser.refresh();
        }
    }

    /** Common worker completion: refresh the remote view and log a message. */
    private void finish(String successMessage) {
        remoteBrowser.refresh();
        try {
            log(successMessage);
        } catch (RuntimeException e) {
            LOG.debug("Unexpected error after a remote operation", e);
        }
    }

    private boolean requireConnection() {
        if (!manager.isConnected()) {
            log("Not connected to a server.");
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // settings
    // ------------------------------------------------------------------

    private void openSettings() {
        SettingsDialog dlg = new SettingsDialog(ownerFrame(), manager.getSettings());
        AppSettings updated = dlg.showDialog();
        if (updated != null) {
            manager.saveSettings(updated);
            log("Settings saved.");
        }
    }

    // ------------------------------------------------------------------
    // status / log
    // ------------------------------------------------------------------

    private void updateStatus() {
        boolean connected = manager.isConnected();
        SiteProfile active = manager.getActiveProfile();
        connectButton.setEnabled(!connected && selectedProfile() != null);
        disconnectButton.setEnabled(connected);
        uploadButton.setEnabled(connected);
        downloadButton.setEnabled(connected);
        mkdirButton.setEnabled(connected);
        renameButton.setEnabled(connected);
        deleteButton.setEnabled(connected);
        if (connected && active != null) {
            String insecure = active.getProtocol() == Protocol.FTP ? "  \u2022  INSECURE (plain FTP)" : "";
            statusLabel.setText("Connected: " + active.getName()
                    + "  \u2022  " + active.getProtocol()
                    + "  \u2022  " + remoteBrowser.getCurrentPath() + insecure);
        } else {
            statusLabel.setText("Not connected");
        }
    }

    /** Appends a timestamped line to the message log (bounded). */
    public void log(String message) {
        if (message == null) {
            return;
        }
        String line = LocalTime.now().format(CLOCK) + "  " + message + "\n";
        logArea.append(line);
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

    private String promptPassword(String siteName) {
        JPasswordField pf = new JPasswordField();
        int answer = JOptionPane.showConfirmDialog(this, pf,
                "Password for " + siteName, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return null;
        }
        return new String(pf.getPassword());
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
        return (cause.getMessage() != null) ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    /** Releases the session and cancels transfers; called when the host window closes. */
    public void dispose() {
        manager.shutdown();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Routes transfer events from the worker thread onto the EDT to refresh the
     * queue table, logging only terminal outcomes to keep the log readable.
     */
    private final class UiTransferListener implements TransferListener {
        @Override
        public void jobStateChanged(TransferJob job) {
            TransferJob.State state = job.getState();
            SwingUtilities.invokeLater(() -> {
                transferModel.refresh(manager.getTransfers().getQueue());
                if (state == TransferJob.State.COMPLETED) {
                    log("Finished " + job.getDirection() + " '" + job.getDisplayName() + "'");
                } else if (state == TransferJob.State.FAILED) {
                    log("Failed " + job.getDisplayName() + ": " + job.getMessage());
                } else if (state == TransferJob.State.CANCELLED) {
                    log("Cancelled '" + job.getDisplayName() + "'");
                }
            });
        }

        @Override
        public void jobProgress(TransferJob job, long transferredBytes, long totalBytes) {
            SwingUtilities.invokeLater(() -> transferModel.refresh(manager.getTransfers().getQueue()));
        }
    }

    /** Renders a site in the selector as {@code name (PROTOCOL)}. */
    private static final class SiteRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof SiteProfile p) {
                setText(p.getName() + " (" + p.getProtocol().name() + ")");
            } else {
                setText("");
            }
            return this;
        }
    }
}
