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
package org.jdesktop.lg3d.widgets.swing;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JInternalFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import org.jdesktop.lg3d.widgets.builtin.BuiltinWidgetCards;
import org.jdesktop.lg3d.widgets.builtin.WidgetCardSpec;

/**
 * The conventional-Swing desktop's widget gallery: the pure-Swing counterpart
 * of the 3D {@code org.jdesktop.lg3d.widgets.gallery.WidgetGallery}. It lists
 * every built-in widget card ({@link BuiltinWidgetCards}) and lets the user add
 * instances to - or remove them from - the live 2D widget layer.
 *
 * <p>It talks to the running desktop through {@link SwingWidgetLayer#current()};
 * if the widget layer is not installed (e.g. the gallery is opened outside the
 * 2D desktop, or before {@code Desktop2D} installs the layer) the Add/Remove
 * actions are disabled and a message is shown.</p>
 *
 * <p>Nothing here references Java 3D, so the panel builds and runs on a JVM
 * where the Java 3D jars are missing entirely. {@code Desktop2DAppRegistry}
 * hosts it in a desktop internal frame: it is a public {@link JPanel} with a
 * public no-arg constructor, and its Close button disposes the enclosing
 * internal frame (or top-level window when shown standalone).</p>
 */
public class WidgetGalleryPanel extends JPanel {

    private static final int PANEL_W = 360;
    private static final int PANEL_H = 440;

    private final DefaultListModel<WidgetCardSpec> model = new DefaultListModel<>();
    private final Map<String, Integer> placedCounts = new HashMap<>();
    private final Map<String, ImageIcon> icons = new HashMap<>();

    private final JList<WidgetCardSpec> list;
    private final JButton addButton = new JButton("Add");
    private final JButton removeButton = new JButton("Remove");
    private final JButton closeButton = new JButton("Close");
    private final JLabel statusLabel = new JLabel(" ");

    public WidgetGalleryPanel() {
        super(new BorderLayout(8, 8));
        for (WidgetCardSpec spec : BuiltinWidgetCards.all()) {
            model.addElement(spec);
            loadIcon(spec);
        }

        list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new SpecRenderer());
        list.addListSelectionListener(e -> updateButtons());

        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setPreferredSize(new Dimension(PANEL_W, PANEL_H));
        setBackground(new Color(238, 240, 244));

        add(buildHeader(), BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
        add(buildControls(), BorderLayout.SOUTH);

        refresh();
        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }
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
        closeButton.addActionListener(e -> doClose());

        statusLabel.setForeground(new Color(90, 100, 115));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 2));

        south.add(statusLabel, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);
        return south;
    }

    // ------------------------------------------------------------------

    private void refresh() {
        placedCounts.clear();
        SwingWidgetLayer layer = SwingWidgetLayer.current();
        if (layer != null) {
            for (String id : layer.instanceIds()) {
                String type = layer.typeOf(id);
                if (type != null) {
                    placedCounts.merge(type, 1, Integer::sum);
                }
            }
        }
        list.repaint();
        updateButtons();
    }

    private void updateButtons() {
        SwingWidgetLayer layer = SwingWidgetLayer.current();
        WidgetCardSpec sel = list.getSelectedValue();
        boolean live = layer != null;
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
        SwingWidgetLayer layer = SwingWidgetLayer.current();
        WidgetCardSpec sel = list.getSelectedValue();
        if (layer == null || sel == null) {
            return;
        }
        String id = layer.addWidgetAtFreeSpot(sel.id());
        if (id == null) {
            JOptionPane.showMessageDialog(this,
                    "Could not add the '" + sel.displayName() + "' widget.",
                    "Widget Gallery", JOptionPane.WARNING_MESSAGE);
        }
        refresh();
    }

    private void doRemove() {
        SwingWidgetLayer layer = SwingWidgetLayer.current();
        WidgetCardSpec sel = list.getSelectedValue();
        if (layer == null || sel == null) {
            return;
        }
        // Remove the most recently placed instance of this type.
        List<String> ids = layer.instanceIds();
        String victim = null;
        for (String id : ids) {
            if (sel.id().equals(layer.typeOf(id))) {
                victim = id;
            }
        }
        if (victim != null) {
            layer.removeWidget(victim);
        }
        refresh();
    }

    /** Disposes the enclosing internal frame, or the top-level window if none. */
    private void doClose() {
        JInternalFrame frame = (JInternalFrame) SwingUtilities
                .getAncestorOfClass(JInternalFrame.class, this);
        if (frame != null) {
            frame.dispose();
            return;
        }
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            window.dispose();
        }
    }

    private void loadIcon(WidgetCardSpec spec) {
        String res = spec.iconResource();
        if (res == null) {
            return;
        }
        try (InputStream in = WidgetGalleryPanel.class.getResourceAsStream(res)) {
            if (in != null) {
                BufferedImage img = ImageIO.read(in);
                if (img != null) {
                    icons.put(spec.id(), new ImageIcon(img.getScaledInstance(
                            24, 24, java.awt.Image.SCALE_SMOOTH)));
                }
            }
        } catch (Exception e) {
            // Icons are decorative; ignore load failures.
        }
    }

    private final class SpecRenderer extends JPanel
            implements ListCellRenderer<WidgetCardSpec> {

        private final JLabel iconLabel = new JLabel();
        private final JLabel nameLabel = new JLabel();
        private final JLabel metaLabel = new JLabel();

        SpecRenderer() {
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
                JList<? extends WidgetCardSpec> list, WidgetCardSpec value,
                int index, boolean isSelected, boolean cellHasFocus) {
            nameLabel.setText(value.displayName());
            int count = placedCounts.getOrDefault(value.id(), 0);
            metaLabel.setText(value.category()
                    + (count > 0 ? ("  \u2022  " + count + " placed") : ""));
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
