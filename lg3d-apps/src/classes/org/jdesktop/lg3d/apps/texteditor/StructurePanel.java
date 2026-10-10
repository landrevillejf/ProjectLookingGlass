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
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import org.jdesktop.lg3d.apps.texteditor.ext.StructureSymbol;

/**
 * The bottom Structure tab: an outline of the current document (types, methods,
 * fields) fed by the JVM structure extension through
 * {@link org.jdesktop.lg3d.apps.texteditor.ext.EditorContext#showStructure}.
 * Double-clicking a node asks the host to jump to that symbol's line.
 *
 * <p>A plain {@link JTree} (no popups, no dialogs) so it renders correctly when
 * the panel is captured offscreen. EDT-only.</p>
 */
final class StructurePanel extends JPanel {

    /** Carries the target line on the tree node user-object. */
    private record NodeInfo(StructureSymbol symbol) { }

    private final JLabel titleLabel = new JLabel("Structure");
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Document");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
    private final JTree tree = new JTree(treeModel);
    private Consumer<StructureSymbol> onActivate = s -> { };

    StructurePanel() {
        super(new BorderLayout());
        setName("structurePanel");

        JPanel header = new JPanel(new BorderLayout());
        titleLabel.setName("structureTitle");
        header.add(titleLabel, BorderLayout.WEST);

        tree.setName("structureTree");
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    activateSelected();
                }
            }
        });

        add(header, BorderLayout.NORTH);
        add(new JScrollPane(tree), BorderLayout.CENTER);
        setPreferredSize(new Dimension(240, 160));
    }

    /**
     * Replaces the outline with {@code symbols} under the given document
     * {@code title}. A null/empty list leaves just the root node.
     */
    void setStructure(String title, List<StructureSymbol> symbols) {
        titleLabel.setText((title == null || title.isBlank())
                ? "Structure" : title);
        root.removeAllChildren();
        if (symbols != null) {
            for (StructureSymbol s : symbols) {
                if (s != null) {
                    root.add(new DefaultMutableTreeNode(new NodeInfo(s)));
                }
            }
        }
        treeModel.reload();
        tree.expandRow(0);
    }

    /** Empties the outline. */
    void clear() {
        setStructure("Structure", List.of());
    }

    /** Host hook: invoked on double-click with the selected symbol. */
    void setOnActivate(Consumer<StructureSymbol> handler) {
        this.onActivate = (handler != null) ? handler : s -> { };
    }

    private void activateSelected() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return;
        }
        Object last = path.getLastPathComponent();
        if (last instanceof DefaultMutableTreeNode node
                && node.getUserObject() instanceof NodeInfo info) {
            onActivate.accept(info.symbol());
        }
    }

    /** @return the number of outline nodes below the root (test seam). */
    int nodeCount() {
        return root.getChildCount();
    }

    /** @return the symbol at the given child index, or null (test seam). */
    StructureSymbol symbolAt(int index) {
        if (index < 0 || index >= root.getChildCount()) {
            return null;
        }
        DefaultMutableTreeNode node =
                (DefaultMutableTreeNode) root.getChildAt(index);
        return (node.getUserObject() instanceof NodeInfo info)
                ? info.symbol() : null;
    }
}
