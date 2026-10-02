package org.jdesktop.lg3d.apps.dockermanager;

import com.protonmail.landrevillejf.IconManager;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.*;
import java.io.File;

// Tree Cell Renderer for files
public class FileTreeCellRenderer extends DefaultTreeCellRenderer {
    private final Icon folderIcon = IconManager.loadIcon(IconManager.IconCategory.GENERAL, "Open", 16, 16);
    private final Icon fileIcon = IconManager.loadIcon(IconManager.IconCategory.GENERAL, "New", 16, 16);
    private final Icon dockerIcon = IconManager.loadIcon(IconManager.IconCategory.DEVELOPMENT, "Application", 16, 16);
    private final Icon composeIcon = IconManager.loadIcon(IconManager.IconCategory.DEVELOPMENT, "Applet", 16, 16);

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value,
                                                  boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
        super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
        Object userObject = node.getUserObject();

        if (userObject instanceof File) {
            File file = (File) userObject;
            setText(file.getName());

            if (file.isDirectory()) {
                setIcon(folderIcon);
            } else {
                if (file.getName().equalsIgnoreCase("Dockerfile")) {
                    setIcon(dockerIcon);
                } else if (file.getName().equalsIgnoreCase("docker-compose.yml") ||
                        file.getName().equalsIgnoreCase("docker-compose.yaml")) {
                    setIcon(composeIcon);
                } else {
                    setIcon(fileIcon);
                }
            }
        }

        return this;
    }
}
