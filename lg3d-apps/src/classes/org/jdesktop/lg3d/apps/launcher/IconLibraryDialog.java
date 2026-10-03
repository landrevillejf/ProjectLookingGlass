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
package org.jdesktop.lg3d.apps.launcher;

import com.protonmail.landrevillejf.IconManager.IconCategory;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;

/**
 * A modal picker over the bundled IconManager glyph library (see
 * {@link IconLibrary}). It shows the categories down the left and the glyphs of
 * the selected category on the right, each with a live preview, so the user can
 * pick one of the desktop's own icons for a launcher instead of browsing for a
 * custom image file.
 *
 * <p>The dialog only reports the chosen {@link Selection} (category + glyph
 * name); the caller decides how to persist it. {@link #show(Component)} returns
 * {@code null} when the user cancels, so it is safe to call directly from an
 * action listener.</p>
 */
final class IconLibraryDialog extends JDialog {

    /** The category and glyph name the user confirmed. */
    record Selection(IconCategory category, String glyph) {
    }

    private final DefaultListModel<IconCategory> categoryModel = new DefaultListModel<>();
    private final DefaultListModel<String> glyphModel = new DefaultListModel<>();
    private final JList<IconCategory> categoryList = new JList<>(categoryModel);
    private final JList<String> glyphList = new JList<>(glyphModel);
    private final GlyphRenderer glyphRenderer = new GlyphRenderer();
    private final JButton okButton = new JButton("OK");

    private Selection result;

    private IconLibraryDialog(final Component parent) {
        super(javax.swing.SwingUtilities.getWindowAncestor(parent),
                "Choose an Icon", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        initComponents();
        selectFirstCategory();
        pack();
        setLocationRelativeTo(parent);
    }

    /**
     * Shows the picker modally and returns the confirmed selection, or
     * {@code null} when cancelled or when the glyph library is unavailable.
     */
    static Selection show(final Component parent) {
        if (!IconLibrary.isAvailable()) {
            return null;
        }
        IconLibraryDialog dialog = new IconLibraryDialog(parent);
        dialog.setVisible(true);
        return dialog.result;
    }

    private void initComponents() {
        categoryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        categoryList.setCellRenderer(new CategoryRenderer());
        categoryList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                populateGlyphs();
            }
        });

        glyphList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        glyphList.setVisibleRowCount(12);
        glyphList.setCellRenderer(glyphRenderer);
        glyphList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                okButton.setEnabled(glyphList.getSelectedValue() != null);
            }
        });

        JScrollPane categoryScroll = new JScrollPane(categoryList);
        categoryScroll.setPreferredSize(new Dimension(140, 320));

        JScrollPane glyphScroll = new JScrollPane(glyphList);
        glyphScroll.setPreferredSize(new Dimension(260, 320));

        JPanel lists = new JPanel(new BorderLayout(8, 0));
        lists.add(categoryScroll, BorderLayout.WEST);
        lists.add(glyphScroll, BorderLayout.CENTER);

        okButton.setEnabled(false);
        okButton.addActionListener(e -> confirm());
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        buttons.add(okButton);
        buttons.add(cancelButton);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 10, 4, 10));
        content.add(lists, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(okButton);
    }

    private void selectFirstCategory() {
        for (IconCategory category : IconLibrary.categories()) {
            categoryModel.addElement(category);
        }
        if (!categoryModel.isEmpty()) {
            categoryList.setSelectedIndex(0);
        }
        populateGlyphs();
    }

    private void populateGlyphs() {
        glyphModel.clear();
        IconCategory category = categoryList.getSelectedValue();
        if (category == null) {
            return;
        }
        glyphRenderer.setCategory(category);
        List<String> names = IconLibrary.glyphNames(category);
        for (String name : names) {
            glyphModel.addElement(name);
        }
        glyphList.clearSelection();
        okButton.setEnabled(false);
        if (!glyphModel.isEmpty()) {
            glyphList.ensureIndexIsVisible(0);
        }
    }

    private void confirm() {
        IconCategory category = categoryList.getSelectedValue();
        String glyph = glyphList.getSelectedValue();
        if (category == null || glyph == null) {
            return;
        }
        result = new Selection(category, glyph);
        dispose();
    }

    /** Renders a category as its friendly label. */
    private static final class CategoryRenderer extends JLabel
            implements ListCellRenderer<IconCategory> {

        CategoryRenderer() {
            setOpaque(true);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8));
        }

        @Override
        public Component getListCellRendererComponent(final JList<? extends IconCategory> list,
                final IconCategory value, final int index, final boolean isSelected,
                final boolean cellHasFocus) {
            setText(IconLibrary.label(value));
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            return this;
        }
    }

    /** Renders a glyph name beside a live preview loaded from IconManager. */
    private static final class GlyphRenderer extends JLabel
            implements ListCellRenderer<String> {

        private final Map<String, javax.swing.Icon> cache = new HashMap<>();
        private IconCategory category;

        GlyphRenderer() {
            setOpaque(true);
            setHorizontalTextPosition(SwingConstants.RIGHT);
            setIconTextGap(8);
            setBorder(javax.swing.BorderFactory.createEmptyBorder(3, 6, 3, 6));
        }

        void setCategory(final IconCategory category) {
            this.category = category;
        }

        @Override
        public Component getListCellRendererComponent(final JList<? extends String> list,
                final String value, final int index, final boolean isSelected,
                final boolean cellHasFocus) {
            setText(value);
            setIcon(preview(value));
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            return this;
        }

        private javax.swing.Icon preview(final String glyph) {
            String key = category + "/" + glyph;
            javax.swing.Icon cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            javax.swing.Icon icon = IconLibrary.load(category, glyph);
            if (icon != null) {
                cache.put(key, icon);
            }
            return icon;
        }
    }
}
