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
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
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
        bar.add(new javax.swing.JToolBar.Separator());
        JButton close = toolButton("Close", e -> {
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

        JMenuItem nf = new JMenuItem("New Folder...");
        nf.setEnabled(currentDir != null);
        nf.addActionListener(e -> doNewFolder());
        menu.add(nf);

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
