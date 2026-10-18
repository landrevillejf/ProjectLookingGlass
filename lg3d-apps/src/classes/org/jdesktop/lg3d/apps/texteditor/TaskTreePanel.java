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
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import org.jdesktop.lg3d.apps.texteditor.ext.BuildTask;

/**
 * The bottom Tasks tab: the build tasks the open project proposes, discovered by
 * scanning its {@code build.gradle} / {@code pom.xml} (never by invoking the
 * tool) and fed by a build extension through
 * {@link org.jdesktop.lg3d.apps.texteditor.ext.EditorContext#showTasks}. Tasks
 * are grouped into a two-level tree on their {@link BuildTask#group()} — a
 * {@code Lifecycle} branch, the build script's own {@code Declared tasks}, and
 * one branch per subproject — and double-clicking a runnable leaf runs it
 * through the extension that published it (the same path its toolbar action
 * uses, so the log, findings and status line are identical).
 *
 * <p>Because the tree is a scan of the build file, it refreshes the moment a
 * task is added or removed: the extension re-publishes on document open, save
 * and (for a build file) on the debounced edit signal. A plain {@link JTree}
 * with no popups or dialogs, so it renders correctly when the panel is captured
 * offscreen. EDT-only.</p>
 */
final class TaskTreePanel extends JPanel {

    private final JLabel titleLabel = new JLabel("Tasks");
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Tasks");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree tree = new JTree(treeModel);
    /** Group name -> its branch node, so leaves file under a stable branch. */
    private final Map<String, DefaultMutableTreeNode> groupNodes = new LinkedHashMap<>();

    TaskTreePanel() {
        super(new BorderLayout());
        setName("taskTreePanel");

        JPanel header = new JPanel(new BorderLayout());
        titleLabel.setName("taskTreeTitle");
        header.add(titleLabel, BorderLayout.WEST);

        tree.setName("taskTree");
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    runSelected();
                }
            }
        });

        add(header, BorderLayout.NORTH);
        add(new JScrollPane(tree), BorderLayout.CENTER);
        setPreferredSize(new Dimension(240, 160));
    }

    /**
     * Replaces the tree with {@code tasks} under the given {@code title},
     * grouped by each task's {@link BuildTask#group()} in first-seen order. A
     * null/empty list leaves just the root, so a project with no build file
     * shows an empty tree rather than a stale one.
     */
    void setTasks(String title, List<BuildTask> tasks) {
        titleLabel.setText((title == null || title.isBlank()) ? "Tasks" : title);
        root.removeAllChildren();
        root.setUserObject((title == null || title.isBlank()) ? "Tasks" : title);
        groupNodes.clear();
        if (tasks != null) {
            for (BuildTask task : tasks) {
                if (task == null) {
                    continue;
                }
                groupNodeFor(task.group()).add(new DefaultMutableTreeNode(task));
            }
        }
        treeModel.reload();
        expandAll();
    }

    /** Empties the tree. */
    void clear() {
        setTasks("Tasks", List.of());
    }

    /** The branch node for {@code group}, created (and hung off root) on demand. */
    private DefaultMutableTreeNode groupNodeFor(String group) {
        String key = (group == null || group.isBlank()) ? "" : group;
        return groupNodes.computeIfAbsent(key, g -> {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(
                    g.isEmpty() ? "Tasks" : g);
            root.add(node);
            return node;
        });
    }

    private void expandAll() {
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }

    /** Runs the selected leaf's action when it carries one. */
    private void runSelected() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return;
        }
        Object last = path.getLastPathComponent();
        if (last instanceof DefaultMutableTreeNode node
                && node.getUserObject() instanceof BuildTask task
                && task.isRunnable()) {
            task.action().run();
        }
    }

    /** @return the number of group branches below the root (test seam). */
    int groupCount() {
        return root.getChildCount();
    }

    /** @return the group branch names, in first-seen order (test seam). */
    List<String> groups() {
        List<String> out = new ArrayList<>(root.getChildCount());
        for (int i = 0; i < root.getChildCount(); i++) {
            out.add(String.valueOf(
                    ((DefaultMutableTreeNode) root.getChildAt(i)).getUserObject()));
        }
        return out;
    }

    /** @return every task leaf in the tree, across all groups (test seam). */
    int taskCount() {
        int n = 0;
        for (int i = 0; i < root.getChildCount(); i++) {
            n += root.getChildAt(i).getChildCount();
        }
        return n;
    }

    /** @return the tasks filed under {@code group}, or empty (test seam). */
    List<BuildTask> tasksIn(String group) {
        DefaultMutableTreeNode node = groupNodes.get((group == null) ? "" : group);
        if (node == null) {
            return List.of();
        }
        List<BuildTask> out = new ArrayList<>(node.getChildCount());
        for (int i = 0; i < node.getChildCount(); i++) {
            Object user = ((DefaultMutableTreeNode) node.getChildAt(i)).getUserObject();
            if (user instanceof BuildTask task) {
                out.add(task);
            }
        }
        return out;
    }

    /** @return the title label's text (test seam). */
    String titleText() {
        return titleLabel.getText();
    }

    /** The underlying tree (test seam). */
    JTree tree() {
        return tree;
    }
}
