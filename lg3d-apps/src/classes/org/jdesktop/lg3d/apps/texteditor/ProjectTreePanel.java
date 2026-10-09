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
package org.jdesktop.lg3d.apps.texteditor;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

/**
 * The editor's west project panel: a lazily-loaded file tree in the spirit of
 * a project manager. It shows the directory of the document being edited
 * &mdash; walked up to the enclosing <em>project root</em> when one is
 * detectable (see {@link #projectRootFor}) &mdash; and double-clicking a file
 * opens it in the editor through the {@code onFileChosen} callback the panel
 * wires to {@link AdvancedTextEditorPanel#openPath}.
 *
 * <p>Children are read from disk on first expansion, so an enormous directory
 * never blocks the EDT wholesale; directories sort before files, both
 * alphabetically, and hidden entries are skipped. All methods must be called
 * on the EDT.</p>
 */
final class ProjectTreePanel extends JPanel {

    /** Project markers: the first directory holding one of these is the root. */
    private static final List<String> PROJECT_MARKERS = List.of(
            ".git", "pom.xml", "build.gradle", "settings.gradle", "build.xml",
            "package.json", "nbproject", ".idea");

    /** How far up the walk looks for a marker before giving up. */
    static final int MAX_WALK_UP = 10;

    private final JLabel rootLabel = new JLabel("Project");
    private final JTree tree = new JTree();
    private Consumer<Path> onFileChosen = p -> { };
    private Path rootPath;

    ProjectTreePanel() {
        super(new BorderLayout());
        setName("projectTreePanel");

        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(
                TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                loadChildren(event.getPath());
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
            }
        });
        tree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelectedNode();
                }
            }
        });

        rootLabel.setName("projectRootLabel");
        add(rootLabel, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setName("projectTreeScroll");
        add(scroll, BorderLayout.CENTER);
        setPreferredSize(new Dimension(220, 100));
    }

    /** Receives the path of a file the user double-clicked in the tree. */
    void setOnFileChosen(Consumer<Path> onFileChosen) {
        this.onFileChosen = (onFileChosen == null) ? p -> { } : onFileChosen;
    }

    /**
     * Re-roots the tree on {@code dir} (no-op when it already is the root).
     * The root expands automatically so the user sees content immediately.
     */
    void setRootPath(Path dir) {
        if (dir == null || dir.equals(rootPath)) {
            return;
        }
        rootPath = dir;
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(dir);
        // A childless node renders as a leaf; the placeholder keeps the root
        // expandable until the real children are swapped in just below.
        root.add(new DefaultMutableTreeNode("loading..."));
        // Load the root synchronously: a programmatic expandPath does not
        // fire the will-expand listener on an unrealized tree, and clicking
        // is impossible in headless tests. Deeper directories stay lazy.
        DefaultTreeModel model = new DefaultTreeModel(root);
        tree.setModel(model);
        loadChildren(new TreePath(root));
        rootLabel.setText(dir.getFileName() != null
                ? dir.getFileName().toString() : dir.toString());
        rootLabel.setToolTipText(dir.toString());
        tree.expandPath(new TreePath(root));
    }

    /** The current tree root directory, or null before the first one. */
    Path rootPath() {
        return rootPath;
    }

    /** The underlying tree (test seam). */
    JTree tree() {
        return tree;
    }

    /**
     * Walks up from {@code file} to the nearest detectable project root: the
     * first ancestor directory holding one of {@link #PROJECT_MARKERS}; falls
     * back to the file's parent directory. Pure, headless-testable.
     */
    static Path projectRootFor(Path file) {
        if (file == null) {
            return null;
        }
        Path dir = file.getParent();
        if (dir == null) {
            return Path.of("").toAbsolutePath();
        }
        Path candidate = dir;
        for (int i = 0; i < MAX_WALK_UP && candidate != null; i++) {
            for (String marker : PROJECT_MARKERS) {
                if (Files.exists(candidate.resolve(marker))) {
                    return candidate;
                }
            }
            candidate = candidate.getParent();
        }
        return dir;
    }

    /**
     * Populates an expansion directory node on demand; leaf nodes and loaded
     * directories are left alone.
     */
    private void loadChildren(TreePath path) {
        Object last = path.getLastPathComponent();
        if (!(last instanceof DefaultMutableTreeNode node)) {
            return;
        }
        if (!(node.getUserObject() instanceof Path dir) || Files.isRegularFile(dir)) {
            return;
        }
        if (isPlaceholderOnly(node)) {
            node.removeAllChildren(); // drop the "loading..." stub
        } else if (node.getChildCount() > 0) {
            return; // already loaded
        }
        for (DefaultMutableTreeNode child : listChildren(dir)) {
            node.add(child);
        }
        ((DefaultTreeModel) tree.getModel()).nodeStructureChanged(node);
    }

    /** True when the node holds just the lazy-load placeholder child. */
    private static boolean isPlaceholderOnly(DefaultMutableTreeNode node) {
        if (node.getChildCount() != 1) {
            return false;
        }
        Object child = node.getChildAt(0);
        return child instanceof DefaultMutableTreeNode stub
                && stub.getUserObject() instanceof String s && s.equals("loading...");
    }

    /** Directory children first, then files, each alphabetical; no hidden. */
    private static List<DefaultMutableTreeNode> listChildren(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return !n.startsWith(".");
                    })
                    .sorted(Comparator
                            .comparing((Path p) -> !isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString(),
                                    String.CASE_INSENSITIVE_ORDER))
                    .map(p -> {
                        DefaultMutableTreeNode node = new DefaultMutableTreeNode(p);
                        if (isDirectory(p)) {
                            node.add(new DefaultMutableTreeNode("loading..."));
                        }
                        return node;
                    })
                    .collect(java.util.stream.Collectors.toList());
        } catch (IOException | UncheckedIOException | SecurityException e) {
            return List.of();
        }
    }

    private static boolean isDirectory(Path p) {
        try {
            return Files.isDirectory(p);
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Fires {@code onFileChosen} for the selected node when it is a file. */
    private void openSelectedNode() {
        TreePath selection = tree.getSelectionPath();
        if (selection == null) {
            return;
        }
        Object last = selection.getLastPathComponent();
        if (!(last instanceof DefaultMutableTreeNode node)) {
            return;
        }
        if (node.getUserObject() instanceof Path file && Files.isRegularFile(file)) {
            onFileChosen.accept(file);
        }
    }

    /**
     * Selects {@code file} in the tree (expanding its parent chain) and
     * reports whether it was found under the current root (test seam and
     * programmatic-open path).
     */
    boolean revealAndOpen(Path file) {
        if (rootPath == null || file == null) {
            return false;
        }
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(rootPath.toAbsolutePath().normalize())
                || !Files.isRegularFile(normalized)) {
            return false;
        }
        DefaultMutableTreeNode root =
                (DefaultMutableTreeNode) tree.getModel().getRoot();
        DefaultMutableTreeNode node = findNode(root,
                rootPath.toAbsolutePath().normalize(), normalized.getParent());
        if (node == null) {
            return false;
        }
        TreePath path = new TreePath(node.getPath());
        tree.expandPath(path);
        DefaultMutableTreeNode fileNode = findFileNode(node, normalized);
        if (fileNode == null) {
            return false;
        }
        tree.setSelectionPath(path.pathByAddingChild(fileNode));
        onFileChosen.accept(normalized);
        return true;
    }

    /** The loaded directory node for {@code dir}, walking from {@code from}. */
    private DefaultMutableTreeNode findNode(DefaultMutableTreeNode from,
            Path fromDir, Path dir) {
        // Load this level first, so a match here is returned with its
        // children already on disk (the caller looks for files inside it).
        loadChildren(new TreePath(from.getPath()));
        if (fromDir.equals(dir)) {
            return from;
        }
        Path next = fromDir.resolve(relativizeOne(fromDir, dir));
        for (int i = 0; i < from.getChildCount(); i++) {
            if (from.getChildAt(i) instanceof DefaultMutableTreeNode child
                    && child.getUserObject() instanceof Path childDir
                    && childDir.toAbsolutePath().normalize().equals(next)) {
                return findNode(child, next, dir);
            }
        }
        return null;
    }

    private static Path relativizeOne(Path from, Path to) {
        return from.relativize(to).getName(0);
    }

    private DefaultMutableTreeNode findFileNode(DefaultMutableTreeNode parent,
            Path file) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i) instanceof DefaultMutableTreeNode child
                    && child.getUserObject() instanceof Path p
                    && p.toAbsolutePath().normalize().equals(file)) {
                return child;
            }
        }
        return null;
    }
}
