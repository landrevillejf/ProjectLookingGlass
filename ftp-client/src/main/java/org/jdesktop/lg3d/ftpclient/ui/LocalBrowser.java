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
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;

/**
 * The left-hand pane of the client: a local-directory browser with a path bar
 * and a table of files. Reading a local directory is cheap, so it happens on the
 * EDT; only remote listing needs a worker (see {@link RemoteBrowser}).
 *
 * <p>Double-clicking a directory navigates into it; double-clicking one or more
 * files hands them to the activation callback so the panel can queue uploads.
 * The browser owns no transfer logic - it is a view plus a current-directory
 * cursor.</p>
 */
public final class LocalBrowser extends JPanel {

    private final JTextField pathField = new JTextField();
    private final LocalTableModel model = new LocalTableModel();
    private final JTable table = new JTable(model);
    private File currentDir = new File(System.getProperty("user.home"));
    private Consumer<File[]> onActivate;

    /** Creates the browser rooted at the user's home directory. */
    public LocalBrowser() {
        super(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Local site"));
        add(buildToolbar(), BorderLayout.NORTH);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(140);
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    onDoubleClick();
                }
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);
        refresh();
    }

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(action("Up", "Go to the parent folder", this::goUp));
        bar.add(action("Refresh", "Reload this folder", this::refresh));
        pathField.addActionListener(e -> setDirectory(new File(pathField.getText())));
        bar.add(pathField);
        bar.add(action("Go", "Open the path in the box", () -> setDirectory(new File(pathField.getText()))));
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
     * Sets the activation callback invoked when files (not a directory) are
     * double-clicked.
     *
     * @param handler receives the selected files to upload
     */
    public void setOnActivate(Consumer<File[]> handler) {
        this.onActivate = handler;
    }

    /** @return the directory currently shown. */
    public File getCurrentDirectory() {
        return currentDir;
    }

    /** @return the selected files, possibly empty; never {@code null}. */
    public File[] getSelectedFiles() {
        int[] rows = table.getSelectedRows();
        List<File> files = model.getFiles();
        List<File> out = new ArrayList<>();
        for (int r : rows) {
            int m = table.convertRowIndexToModel(r);
            if (m >= 0 && m < files.size()) {
                out.add(files.get(m));
            }
        }
        return out.toArray(new File[0]);
    }

    /**
     * Points the browser at a directory and reloads. A non-directory or
     * unreadable path is ignored (the view stays where it was).
     *
     * @param dir the directory to show
     */
    public void setDirectory(File dir) {
        if (dir == null) {
            return;
        }
        File target = dir.isDirectory() ? dir : dir.getParentFile();
        if (target == null || !target.canRead()) {
            return;
        }
        currentDir = target;
        refresh();
    }

    /** Reloads the current directory's contents. */
    public void refresh() {
        File[] children = currentDir.listFiles();
        List<File> files = (children == null) ? new ArrayList<>() : new ArrayList<>(Arrays.asList(children));
        // Folders first, then case-insensitive by name, for a familiar order.
        files.sort(Comparator
                .comparing((File f) -> !f.isDirectory())
                .thenComparing(f -> f.getName().toLowerCase()));
        model.setFiles(files);
        pathField.setText(currentDir.getAbsolutePath());
    }

    private void goUp() {
        File parent = currentDir.getParentFile();
        if (parent != null) {
            setDirectory(parent);
        }
    }

    private void onDoubleClick() {
        File[] selected = getSelectedFiles();
        if (selected.length == 1 && selected[0].isDirectory()) {
            setDirectory(selected[0]);
            return;
        }
        List<File> files = new ArrayList<>();
        for (File f : selected) {
            if (f.isFile()) {
                files.add(f);
            }
        }
        if (!files.isEmpty() && onActivate != null) {
            onActivate.accept(files.toArray(new File[0]));
        }
    }

    /** The table model backing the local file list. */
    private static final class LocalTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Name", "Size", "Modified"};
        private final List<File> files = new ArrayList<>();

        void setFiles(List<File> newFiles) {
            files.clear();
            files.addAll(newFiles);
            fireTableDataChanged();
        }

        List<File> getFiles() {
            return files;
        }

        @Override
        public int getRowCount() {
            return files.size();
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
            File f = files.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return f.isDirectory() ? f.getName() + "/" : f.getName();
                case 1:
                    return f.isDirectory() ? "" : UiFormats.bytes(f.length());
                case 2:
                    return UiFormats.timestamp(f.lastModified());
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
