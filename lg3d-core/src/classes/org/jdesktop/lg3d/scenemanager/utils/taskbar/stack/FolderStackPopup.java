/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.scenemanager.utils.taskbar.stack;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import org.jdesktop.lg3d.utils.action.AppLaunchAction;
import org.jdesktop.lg3d.utils.system.Opener;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.SwingNode;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jogamp.vecmath.Vector3f;

/**
 * The expanded view of a {@link FolderStack}: a lightweight {@link Frame3D}
 * window hosting a Swing panel that renders the folder's recent entries either
 * as a vertical list or as an icon grid (OSX-stack style), with a view toggle,
 * an "Open Folder" action and a close button.
 *
 * <p>Clicking an entry opens it: files go to {@code xdg-open} via
 * {@link Opener}, sub-folders open in the lg3d file manager when it is on the
 * classpath (falling back to {@code xdg-open} until Stage 3 lands). Escape
 * closes the popup.</p>
 */
public class FolderStackPopup {

    /** Fully-qualified main class of the Stage 3 file manager. */
    static final String FILE_MANAGER_CLASS =
            "org.jdesktop.lg3d.apps.filemanager.FileManager";

    private static final int PANEL_W = 340;
    private static final int PANEL_H = 420;

    private final FolderStackModel model;
    private final Frame3D frame3d;
    private final StackPanel panel;

    public FolderStackPopup(FolderStackModel model) {
        this.model = model;
        this.panel = new StackPanel();

        SwingNode node = new SwingNode();
        node.setJPanel(panel);
        node.setTransparency(0.0f);

        frame3d = new Frame3D();
        frame3d.setName(model.getDisplayName() + " stack");
        frame3d.addChild(node);

        Toolkit3D tk = Toolkit3D.getToolkit3D();
        frame3d.setPreferredSize(new Vector3f(
                tk.widthNativeToPhysical(PANEL_W),
                tk.heightNativeToPhysical(PANEL_H), 0.01f));
    }

    /** Rescans the folder and shows (or re-shows) the popup. */
    public void show() {
        model.refresh();
        panel.reload();
        frame3d.changeEnabled(true);
        frame3d.changeVisible(true);
    }

    /** Hides the popup. */
    public void hide() {
        frame3d.changeEnabled(false);
    }

    public boolean isVisible() {
        return frame3d.isEnabled();
    }

    /**
     * Opens a folder in the lg3d file manager at that directory when the file
     * manager is on the classpath; otherwise falls back to {@code xdg-open}
     * (the desktop's own file manager). Guarded so a missing file manager never
     * throws.
     */
    static boolean openInFileManager(Path dir) {
        try {
            Class.forName(FILE_MANAGER_CLASS);
            new AppLaunchAction("java " + FILE_MANAGER_CLASS + " "
                    + dir.toAbsolutePath(),
                    FolderStackPopup.class.getClassLoader()).performAction(null);
            return true;
        } catch (ClassNotFoundException notPresent) {
            return Opener.open(dir);
        } catch (RuntimeException e) {
            return Opener.open(dir);
        }
    }

    private void openItem(FolderStackModel.StackItem item) {
        if (item == null) {
            return;
        }
        if (item.isDirectory()) {
            openInFileManager(item.getPath());
        } else {
            Opener.open(item.getPath());
        }
    }

    // ------------------------------------------------------------------

    private final class StackPanel extends JPanel {
        private final DefaultListModel<FolderStackModel.StackItem> listModel =
                new DefaultListModel<>();
        private final JList<FolderStackModel.StackItem> list =
                new JList<>(listModel);
        private final JLabel titleLabel = new JLabel();
        private final ItemRenderer renderer = new ItemRenderer();
        private boolean gridMode = false;

        StackPanel() {
            super(new BorderLayout());
            setPreferredSize(new Dimension(PANEL_W, PANEL_H));
            setBackground(new Color(240, 242, 246));

            add(buildHeader(), BorderLayout.NORTH);
            JScrollPane scroll = new JScrollPane(list);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            add(scroll, BorderLayout.CENTER);

            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(renderer);
            list.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    int idx = list.locationToIndex(e.getPoint());
                    if (idx >= 0) {
                        openItem(listModel.get(idx));
                    }
                }
            });
            // Escape closes the popup.
            list.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "closeStack");
            list.getActionMap().put("closeStack", new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    hide();
                }
            });

            applyMode();
        }

        private JPanel buildHeader() {
            JPanel header = new JPanel();
            header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
            header.setBackground(new Color(228, 232, 238));
            header.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
            titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            header.add(titleLabel);

            JPanel buttons = new JPanel(new BorderLayout());
            buttons.setOpaque(false);
            buttons.setAlignmentX(Component.LEFT_ALIGNMENT);

            JButton toggle = new JButton("Grid view");
            toggle.addActionListener(e -> {
                gridMode = !gridMode;
                toggle.setText(gridMode ? "List view" : "Grid view");
                applyMode();
            });
            JButton openFolder = new JButton("Open Folder");
            openFolder.addActionListener(e -> openInFileManager(model.getDirectory()));
            JButton close = new JButton("Close");
            close.addActionListener(e -> hide());

            JPanel left = new JPanel();
            left.setOpaque(false);
            left.add(toggle);
            left.add(openFolder);
            buttons.add(left, BorderLayout.WEST);
            buttons.add(close, BorderLayout.EAST);
            header.add(Box.createVerticalStrut(6));
            header.add(buttons);
            return header;
        }

        private void applyMode() {
            renderer.setGrid(gridMode);
            if (gridMode) {
                list.setLayoutOrientation(JList.HORIZONTAL_WRAP);
                list.setVisibleRowCount(-1);
                list.setFixedCellWidth(96);
                list.setFixedCellHeight(96);
            } else {
                list.setLayoutOrientation(JList.VERTICAL);
                list.setVisibleRowCount(10);
                list.setFixedCellWidth(-1);
                list.setFixedCellHeight(30);
            }
            list.revalidate();
            list.repaint();
        }

        void reload() {
            listModel.clear();
            List<FolderStackModel.StackItem> items = model.getItems();
            for (FolderStackModel.StackItem it : items) {
                listModel.addElement(it);
            }
            titleLabel.setText(model.getDisplayName() + "  (" + items.size() + ")");
            list.revalidate();
            list.repaint();
        }
    }

    private static final class ItemRenderer extends JPanel
            implements ListCellRenderer<FolderStackModel.StackItem> {

        private final JLabel iconLabel = new JLabel();
        private final JLabel nameLabel = new JLabel();
        private final JLabel metaLabel = new JLabel();
        private boolean grid = false;

        ItemRenderer() {
            super(new BorderLayout(6, 2));
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.PLAIN, 12f));
            metaLabel.setFont(metaLabel.getFont().deriveFont(Font.PLAIN, 10f));
            metaLabel.setForeground(new Color(110, 120, 135));
        }

        void setGrid(boolean grid) {
            this.grid = grid;
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends FolderStackModel.StackItem> list,
                FolderStackModel.StackItem value, int index,
                boolean isSelected, boolean cellHasFocus) {
            removeAll();
            nameLabel.setText(truncate(value.getName(), grid ? 12 : 26));
            if (value.getIcon() != null) {
                iconLabel.setIcon(value.getIcon());
            } else {
                iconLabel.setIcon(null);
            }
            if (grid) {
                nameLabel.setHorizontalAlignment(SwingConstants.CENTER);
                JPanel text = new JPanel();
                text.setOpaque(false);
                text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
                nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                text.add(nameLabel);
                setOpaque(true);
                add(iconLabel, BorderLayout.NORTH);
                add(text, BorderLayout.CENTER);
                setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 2));
            } else {
                metaLabel.setText(value.getTypeLabel());
                JPanel text = new JPanel(new BorderLayout());
                text.setOpaque(false);
                text.add(nameLabel, BorderLayout.CENTER);
                text.add(metaLabel, BorderLayout.EAST);
                add(iconLabel, BorderLayout.WEST);
                add(text, BorderLayout.CENTER);
                setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            }
            if (isSelected) {
                setBackground(new Color(70, 130, 210));
                nameLabel.setForeground(Color.WHITE);
                metaLabel.setForeground(new Color(215, 228, 245));
            } else {
                setBackground(index % 2 == 0 ? Color.WHITE : new Color(246, 248, 251));
                nameLabel.setForeground(new Color(30, 36, 48));
                metaLabel.setForeground(new Color(110, 120, 135));
            }
            return this;
        }

        private static String truncate(String s, int max) {
            return (s.length() > max) ? s.substring(0, max - 1) + "\u2026" : s;
        }
    }
}
