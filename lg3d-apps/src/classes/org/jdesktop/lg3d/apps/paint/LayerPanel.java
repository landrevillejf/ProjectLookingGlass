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
package org.jdesktop.lg3d.apps.paint;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

/**
 * The right-hand layer stack: a {@link JList} of layers (topmost first) with live
 * thumbnails, a visibility box, an opacity slider and a blend-mode combo for the
 * active layer, plus add/delete/duplicate/merge/reorder buttons. It listens to the
 * {@link PaintDocument} so it refreshes after every structural edit, and pushes
 * the user's attribute changes straight back into the document.
 *
 * <p>The document stores layers bottom-up; this panel displays them reversed so
 * the top of the list is the top of the stack. List index {@code i} maps to
 * document index {@code count-1-i}.</p>
 */
public class LayerPanel extends JPanel implements PaintDocument.Listener {

    private static final int CHECK_W = 18;

    private final PaintDocument doc;
    private final DefaultListModel<PaintLayer> model =
            new DefaultListModel<PaintLayer>();
    private final JList<PaintLayer> list = new JList<PaintLayer>(model);
    private final JSlider opacity = new JSlider(0, 100, 100);
    private final JComboBox<PaintLayer.BlendMode> blend =
            new JComboBox<PaintLayer.BlendMode>(PaintLayer.BlendMode.values());
    private boolean adjusting;

    public LayerPanel(PaintDocument doc) {
        this.doc = doc;
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Layers"));
        setPreferredSize(new Dimension(200, 300));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new LayerRenderer());
        list.addListSelectionListener(new ListSelectionListener() {
            public void valueChanged(ListSelectionEvent e) {
                if (!e.getValueIsAdjusting()) {
                    selectionToDocument();
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index < 0) {
                    return;
                }
                if (e.getX() < CHECK_W) {
                    PaintLayer layer = model.getElementAt(index);
                    layer.setVisible(!layer.isVisible());
                    doc.notifyChanged();
                }
            }
        });
        add(new javax.swing.JScrollPane(list), BorderLayout.CENTER);

        opacity.addChangeListener(new ChangeListener() {
            public void stateChanged(ChangeEvent e) {
                if (adjusting) {
                    return;
                }
                PaintLayer layer = activeFromList();
                if (layer != null) {
                    layer.setOpacity(opacity.getValue() / 100.0f);
                    doc.notifyChanged();
                }
            }
        });
        blend.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (adjusting) {
                    return;
                }
                PaintLayer layer = activeFromList();
                if (layer != null) {
                    layer.setBlend((PaintLayer.BlendMode) blend.getSelectedItem());
                    doc.notifyChanged();
                }
            }
        });

        JPanel controls = new JPanel(new BorderLayout());
        JPanel sliderRow = new JPanel(new BorderLayout());
        sliderRow.add(new JLabel("Opacity"), BorderLayout.WEST);
        sliderRow.add(opacity, BorderLayout.CENTER);
        JPanel blendRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        blendRow.add(new JLabel("Blend"));
        blendRow.add(blend);
        controls.add(sliderRow, BorderLayout.NORTH);
        controls.add(blendRow, BorderLayout.SOUTH);
        add(controls, BorderLayout.SOUTH);

        add(buildButtons(), BorderLayout.NORTH);

        doc.addListener(this);
        refresh();
    }

    private JPanel buildButtons() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        p.add(button("+", "Add layer", new Runnable() {
            public void run() {
                doc.addLayerAbove();
            }
        }));
        p.add(button("Dup", "Duplicate layer", new Runnable() {
            public void run() {
                doc.duplicateLayer();
            }
        }));
        p.add(button("-", "Delete layer", new Runnable() {
            public void run() {
                doc.deleteLayer();
            }
        }));
        p.add(button("Merge", "Merge down", new Runnable() {
            public void run() {
                doc.mergeDown();
            }
        }));
        p.add(button("Up", "Move layer up", new Runnable() {
            public void run() {
                doc.moveLayerUp();
            }
        }));
        p.add(button("Down", "Move layer down", new Runnable() {
            public void run() {
                doc.moveLayerDown();
            }
        }));
        return p;
    }

    private JButton button(String label, String tip, final Runnable action) {
        JButton b = new JButton(label);
        b.setToolTipText(tip);
        b.setMargin(new java.awt.Insets(1, 4, 1, 4));
        b.setFocusPainted(false);
        b.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                action.run();
            }
        });
        return b;
    }

    // ------------------------------------------------------------------

    public void documentChanged() {
        refresh();
    }

    /** Rebuilds the list (topmost first) and syncs the controls to the active layer. */
    public void refresh() {
        adjusting = true;
        model.clear();
        int count = doc.getLayerCount();
        for (int i = count - 1; i >= 0; i--) {
            model.addElement(doc.getLayer(i));
        }
        int activeList = count - 1 - doc.getActiveIndex();
        if (activeList >= 0 && activeList < model.size()) {
            list.setSelectedIndex(activeList);
        }
        PaintLayer active = doc.getActiveLayer();
        if (active != null) {
            opacity.setValue(Math.round(active.getOpacity() * 100.0f));
            blend.setSelectedItem(active.getBlend());
        }
        adjusting = false;
        list.repaint();
    }

    /** Pushes the list selection back into the document as the active index. */
    private void selectionToDocument() {
        int listIndex = list.getSelectedIndex();
        if (listIndex < 0) {
            return;
        }
        int docIndex = doc.getLayerCount() - 1 - listIndex;
        doc.setActiveIndex(docIndex);
        PaintLayer active = doc.getActiveLayer();
        adjusting = true;
        if (active != null) {
            opacity.setValue(Math.round(active.getOpacity() * 100.0f));
            blend.setSelectedItem(active.getBlend());
        }
        adjusting = false;
    }

    private PaintLayer activeFromList() {
        int listIndex = list.getSelectedIndex();
        if (listIndex < 0 || listIndex >= model.size()) {
            return null;
        }
        return model.getElementAt(listIndex);
    }

    // ------------------------------------------------------------------

    private static final class LayerRenderer extends JPanel
            implements ListCellRenderer<PaintLayer> {

        private final JLabel name = new JLabel();
        private boolean visible;
        private boolean selected;
        private BufferedImage thumb;

        LayerRenderer() {
            setLayout(new BorderLayout(4, 0));
            setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
            name.setBorder(BorderFactory.createEmptyBorder(0, 62, 0, 0));
            add(name, BorderLayout.CENTER);
        }

        public Component getListCellRendererComponent(
                JList<? extends PaintLayer> list, PaintLayer layer, int index,
                boolean isSelected, boolean cellHasFocus) {
            visible = layer.isVisible();
            selected = isSelected;
            name.setText(layer.getName());
            thumb = thumbnail(layer.getImage(), 40, 28);
            setOpaque(true);
            setBackground(isSelected ? new Color(0xc8, 0xdc, 0xf5)
                    : Color.WHITE);
            name.setForeground(visible ? Color.BLACK : Color.GRAY);
            setPreferredSize(new Dimension(180, 34));
            return this;
        }

        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            int y = (getHeight() - 28) / 2;
            // Visibility checkbox.
            g.setColor(Color.WHITE);
            g.fillRect(2, y + 6, 12, 12);
            g.setColor(Color.DARK_GRAY);
            g.drawRect(2, y + 6, 12, 12);
            if (visible) {
                g.setColor(new Color(0x2a, 0x6f, 0xdb));
                g.fillRect(4, y + 8, 9, 9);
            }
            // Thumbnail.
            if (thumb != null) {
                g.setColor(selected ? new Color(0xa9, 0xc2, 0xe8) : Color.LIGHT_GRAY);
                g.fillRect(CHECK_W, y, 40, 28);
                g.drawImage(thumb, CHECK_W, y, null);
                g.setColor(Color.GRAY);
                g.drawRect(CHECK_W, y, 40, 28);
            }
        }

        private static BufferedImage thumbnail(BufferedImage src, int w, int h) {
            BufferedImage out = new BufferedImage(w, h,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            return out;
        }
    }
}
