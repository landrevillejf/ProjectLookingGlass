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
package org.jdesktop.lg3d.widgets.gallery;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import org.jdesktop.lg3d.wg.Frame3D;
import org.jdesktop.lg3d.wg.SwingNode;
import org.jdesktop.lg3d.wg.Toolkit3D;
import org.jdesktop.lg3d.widgets.api.WidgetDescriptor;
import org.jdesktop.lg3d.widgets.api.WidgetRegistry;
import org.jdesktop.lg3d.widgets.host.WidgetHost;
import org.jdesktop.lg3d.widgets.host.WidgetLayerPlugin;
import org.jogamp.vecmath.Vector3f;

/**
 * A small application that lists every widget type discovered by the
 * {@link WidgetRegistry} and lets the user add instances to (or remove them
 * from) the live desktop widget layer.
 *
 * <p>It talks to the running desktop through {@link WidgetLayerPlugin#host()};
 * if the widget layer is not present (e.g. the gallery is launched outside the
 * lg3d desktop) the Add/Remove actions are disabled and a message is shown.</p>
 */
public class WidgetGallery {

    private static final int PANEL_W = 360;
    private static final int PANEL_H = 440;

    private final Frame3D frame3d;
    private final DefaultListModel<WidgetDescriptor> model = new DefaultListModel<>();
    private final Map<String, Integer> placedCounts = new HashMap<>();
    private final Map<String, ImageIcon> icons = new HashMap<>();

    private final JList<WidgetDescriptor> list;
    private final JButton addButton = new JButton("Add");
    private final JButton removeButton = new JButton("Remove");
    private final JButton closeButton = new JButton("Close");
    private final JLabel statusLabel = new JLabel(" ");

    public static void main(String[] args) {
        new WidgetGallery();
    }

    public WidgetGallery() {
        for (WidgetDescriptor d : WidgetRegistry.getInstance().descriptors()) {
            model.addElement(d);
            loadIcon(d);
        }

        list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DescriptorRenderer());
        list.addListSelectionListener(e -> updateButtons());

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.setPreferredSize(new Dimension(PANEL_W, PANEL_H));
        root.setBackground(new Color(238, 240, 244));

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(new JScrollPane(list), BorderLayout.CENTER);
        root.add(buildControls(), BorderLayout.SOUTH);

        refresh();
        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }

        SwingNode swingNode = new SwingNode();
        swingNode.setJPanel(root);
        swingNode.setTransparency(0.0f);

        frame3d = new Frame3D();
        frame3d.setName("Widget Gallery");
        frame3d.addChild(swingNode);

        Toolkit3D tk = Toolkit3D.getToolkit3D();
        float w = tk.widthNativeToPhysical(PANEL_W);
        float h = tk.heightNativeToPhysical(PANEL_H);
        frame3d.setPreferredSize(new Vector3f(w, h, 0.01f));
        frame3d.changeEnabled(true);
        frame3d.changeVisible(true);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel title = new JLabel("Desktop Widgets");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        JLabel subtitle = new JLabel(
                "<html>Add widgets to your desktop, or remove ones you have placed. "
                + "Drag placed widgets to reposition them.</html>");
        subtitle.setForeground(new Color(90, 100, 115));
        header.add(title, BorderLayout.NORTH);
        header.add(subtitle, BorderLayout.CENTER);
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        return header;
    }

    private JPanel buildControls() {
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);

        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setOpaque(false);
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.add(addButton);
        left.add(removeButton);
        buttons.add(left, BorderLayout.WEST);
        buttons.add(closeButton, BorderLayout.EAST);

        addButton.addActionListener(e -> doAdd());
        removeButton.addActionListener(e -> doRemove());
        closeButton.addActionListener(e -> frame3d.changeEnabled(false));

        statusLabel.setForeground(new Color(90, 100, 115));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 2));

        south.add(statusLabel, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);
        return south;
    }

    // ------------------------------------------------------------------

    private void refresh() {
        placedCounts.clear();
        WidgetHost host = WidgetLayerPlugin.host();
        if (host != null) {
            for (String id : host.instanceIds()) {
                String type = host.typeOf(id);
                if (type != null) {
                    placedCounts.merge(type, 1, Integer::sum);
                }
            }
        }
        list.repaint();
        updateButtons();
    }

    private void updateButtons() {
        WidgetHost host = WidgetLayerPlugin.host();
        WidgetDescriptor sel = list.getSelectedValue();
        boolean live = host != null;
        addButton.setEnabled(live && sel != null);
        int count = (sel == null) ? 0 : placedCounts.getOrDefault(sel.id(), 0);
        removeButton.setEnabled(live && sel != null && count > 0);
        if (!live) {
            statusLabel.setText("Widget layer is not running on this desktop.");
        } else {
            int total = 0;
            for (int c : placedCounts.values()) {
                total += c;
            }
            statusLabel.setText(total + " widget(s) placed.");
        }
    }

    private void doAdd() {
        WidgetHost host = WidgetLayerPlugin.host();
        WidgetDescriptor sel = list.getSelectedValue();
        if (host == null || sel == null) {
            return;
        }
        String id = host.addWidgetAtFreeSpot(sel.id());
        if (id == null) {
            JOptionPane.showMessageDialog(null,
                    "Could not add the '" + sel.displayName() + "' widget.",
                    "Widget Gallery", JOptionPane.WARNING_MESSAGE);
        }
        refresh();
    }

    private void doRemove() {
        WidgetHost host = WidgetLayerPlugin.host();
        WidgetDescriptor sel = list.getSelectedValue();
        if (host == null || sel == null) {
            return;
        }
        // Remove the most recently placed instance of this type.
        List<String> ids = host.instanceIds();
        String victim = null;
        for (String id : ids) {
            if (sel.id().equals(host.typeOf(id))) {
                victim = id;
            }
        }
        if (victim != null) {
            host.removeWidget(victim);
        }
        refresh();
    }

    private void loadIcon(WidgetDescriptor d) {
        String res = d.iconResource();
        if (res == null) {
            return;
        }
        try (InputStream in = WidgetGallery.class.getResourceAsStream(res)) {
            if (in != null) {
                BufferedImage img = ImageIO.read(in);
                if (img != null) {
                    icons.put(d.id(), new ImageIcon(img.getScaledInstance(
                            24, 24, java.awt.Image.SCALE_SMOOTH)));
                }
            }
        } catch (Exception e) {
            // Icons are decorative; ignore load failures.
        }
    }

    private final class DescriptorRenderer extends JPanel
            implements ListCellRenderer<WidgetDescriptor> {

        private final JLabel iconLabel = new JLabel();
        private final JLabel nameLabel = new JLabel();
        private final JLabel metaLabel = new JLabel();

        DescriptorRenderer() {
            super(new BorderLayout(8, 0));
            setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            JPanel text = new JPanel(new BorderLayout());
            text.setOpaque(false);
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 13f));
            metaLabel.setForeground(new Color(110, 120, 135));
            text.add(nameLabel, BorderLayout.CENTER);
            text.add(metaLabel, BorderLayout.SOUTH);
            add(iconLabel, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends WidgetDescriptor> list, WidgetDescriptor value,
                int index, boolean isSelected, boolean cellHasFocus) {
            nameLabel.setText(value.displayName());
            int count = placedCounts.getOrDefault(value.id(), 0);
            metaLabel.setText(value.category() + (count > 0 ? ("  \u2022  " + count + " placed") : ""));
            ImageIcon icon = icons.get(value.id());
            iconLabel.setIcon(icon != null ? icon : new ImageIcon(placeholderIcon()));
            if (isSelected) {
                setBackground(new Color(70, 130, 210));
                nameLabel.setForeground(Color.WHITE);
                metaLabel.setForeground(new Color(215, 228, 245));
            } else {
                setBackground(index % 2 == 0 ? Color.WHITE : new Color(245, 247, 250));
                nameLabel.setForeground(new Color(30, 36, 48));
                metaLabel.setForeground(new Color(110, 120, 135));
            }
            setOpaque(true);
            return this;
        }
    }

    private static BufferedImage placeholderIcon() {
        BufferedImage img = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(120, 150, 200));
            g2.fillRoundRect(2, 2, 20, 20, 8, 8);
        } finally {
            g2.dispose();
        }
        return img;
    }
}
