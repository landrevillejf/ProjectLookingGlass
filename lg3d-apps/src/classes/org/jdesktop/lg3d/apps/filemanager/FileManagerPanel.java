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
package org.jdesktop.lg3d.apps.filemanager;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.function.Function;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.filechooser.FileSystemView;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import org.jdesktop.lg3d.apps.mediawriter.MediaWriterEngine;
import org.jdesktop.lg3d.utils.system.Opener;
import org.jdesktop.lg3d.utils.system.ProcessRunner;
import org.jdesktop.lg3d.utils.system.ProcessService;

/**
 * The file manager's Swing UI: a directory {@link JTree} on the left and a
 * {@link JTable} of the current directory on the right, with a toolbar
 * (Back/Forward/Up/Home/Refresh, list/icon view, Close), a clickable
 * breadcrumb, and a status bar.
 *
 * <p>Supports the full set of file operations from {@link FileOperations}:
 * open (xdg-open), open-with, cut/copy/paste, rename, delete-to-trash, new
 * folder and properties; multi-selection; drag-and-drop move onto the tree;
 * keyboard shortcuts; and a background {@link SwingWorker} with a progress
 * dialog for large copy/move operations. Destructive actions confirm first.</p>
 */
public class FileManagerPanel extends JPanel {

    private static final int PANEL_W = 760;
    private static final int PANEL_H = 500;
    private static final int HISTORY_MAX = 50;

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    private static final FileSystemView FSV = FileSystemView.getFileSystemView();

    private final FileTableModel tableModel = new FileTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTree tree;
    private final DefaultTreeModel treeModel;
    private final Path rootPath;
    private final Path home;

    private final Deque<Path> back = new ArrayDeque<>();
    private final Deque<Path> forward = new ArrayDeque<>();
    private final List<Path> clipboard = new ArrayList<>();
    private final List<TableColumn> hiddenColumns = new ArrayList<>();

    private Path currentDir;
    private boolean clipboardCut;
    private boolean iconView;
    private boolean suppressTreeNav;
    private Runnable onClose;

    private final JPanel breadcrumbBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton viewButton = new JButton("Icon view");
    private final JButton shareButton = new JButton("Share");

    /** Reused for "Burn to Disc": the guarded engine behind the Media Writer. */
    private final MediaWriterEngine burnEngine = new MediaWriterEngine();
    /** The currently running HTTP folder share, or null when not sharing. */
    private ShareOperations.Share activeShare;

    public FileManagerPanel(Path initial) {
        super(new BorderLayout());
        setPreferredSize(new Dimension(PANEL_W, PANEL_H));
        setBackground(new Color(238, 240, 244));

        home = Paths.get(System.getProperty("user.home"));
        File[] roots = File.listRoots();
        rootPath = (roots != null && roots.length > 0) ? roots[0].toPath() : Paths.get("/");

        FileNode rootNode = new FileNode(rootPath);
        treeModel = new DefaultTreeModel(rootNode);
        tree = new JTree(treeModel);

        buildTable();
        buildTree();

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(tree), new JScrollPane(table));
        split.setDividerLocation(190);
        split.setResizeWeight(0.28);
        split.setBorder(BorderFactory.createEmptyBorder());

        add(buildToolbar(), BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        populate(rootNode);

        Path start = (initial != null && Files.isDirectory(initial)) ? initial : home;
        show(start);
        expandTreeTo(start);
    }

    /** Sets the callback invoked when the user presses Close. */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    // ------------------------------------------------------------------
    // UI construction

    private void buildTable() {
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowHeight(22);
        table.setFillsViewportHeight(true);
        table.setDragEnabled(true);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.getTableHeader().setReorderingAllowed(false);

        table.setDefaultRenderer(Object.class, new NameRenderer());
        applyColumnWidths();

        table.setTransferHandler(new ExportHandler());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateStatus();
            }
        });

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }
            @Override
            public void mouseClicked(MouseEvent e) {
                // Double-click opens the item under the cursor: descends into a
                // folder, or opens a file via xdg-open (same as Enter / Open).
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 2) {
                    int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        if (!table.isRowSelected(row)) {
                            table.setRowSelectionInterval(row, row);
                        }
                        doOpen();
                    }
                }
            }
            private void maybePopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && !table.isRowSelected(row)) {
                    table.setRowSelectionInterval(row, row);
                }
                if (row >= 0) {
                    showContextMenu(e.getComponent(), e.getX(), e.getY());
                }
            }
        });

        table.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handleTableKey(e);
            }
        });

        // Capture the Size/Type/Modified columns so icon view can hide them.
        for (int i = 1; i < table.getColumnCount(); i++) {
            hiddenColumns.add(table.getColumnModel().getColumn(i));
        }
    }

    private void applyColumnWidths() {
        if (table.getColumnCount() < 4) {
            return;
        }
        table.getColumnModel().getColumn(0).setPreferredWidth(280);
        // The remaining columns may have been removed for icon view; guard.
        for (int i = 1; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(i == 3 ? 150 : 90);
        }
    }

    private void buildTree() {
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setDropMode(javax.swing.DropMode.ON);
        tree.setTransferHandler(new TreeImportHandler());
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent e) {
                FileNode node = (FileNode) e.getPath().getLastPathComponent();
                if (!node.loaded) {
                    populate(node);
                }
            }
            @Override
            public void treeWillCollapse(TreeExpansionEvent e) {
                // allowed
            }
        });
        tree.addTreeSelectionListener(e -> {
            if (suppressTreeNav) {
                return;
            }
            TreePath p = tree.getSelectionPath();
            if (p != null) {
                FileNode node = (FileNode) p.getLastPathComponent();
                if (Files.isDirectory(node.path) && !node.path.equals(currentDir)) {
                    pushHistory(currentDir);
                    show(node.path);
                }
            }
        });
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(200, 205, 214)));
        bar.setBackground(new Color(246, 247, 250));

        bar.add(toolButton("Back", e -> goBack()));
        bar.add(toolButton("Forward", e -> goForward()));
        bar.add(toolButton("Up", e -> goUp()));
        bar.add(toolButton("Home", e -> navigateTo(home)));
        bar.add(toolButton("Refresh", e -> refresh()));
        bar.add(new javax.swing.JToolBar.Separator());
        viewButton.addActionListener(e -> toggleView());
        bar.add(viewButton);
        bar.add(toolButton("New Folder", e -> doNewFolder()));
        shareButton.setFocusable(false);
        shareButton.setMargin(new java.awt.Insets(2, 8, 2, 8));
        shareButton.addActionListener(e -> toggleShare());
        bar.add(shareButton);
        bar.add(toolButton("Mounts", e -> showMountsDialog()));
        bar.add(new javax.swing.JToolBar.Separator());
        JButton close = toolButton("Close", e -> {
            stopShare();
            if (onClose != null) {
                onClose.run();
            }
        });
        bar.add(close);
        return bar;
    }

    private JButton toolButton(String label, java.awt.event.ActionListener al) {
        JButton b = new JButton(label);
        b.setFocusable(false);
        b.setMargin(new java.awt.Insets(2, 8, 2, 8));
        b.addActionListener(al);
        return b;
    }

    private JPanel buildStatusBar() {
        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(new Color(246, 247, 250));
        breadcrumbBar.setOpaque(false);
        south.add(breadcrumbBar, BorderLayout.NORTH);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        statusLabel.setForeground(new Color(80, 90, 105));
        south.add(statusLabel, BorderLayout.SOUTH);
        return south;
    }

    // ------------------------------------------------------------------
    // Navigation

    /** Updates the table/breadcrumb/status to {@code dir} without touching history. */
    private void show(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        currentDir = dir;
        tableModel.setDirectory(dir);
        applyColumnWidths();
        rebuildBreadcrumb(dir);
        updateStatus();
        table.clearSelection();
        if (tableModel.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    /** History-recording navigation (also expands the tree to the target). */
    private void navigateTo(Path dir) {
        if (dir == null || !Files.isDirectory(dir) || dir.equals(currentDir)) {
            return;
        }
        pushHistory(currentDir);
        show(dir);
        expandTreeTo(dir);
    }

    private void pushHistory(Path prev) {
        if (prev == null) {
            return;
        }
        back.push(prev);
        forward.clear();
        while (back.size() > HISTORY_MAX) {
            back.removeLast();
        }
    }

    private void goBack() {
        if (back.isEmpty()) {
            return;
        }
        Path p = back.pop();
        forward.push(currentDir);
        show(p);
        expandTreeTo(p);
    }

    private void goForward() {
        if (forward.isEmpty()) {
            return;
        }
        Path p = forward.pop();
        back.push(currentDir);
        show(p);
        expandTreeTo(p);
    }

    private void goUp() {
        if (currentDir == null) {
            return;
        }
        Path parent = currentDir.getParent();
        if (parent != null) {
            navigateTo(parent);
        }
    }

    private void refresh() {
        if (currentDir != null) {
            tableModel.reload();
            updateStatus();
        }
    }

    private void rebuildBreadcrumb(Path dir) {
        breadcrumbBar.removeAll();
        List<Path> chain = new ArrayList<>();
        Path p = dir;
        while (p != null) {
            chain.add(0, p);
            p = p.getParent();
        }
        if (chain.isEmpty()) {
            chain.add(dir);
        }
        for (final Path seg : chain) {
            String label = (seg.getNameCount() == 0 || seg.getFileName() == null)
                    ? "/" : seg.getFileName().toString();
            JButton b = new JButton(label);
            b.setFocusable(false);
            b.setContentAreaFilled(false);
            b.setBorderPainted(false);
            b.setMargin(new java.awt.Insets(0, 2, 0, 2));
            b.setForeground(new Color(50, 90, 160));
            b.addActionListener(e -> navigateTo(seg));
            breadcrumbBar.add(b);
            breadcrumbBar.add(new JLabel("\u203A"));
        }
        breadcrumbBar.revalidate();
        breadcrumbBar.repaint();
    }

    private void updateStatus() {
        int count = tableModel.getRowCount();
        StringBuilder sb = new StringBuilder();
        sb.append(count).append(count == 1 ? " item" : " items");
        List<Path> sel = selectedPaths();
        if (!sel.isEmpty()) {
            long bytes = FileOperations.totalSize(sel);
            sb.append("   |   ").append(sel.size()).append(" selected (")
                    .append(ProcessService.formatBytes(bytes)).append(")");
        }
        statusLabel.setText(sb.toString());
    }

    private void expandTreeTo(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            FileNode root = (FileNode) treeModel.getRoot();
            if (!dir.startsWith(root.path)) {
                return;
            }
            suppressTreeNav = true;
            TreePath tp = new TreePath(root);
            Path cur = root.path;
            for (Path seg : root.path.relativize(dir)) {
                cur = cur.resolve(seg);
                FileNode parentNode = (FileNode) tp.getLastPathComponent();
                if (!parentNode.loaded) {
                    populate(parentNode);
                }
                FileNode child = findChild(parentNode, cur);
                if (child == null) {
                    break;
                }
                tree.expandPath(tp);
                tp = tp.pathByAddingChild(child);
            }
            tree.expandPath(tp.getParentPath() != null ? tp.getParentPath() : tp);
            tree.setSelectionPath(tp);
            tree.scrollPathToVisible(tp);
        } catch (RuntimeException ex) {
            // best effort; the table is the source of truth
        } finally {
            suppressTreeNav = false;
        }
    }

    // ------------------------------------------------------------------
    // Tree model helpers

    private void populate(FileNode node) {
        node.removeAllChildren();
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(node.path)) {
            for (Path child : ds) {
                if (Files.isDirectory(child)) {
                    dirs.add(child);
                }
            }
        } catch (IOException | RuntimeException e) {
            // unreadable directory: show as empty
        }
        dirs.sort(Comparator.comparing(FileManagerPanel::fileName,
                String.CASE_INSENSITIVE_ORDER));
        for (Path d : dirs) {
            node.add(new FileNode(d));
        }
        node.loaded = true;
        treeModel.reload(node);
    }

    private FileNode findChild(FileNode parent, Path path) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            FileNode c = (FileNode) parent.getChildAt(i);
            if (c.path.equals(path)) {
                return c;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Selection helpers

    private List<Path> selectedPaths() {
        List<Path> out = new ArrayList<>();
        for (int r : table.getSelectedRows()) {
            Path p = tableModel.getFileAt(table.convertRowIndexToModel(r));
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    private Path singleSelection() {
        int r = table.getSelectedRow();
        if (r < 0) {
            return null;
        }
        return tableModel.getFileAt(table.convertRowIndexToModel(r));
    }

    // ------------------------------------------------------------------
    // Operations

    private void doOpen() {
        Path p = singleSelection();
        if (p == null) {
            return;
        }
        if (Files.isDirectory(p)) {
            navigateTo(p);
        } else if (!Opener.open(p)) {
            showError("Could not open " + fileName(p) + ".");
        }
    }

    private void doOpenWith() {
        Path p = singleSelection();
        if (p == null) {
            return;
        }
        String cmd = (String) JOptionPane.showInputDialog(this,
                "Open '" + fileName(p) + "' with command:", "Open With",
                JOptionPane.QUESTION_MESSAGE, null, null, "xdg-open");
        if (cmd == null || cmd.isBlank()) {
            return;
        }
        String quoted = "'" + p.toAbsolutePath().toString().replace("'", "'\\''") + "'";
        ProcessRunner.run("/bin/sh", "-c", cmd + " " + quoted);
    }

    private void doRename() {
        Path p = singleSelection();
        if (p == null) {
            return;
        }
        String name = (String) JOptionPane.showInputDialog(this,
                "New name:", "Rename", JOptionPane.QUESTION_MESSAGE,
                null, null, fileName(p));
        if (name == null || name.isBlank() || name.equals(fileName(p))) {
            return;
        }
        if (!FileOperations.rename(p, name)) {
            showError("Could not rename '" + fileName(p) + "'. The name may already exist.");
        }
        refresh();
    }

    private void doNewFolder() {
        if (currentDir == null) {
            return;
        }
        String name = (String) JOptionPane.showInputDialog(this,
                "New folder name:", "New Folder", JOptionPane.QUESTION_MESSAGE,
                null, null, "New Folder");
        if (name == null || name.isBlank()) {
            return;
        }
        if (!FileOperations.newFolder(currentDir, name)) {
            showError("Could not create folder '" + name + "'.");
        }
        refresh();
    }

    private void doDelete() {
        List<Path> sel = selectedPaths();
        if (sel.isEmpty()) {
            return;
        }
        String msg = (sel.size() == 1)
                ? "Move '" + fileName(sel.get(0)) + "' to the trash?"
                : "Move " + sel.size() + " items to the trash?";
        int r = JOptionPane.showConfirmDialog(this, msg, "Delete",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        if (!FileOperations.trash(sel)) {
            showError("Some items could not be moved to the trash.");
        }
        refresh();
    }

    private void doCopy() {
        clipboard.clear();
        clipboard.addAll(selectedPaths());
        clipboardCut = false;
        updateStatus();
    }

    private void doCut() {
        clipboard.clear();
        clipboard.addAll(selectedPaths());
        clipboardCut = true;
        updateStatus();
    }

    private void doPaste() {
        if (clipboard.isEmpty() || currentDir == null) {
            return;
        }
        List<Path> srcs = new ArrayList<>(clipboard);
        boolean move = clipboardCut;
        if (move) {
            clipboard.clear();
            clipboardCut = false;
        }
        runFileOperation(move ? "Move" : "Copy", srcs, currentDir, move);
    }

    private void doProperties() {
        Path p = singleSelection();
        if (p == null) {
            return;
        }
        boolean dir = Files.isDirectory(p);
        long size = FileOperations.totalSize(List.of(p));
        StringBuilder sb = new StringBuilder();
        sb.append("Name:      ").append(fileName(p)).append('\n');
        sb.append("Type:      ").append(dir ? "Folder" : fileType(p)).append('\n');
        sb.append("Path:      ").append(p.toAbsolutePath()).append('\n');
        sb.append("Size:      ").append(ProcessService.formatBytes(size)).append('\n');
        try {
            sb.append("Modified:  ").append(DATE.format(Instant.ofEpochMilli(
                    Files.getLastModifiedTime(p).toMillis()))).append('\n');
        } catch (IOException | RuntimeException e) {
            sb.append("Modified:  (unknown)\n");
        }
        sb.append("Readable:  ").append(Files.isReadable(p)).append('\n');
        sb.append("Writable:  ").append(Files.isWritable(p)).append('\n');
        sb.append("Executable:").append(Files.isExecutable(p)).append('\n');

        JLabel label = new JLabel(sb.toString());
        label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        label.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JOptionPane.showMessageDialog(this, label, fileName(p) + " Properties",
                JOptionPane.INFORMATION_MESSAGE);
    }

    /** Runs copy/move on a background thread with a progress dialog. */
    private void runFileOperation(String verb, List<Path> sources, Path dest, boolean move) {
        if (sources.isEmpty() || dest == null) {
            return;
        }
        final long total = Math.max(1L, FileOperations.totalSize(sources));

        final JDialog dialog = new JDialog();
        dialog.setTitle(verb);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(new JLabel(verb + " " + sources.size() + " item(s)..."),
                BorderLayout.NORTH);
        final JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        content.add(bar, BorderLayout.CENTER);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(this);

        SwingWorker<Boolean, Integer> worker = new SwingWorker<Boolean, Integer>() {
            @Override
            protected Boolean doInBackground() {
                LongConsumer cb = bytes -> publish(
                        Integer.valueOf((int) Math.min(100, bytes * 100 / total)));
                return move ? FileOperations.move(sources, dest, cb)
                        : FileOperations.copy(sources, dest, cb);
            }
            @Override
            protected void process(List<Integer> chunks) {
                bar.setValue(chunks.get(chunks.size() - 1).intValue());
            }
            @Override
            protected void done() {
                bar.setValue(100);
                dialog.setVisible(false);
                dialog.dispose();
                boolean ok;
                try {
                    ok = Boolean.TRUE.equals(get());
                } catch (Exception ex) {
                    ok = false;
                }
                refresh();
                if (!ok) {
                    showError(verb + " completed with errors (permission or space?).");
                }
            }
        };
        worker.execute();
        dialog.setVisible(true);
    }

    // ------------------------------------------------------------------
    // Compression, extraction, sharing, mounts and burning

    /**
     * Runs an arbitrary byte-reporting operation on a background thread with a
     * progress dialog (the archive counterpart of {@link #runFileOperation}).
     */
    private void runProgressOperation(String verb, String failMessage, long totalBytes,
            Function<LongConsumer, Boolean> work) {
        final long total = Math.max(1L, totalBytes);
        final JDialog dialog = new JDialog();
        dialog.setTitle(verb);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(new JLabel(verb + "..."), BorderLayout.NORTH);
        final JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        content.add(bar, BorderLayout.CENTER);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(this);

        SwingWorker<Boolean, Integer> worker = new SwingWorker<Boolean, Integer>() {
            @Override
            protected Boolean doInBackground() {
                LongConsumer cb = bytes -> publish(
                        Integer.valueOf((int) Math.min(100, bytes * 100 / total)));
                return Boolean.TRUE.equals(work.apply(cb));
            }
            @Override
            protected void process(List<Integer> chunks) {
                bar.setValue(chunks.get(chunks.size() - 1).intValue());
            }
            @Override
            protected void done() {
                bar.setValue(100);
                dialog.setVisible(false);
                dialog.dispose();
                boolean ok;
                try {
                    ok = Boolean.TRUE.equals(get());
                } catch (Exception ex) {
                    ok = false;
                }
                refresh();
                if (!ok) {
                    showError(failMessage);
                }
            }
        };
        worker.execute();
        dialog.setVisible(true);
    }

    /** Compresses the selection into a new {@code .zip} in the current folder. */
    private void doCompress() {
        final List<Path> sel = selectedPaths();
        if (sel.isEmpty() || currentDir == null) {
            return;
        }
        String suggested = ArchiveOperations.defaultArchiveName(sel.get(0));
        String name = (String) JOptionPane.showInputDialog(this,
                "Archive name:", "Compress", JOptionPane.QUESTION_MESSAGE,
                null, null, suggested);
        if (name == null || name.isBlank()) {
            return;
        }
        String trimmed = name.trim();
        if (!trimmed.toLowerCase().endsWith(".zip")) {
            trimmed = trimmed + ".zip";
        }
        final Path dest = currentDir.resolve(trimmed);
        final List<Path> sources = new ArrayList<>(sel);
        long total = FileOperations.totalSize(sources);
        runProgressOperation("Compress", "Could not create the archive.", total,
                cb -> ArchiveOperations.createZip(sources, dest, cb));
    }

    /** Extracts a single selected archive into a folder named after it. */
    private void doExtract() {
        final Path p = singleSelection();
        if (p == null || currentDir == null || !ArchiveOperations.isArchive(p)) {
            return;
        }
        final Path dest = currentDir.resolve(ArchiveOperations.baseName(p));
        long total = FileOperations.totalSize(List.of(p));
        runProgressOperation("Extract", "Could not extract the archive.", total,
                cb -> ArchiveOperations.extract(p, dest, cb));
    }

    /** Starts (or stops) an HTTP share of the current folder over the LAN. */
    private void toggleShare() {
        if (activeShare != null) {
            stopShare();
            return;
        }
        if (currentDir == null) {
            showError("There is no folder to share.");
            return;
        }
        try {
            activeShare = ShareOperations.start(currentDir);
            shareButton.setText("Stop Share");
            String url = activeShare.getUrl();
            statusLabel.setText("Sharing " + fileName(currentDir) + " at " + url);
            JOptionPane.showMessageDialog(this,
                    "Folder shared over the network (read-only):\n\n" + url
                    + "\n\nAnyone on your local network can browse and download it\n"
                    + "until you press Stop Share.",
                    "Share Folder", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException | RuntimeException ex) {
            activeShare = null;
            showError("Could not share the folder: " + ex.getMessage());
        }
    }

    /** Stops any running share and restores the toolbar button. */
    private void stopShare() {
        if (activeShare != null) {
            activeShare.stop();
            activeShare = null;
        }
        shareButton.setText("Share");
        updateStatus();
    }

    /** Opens a dialog listing mountable volumes with Mount / Unmount actions. */
    private void showMountsDialog() {
        final JDialog dialog = new JDialog();
        dialog.setTitle("Mounts / Volumes");
        final DefaultListModel<VolumeOperations.Volume> model = new DefaultListModel<>();
        final JList<VolumeOperations.Volume> list = new JList<>(model);
        final JLabel header = new JLabel("Removable and mountable volumes:");

        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(header, BorderLayout.NORTH);
        content.add(new JScrollPane(list), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        JButton mount = new JButton("Mount");
        JButton unmount = new JButton("Unmount");
        JButton reload = new JButton("Refresh");
        JButton close = new JButton("Close");
        buttons.add(mount);
        buttons.add(unmount);
        buttons.add(reload);
        buttons.add(close);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);

        final Runnable load = () -> {
            model.clear();
            List<VolumeOperations.Volume> vols = VolumeOperations.listVolumes();
            for (VolumeOperations.Volume v : vols) {
                model.addElement(v);
            }
            header.setText(vols.isEmpty()
                    ? "No mountable volumes detected (is lsblk installed?)."
                    : "Removable and mountable volumes:");
        };

        mount.addActionListener(e -> {
            VolumeOperations.Volume v = list.getSelectedValue();
            if (v == null) {
                return;
            }
            if (v.isMounted()) {
                JOptionPane.showMessageDialog(dialog, v.path + " is already mounted at "
                        + v.mountPoint + ".", "Mount", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            VolumeOperations.OpResult r = VolumeOperations.mount(v.path, v.label);
            showMountResult(dialog, "Mount", r);
            load.run();
            refresh();
        });
        unmount.addActionListener(e -> {
            VolumeOperations.Volume v = list.getSelectedValue();
            if (v == null) {
                return;
            }
            if (!v.isMounted()) {
                JOptionPane.showMessageDialog(dialog, v.path + " is not mounted.",
                        "Unmount", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            VolumeOperations.OpResult r = VolumeOperations.unmount(v.path, v.mountPoint);
            showMountResult(dialog, "Unmount", r);
            load.run();
            refresh();
        });
        reload.addActionListener(e -> load.run());
        close.addActionListener(e -> dialog.dispose());

        load.run();
        dialog.setSize(480, 320);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void showMountResult(java.awt.Window owner, String title,
            VolumeOperations.OpResult r) {
        JOptionPane.showMessageDialog(owner,
                r.isSuccess() ? r.getMessage() : (title + " failed: " + r.getMessage()),
                title, r.isSuccess()
                        ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
    }

    /**
     * Burns the selection to an optical drive (or just builds an ISO when no
     * drive is chosen), reusing the guarded {@link MediaWriterEngine} behind the
     * Media Writer app so the destructive device-write safety checks are shared.
     */
    private void doBurn() {
        final List<Path> sel = selectedPaths();
        if (sel.isEmpty()) {
            showError("Select the files or folder to burn.");
            return;
        }
        final List<MediaWriterEngine.DeviceInfo> optical = new ArrayList<>();
        try {
            for (MediaWriterEngine.DeviceInfo d : burnEngine.detectDevices()) {
                if (d.kind == MediaWriterEngine.DeviceKind.OPTICAL) {
                    optical.add(d);
                }
            }
        } catch (RuntimeException ex) {
            // detection is best effort; fall back to ISO-only
        }

        List<Object> choices = new ArrayList<>();
        for (MediaWriterEngine.DeviceInfo d : optical) {
            choices.add(d.describe());
        }
        choices.add("Create ISO image only (no burning)");
        Object pick = JOptionPane.showInputDialog(this,
                optical.isEmpty()
                        ? "No optical drive was detected. Create an ISO image instead?"
                        : "Burn target:",
                "Burn to Disc", JOptionPane.QUESTION_MESSAGE, null,
                choices.toArray(), choices.get(choices.size() - 1));
        if (pick == null) {
            return;
        }
        int idx = choices.indexOf(pick);
        MediaWriterEngine.DeviceInfo drive =
                (idx >= 0 && idx < optical.size()) ? optical.get(idx) : null;
        runBurn(drive, new ArrayList<>(sel));
    }

    private void runBurn(final MediaWriterEngine.DeviceInfo drive, final List<Path> sources) {
        final boolean keepIso = (drive == null);
        final File outIso;
        try {
            if (keepIso) {
                Path base = (currentDir != null) ? currentDir : home;
                outIso = base.resolve(fileName(sources.get(0)) + ".iso").toFile();
            } else {
                outIso = File.createTempFile("lg3d-burn-", ".iso");
            }
        } catch (IOException | RuntimeException ex) {
            showError("Cannot prepare the ISO output: " + ex.getMessage());
            return;
        }

        final JDialog dialog = new JDialog();
        dialog.setTitle("Burn to Disc");
        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        final JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        final JTextArea log = new JTextArea(10, 46);
        log.setEditable(false);
        content.add(bar, BorderLayout.NORTH);
        content.add(new JScrollPane(log), BorderLayout.CENTER);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        south.add(cancel);
        content.add(south, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setLocationRelativeTo(this);

        final java.util.concurrent.atomic.AtomicBoolean ok =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        burnEngine.resetCancel();
        cancel.addActionListener(e -> burnEngine.cancel());

        final MediaWriterEngine.ProgressHandler handler =
                new MediaWriterEngine.ProgressHandler() {
            @Override
            public void onLog(String line) {
                SwingUtilities.invokeLater(() -> {
                    log.append(line + "\n");
                    log.setCaretPosition(log.getDocument().getLength());
                });
            }
            @Override
            public void onProgress(double fraction, String stage) {
                SwingUtilities.invokeLater(() -> {
                    if (fraction < 0) {
                        bar.setIndeterminate(true);
                    } else {
                        bar.setIndeterminate(false);
                        bar.setValue((int) Math.round(fraction * 100));
                    }
                    bar.setString(stage);
                });
            }
            @Override
            public void onFinished(boolean success, String message) {
                ok.set(success);
                SwingUtilities.invokeLater(() -> log.append(
                        (success ? "\u2713 " : "\u2717 ") + message + "\n"));
            }
        };

        SwingWorker<Boolean, Void> worker = new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                Path staged = null;
                try {
                    File folder;
                    if (sources.size() == 1 && Files.isDirectory(sources.get(0))) {
                        folder = sources.get(0).toFile();
                    } else {
                        staged = Files.createTempDirectory("lg3d-burn-");
                        FileOperations.copy(sources, staged, null);
                        folder = staged.toFile();
                    }
                    burnEngine.createDataDisc(folder, outIso, "LG3D_DATA",
                            drive, null, false, handler);
                    return ok.get();
                } catch (IOException | RuntimeException ex) {
                    handler.onFinished(false, String.valueOf(ex.getMessage()));
                    return false;
                } finally {
                    if (staged != null) {
                        deleteRecursively(staged);
                    }
                }
            }
            @Override
            protected void done() {
                bar.setIndeterminate(false);
                dialog.setVisible(false);
                dialog.dispose();
                if (keepIso) {
                    refresh();
                    JOptionPane.showMessageDialog(FileManagerPanel.this,
                            "ISO image created:\n" + outIso.getAbsolutePath(),
                            "Burn to Disc", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    if (!outIso.delete()) {
                        outIso.deleteOnExit();
                    }
                    if (!ok.get()) {
                        showError("Burn did not complete. See the log for details.");
                    }
                }
            }
        };
        worker.execute();
        dialog.setVisible(true);
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best effort cleanup of the staging folder
                        }
                    });
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }

    // ------------------------------------------------------------------
    // Context menu + keyboard

    private void showContextMenu(Component invoker, int x, int y) {
        JPopupMenu menu = new JPopupMenu();
        List<Path> sel = selectedPaths();
        boolean hasSel = !sel.isEmpty();
        boolean single = sel.size() == 1;

        JMenuItem open = new JMenuItem("Open");
        open.setEnabled(single);
        open.addActionListener(e -> doOpen());
        menu.add(open);

        JMenuItem openWith = new JMenuItem("Open With...");
        openWith.setEnabled(single);
        openWith.addActionListener(e -> doOpenWith());
        menu.add(openWith);

        menu.addSeparator();

        JMenuItem cut = new JMenuItem("Cut");
        cut.setEnabled(hasSel);
        cut.addActionListener(e -> doCut());
        menu.add(cut);

        JMenuItem copy = new JMenuItem("Copy");
        copy.setEnabled(hasSel);
        copy.addActionListener(e -> doCopy());
        menu.add(copy);

        JMenuItem paste = new JMenuItem("Paste");
        paste.setEnabled(!clipboard.isEmpty() && currentDir != null);
        paste.addActionListener(e -> doPaste());
        menu.add(paste);

        menu.addSeparator();

        JMenuItem rename = new JMenuItem("Rename...");
        rename.setEnabled(single);
        rename.addActionListener(e -> doRename());
        menu.add(rename);

        JMenuItem del = new JMenuItem("Delete");
        del.setEnabled(hasSel);
        del.addActionListener(e -> doDelete());
        menu.add(del);

        menu.addSeparator();

        JMenuItem compress = new JMenuItem("Compress...");
        compress.setEnabled(hasSel && currentDir != null);
        compress.addActionListener(e -> doCompress());
        menu.add(compress);

        JMenuItem extract = new JMenuItem("Extract");
        extract.setEnabled(single && ArchiveOperations.isArchive(sel.get(0)));
        extract.addActionListener(e -> doExtract());
        menu.add(extract);

        JMenuItem burn = new JMenuItem("Burn to Disc...");
        burn.setEnabled(hasSel);
        burn.addActionListener(e -> doBurn());
        menu.add(burn);

        menu.addSeparator();

        JMenuItem nf = new JMenuItem("New Folder...");
        nf.setEnabled(currentDir != null);
        nf.addActionListener(e -> doNewFolder());
        menu.add(nf);

        JMenuItem share = new JMenuItem(
                (activeShare == null) ? "Share This Folder" : "Stop Sharing");
        share.setEnabled(currentDir != null || activeShare != null);
        share.addActionListener(e -> toggleShare());
        menu.add(share);

        JMenuItem mounts = new JMenuItem("Mounts / Volumes...");
        mounts.addActionListener(e -> showMountsDialog());
        menu.add(mounts);

        JMenuItem props = new JMenuItem("Properties");
        props.setEnabled(single);
        props.addActionListener(e -> doProperties());
        menu.add(props);

        menu.show(invoker, x, y);
    }

    private void handleTableKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean alt = e.isAltDown();
        if (code == KeyEvent.VK_ENTER && !alt) {
            doOpen();
            e.consume();
        } else if (code == KeyEvent.VK_DELETE) {
            doDelete();
            e.consume();
        } else if (code == KeyEvent.VK_F2) {
            doRename();
            e.consume();
        } else if (code == KeyEvent.VK_UP && alt) {
            goUp();
            e.consume();
        } else if (code == KeyEvent.VK_BACK_SPACE) {
            goBack();
            e.consume();
        } else if (e.isControlDown() && code == KeyEvent.VK_C) {
            doCopy();
            e.consume();
        } else if (e.isControlDown() && code == KeyEvent.VK_X) {
            doCut();
            e.consume();
        } else if (e.isControlDown() && code == KeyEvent.VK_V) {
            doPaste();
            e.consume();
        }
    }

    private void toggleView() {
        iconView = !iconView;
        if (iconView) {
            viewButton.setText("List view");
            table.setRowHeight(84);
            for (TableColumn col : hiddenColumns) {
                table.getColumnModel().removeColumn(col);
            }
        } else {
            viewButton.setText("Icon view");
            table.setRowHeight(22);
            for (TableColumn col : hiddenColumns) {
                table.getColumnModel().addColumn(col);
            }
            applyColumnWidths();
        }
        table.repaint();
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "File Manager",
                JOptionPane.WARNING_MESSAGE);
    }

    // ------------------------------------------------------------------
    // Drag and drop

    /** Exports the table selection as a javaFileList (for drag onto the tree). */
    private final class ExportHandler extends TransferHandler {
        @Override
        public int getSourceActions(JComponent c) {
            return COPY_OR_MOVE;
        }
        @Override
        protected Transferable createTransferable(JComponent c) {
            final List<Path> sel = selectedPaths();
            if (sel.isEmpty()) {
                return null;
            }
            final List<File> files = new ArrayList<>();
            for (Path p : sel) {
                files.add(p.toFile());
            }
            return new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[] { DataFlavor.javaFileListFlavor };
                }
                @Override
                public boolean isDataFlavorSupported(DataFlavor flavor) {
                    return DataFlavor.javaFileListFlavor.equals(flavor);
                }
                @Override
                public Object getTransferData(DataFlavor flavor) {
                    return files;
                }
            };
        }
    }

    /** Accepts a dropped file list on a tree folder and moves the files there. */
    private final class TreeImportHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }
        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport support) {
            try {
                List<File> files = (List<File>) support.getTransferable()
                        .getTransferData(DataFlavor.javaFileListFlavor);
                Path dest;
                if (support.isDrop()) {
                    JTree.DropLocation dl = (JTree.DropLocation) support.getDropLocation();
                    TreePath tp = dl.getPath();
                    dest = ((FileNode) tp.getLastPathComponent()).path;
                } else {
                    TreePath tp = tree.getSelectionPath();
                    if (tp == null) {
                        return false;
                    }
                    dest = ((FileNode) tp.getLastPathComponent()).path;
                }
                if (dest == null || !Files.isDirectory(dest)) {
                    return false;
                }
                List<Path> srcs = new ArrayList<>();
                for (File f : files) {
                    Path p = f.toPath();
                    if (!dest.equals(p.getParent())) {
                        srcs.add(p);
                    }
                }
                if (!srcs.isEmpty()) {
                    runFileOperation("Move", srcs, dest, true);
                }
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------
    // Renderer + tree node

    /** Renders the Name column with a system icon; centered in icon view. */
    private final class NameRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(
                    t, value, isSelected, hasFocus, row, column);
            Path p = tableModel.getFileAt(t.convertRowIndexToModel(row));
            Icon icon = null;
            if (p != null) {
                try {
                    icon = FSV.getSystemIcon(p.toFile());
                } catch (RuntimeException ex) {
                    icon = null;
                }
            }
            label.setIcon(icon);
            if (iconView) {
                label.setHorizontalAlignment(SwingConstants.CENTER);
                label.setVerticalTextPosition(SwingConstants.BOTTOM);
                label.setHorizontalTextPosition(SwingConstants.CENTER);
            } else {
                label.setHorizontalAlignment(SwingConstants.LEFT);
                label.setVerticalTextPosition(SwingConstants.CENTER);
                label.setHorizontalTextPosition(SwingConstants.RIGHT);
            }
            if (isSelected) {
                label.setBackground(new Color(70, 130, 210));
                label.setForeground(Color.WHITE);
            } else if (row % 2 == 0) {
                label.setBackground(Color.WHITE);
                label.setForeground(new Color(30, 36, 48));
            } else {
                label.setBackground(new Color(245, 247, 250));
                label.setForeground(new Color(30, 36, 48));
            }
            label.setOpaque(true);
            return label;
        }
    }

    /** A lazily-populated directory node in the tree. */
    private static final class FileNode extends DefaultMutableTreeNode {
        final Path path;
        boolean loaded;

        FileNode(Path path) {
            super(path);
            this.path = path;
        }

        @Override
        public String toString() {
            return fileName(path);
        }

        @Override
        public boolean isLeaf() {
            return !Files.isDirectory(path);
        }
    }

    // ------------------------------------------------------------------

    private static String fileName(Path p) {
        Path n = p.getFileName();
        return (n != null) ? n.toString() : p.toString();
    }

    private static String fileType(Path p) {
        String n = fileName(p);
        int dot = n.lastIndexOf('.');
        if (dot > 0 && dot < n.length() - 1) {
            return n.substring(dot + 1).toUpperCase() + " file";
        }
        return "File";
    }
}
