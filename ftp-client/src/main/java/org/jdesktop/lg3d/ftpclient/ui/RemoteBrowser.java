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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import org.jdesktop.lg3d.ftpclient.net.RemoteEntry;
import org.jdesktop.lg3d.ftpclient.net.RemotePaths;
import org.jdesktop.lg3d.ftpclient.session.ConnectionManager;

/**
 * The right-hand pane of the client: the remote-directory browser. Because
 * listing a directory is a blocking network call, every navigation runs on a
 * {@link SwingWorker} and only the resulting snapshot is applied on the EDT -
 * the browser never stalls the UI waiting on the server.
 *
 * <p>Double-clicking a directory navigates into it; double-clicking one or more
 * files hands them to the activation callback so the panel can queue downloads.
 * Like {@link LocalBrowser}, this view owns no transfer logic.</p>
 */
public final class RemoteBrowser extends JPanel {

    private final ConnectionManager manager;
    private final JTextField pathField = new JTextField();
    private final RemoteTableModel model = new RemoteTableModel();
    private final JTable table = new JTable(model);
    private Consumer<List<RemoteEntry>> onActivate;
    private Consumer<String> logger;
    private volatile String currentPath = RemotePaths.SEPARATOR;

    /**
     * Creates the browser bound to a session controller. It does not connect or
     * list anything until {@link #navigate(String)} is called.
     *
     * @param manager the connection/session controller
     */
    public RemoteBrowser(ConnectionManager manager) {
        super(new BorderLayout());
        this.manager = manager;
        setBorder(BorderFactory.createTitledBorder("Remote site"));
        add(buildToolbar(), BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(140);
        table.getColumnModel().getColumn(3).setPreferredWidth(100);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    onDoubleClick();
                }
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
        pathField.setText(currentPath);
    }

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(action("Up", "Go to the parent directory", this::goUp));
        bar.add(action("Refresh", "Reload this directory", this::refresh));
        pathField.addActionListener(e -> navigate(pathField.getText()));
        bar.add(pathField);
        bar.add(action("Go", "Open the path in the box", () -> navigate(pathField.getText())));
        return bar;
    }

    private JButton action(String label, String tooltip, Runnable onClick) {
        JButton b = new JButton(label);
        b.setToolTipText(tooltip);
        b.setFocusable(false);
        b.addActionListener(e -> onClick.run());
        return b;
    }

    /**
     * Sets the callback invoked when files (not a directory) are double-clicked.
     *
     * @param handler receives the selected entries to download
     */
    public void setOnActivate(Consumer<List<RemoteEntry>> handler) {
        this.onActivate = handler;
    }

    /**
     * Sets a sink for error/status messages (the panel routes these to its log).
     *
     * @param logger receives human-readable messages
     */
    public void setLogger(Consumer<String> logger) {
        this.logger = logger;
    }

    /** @return the absolute path currently shown. */
    public String getCurrentPath() {
        return currentPath;
    }

    /** @return the selected entries, possibly empty; never {@code null}. */
    public List<RemoteEntry> getSelectedEntries() {
        int[] rows = table.getSelectedRows();
        List<RemoteEntry> entries = model.getEntries();
        List<RemoteEntry> out = new ArrayList<>();
        for (int r : rows) {
            int m = table.convertRowIndexToModel(r);
            if (m >= 0 && m < entries.size()) {
                out.add(entries.get(m));
            }
        }
        return out;
    }

    /** Reloads the current directory. */
    public void refresh() {
        navigate(currentPath);
    }

    /**
     * Lists a remote directory on a worker thread and, on success, adopts it as
     * the current path. A failure leaves the view unchanged and is logged.
     *
     * @param path the absolute directory to open
     */
    public void navigate(String path) {
        String target = RemotePaths.normalize(path);
        new SwingWorker<List<RemoteEntry>, Void>() {
            @Override
            protected List<RemoteEntry> doInBackground() throws Exception {
                return manager.list(target);
            }

            @Override
            protected void done() {
                try {
                    List<RemoteEntry> entries = get();
                    currentPath = target;
                    pathField.setText(target);
                    model.setEntries(entries);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    log("Listing interrupted.");
                } catch (ExecutionException ex) {
                    log("Cannot list " + target + ": " + rootMessage(ex));
                }
            }
        }.execute();
    }

    /** Clears the listing (used after a disconnect). */
    public void clear() {
        model.setEntries(new ArrayList<>());
        currentPath = RemotePaths.SEPARATOR;
        pathField.setText("");
    }

    private void goUp() {
        navigate(RemotePaths.parent(currentPath));
    }

    private void onDoubleClick() {
        List<RemoteEntry> selected = getSelectedEntries();
        if (selected.size() == 1 && selected.get(0).isDirectory()) {
            navigate(RemotePaths.join(currentPath, selected.get(0).getName()));
            return;
        }
        List<RemoteEntry> files = new ArrayList<>();
        for (RemoteEntry e : selected) {
            if (!e.isDirectory()) {
                files.add(e);
            }
        }
        if (!files.isEmpty() && onActivate != null) {
            onActivate.accept(files);
        }
    }

    private void log(String message) {
        if (logger != null) {
            logger.accept(message);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return (cause.getMessage() != null) ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    /** The table model backing the remote listing. */
    private static final class RemoteTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Name", "Size", "Modified", "Permissions"};
        private final List<RemoteEntry> entries = new ArrayList<>();

        void setEntries(List<RemoteEntry> newEntries) {
            entries.clear();
            if (newEntries != null) {
                entries.addAll(newEntries);
            }
            fireTableDataChanged();
        }

        List<RemoteEntry> getEntries() {
            return entries;
        }

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            RemoteEntry e = entries.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return e.isDirectory() ? e.getName() + "/" : e.getName();
                case 1:
                    return e.isDirectory() ? "" : UiFormats.bytes(e.getSize());
                case 2:
                    return UiFormats.timestamp(e.getModifiedMillis());
                case 3:
                    return e.getPermissions();
                default:
                    return "";
            }
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }
    }
}
