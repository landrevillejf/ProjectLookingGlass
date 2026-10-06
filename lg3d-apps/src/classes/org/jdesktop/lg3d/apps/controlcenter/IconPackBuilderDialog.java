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
package org.jdesktop.lg3d.apps.controlcenter;

import com.protonmail.landrevillejf.IconManager.IconCategory;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import org.jdesktop.lg3d.displayserver.desktop2d.IconGlyphLibrary;
import org.jdesktop.lg3d.displayserver.desktop2d.IconPack;
import org.jdesktop.lg3d.displayserver.desktop2d.IconPackManager;
import org.jdesktop.lg3d.displayserver.desktop2d.IconPackManager.AppIconTarget;

/**
 * A modal builder that lets the user compose a new icon pack from the desktop's
 * own IconManager glyph library. Applications the 2D desktop can override are
 * listed down the left (from {@link IconPackManager#appIconTargets()}); the
 * glyph catalogue - categories then a live-preview glyph list - sits on the
 * right. The user selects an application, picks a glyph and presses
 * <em>Assign</em>; assigned glyphs preview beside each application. <em>Save
 * Pack&hellip;</em> prompts for a name and persists the assignment through
 * {@link IconPackManager#saveUserPack}, returning the new {@link IconPack}.
 *
 * <p>Like the rest of the control center, every choice control is a
 * {@link JList} selector (never a combo box) so the panel keeps working when it
 * is hosted offscreen in a {@code SwingNode}. The dialog only reports the
 * created pack; the caller applies it. {@link #show(Component)} returns
 * {@code null} when the user cancels.</p>
 */
final class IconPackBuilderDialog extends JDialog {

    /** A chosen glyph: its category and base name. */
    private record Selection(IconCategory category, String glyph) {
    }

    private static final int PREVIEW_EDGE = 16;

    private final DefaultListModel<AppIconTarget> appModel = new DefaultListModel<>();
    private final DefaultListModel<IconCategory> categoryModel = new DefaultListModel<>();
    private final DefaultListModel<String> glyphModel = new DefaultListModel<>();
    private final JList<AppIconTarget> appList = new JList<>(appModel);
    private final JList<IconCategory> categoryList = new JList<>(categoryModel);
    private final JList<String> glyphList = new JList<>(glyphModel);
    private final GlyphRenderer glyphRenderer = new GlyphRenderer();
    private final JLabel statusLabel = new JLabel(" ");

    /** baseName -> chosen glyph; the pack the user is composing. */
    private final Map<String, Selection> assignments = new LinkedHashMap<>();
    private final Map<String, Icon> previewCache = new HashMap<>();

    private IconPack result;

    private IconPackBuilderDialog(final Component parent) {
        super(SwingUtilities.getWindowAncestor(parent), "Create Icon Pack",
                ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        initComponents();
        populateApps();
        populateCategories();
        pack();
        setLocationRelativeTo(parent);
    }

    /**
     * Shows the builder modally and returns the newly created pack, or
     * {@code null} when the user cancels or nothing could be saved.
     */
    static IconPack show(final Component parent) {
        IconPackBuilderDialog dialog = new IconPackBuilderDialog(parent);
        dialog.setVisible(true);
        return dialog.result;
    }

    private void initComponents() {
        appList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        appList.setCellRenderer(new AppRenderer());
        appList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateButtons();
            }
        });
        JScrollPane appScroll = new JScrollPane(appList);
        appScroll.setPreferredSize(new Dimension(220, 340));
        appScroll.setBorder(BorderFactory.createTitledBorder("Applications"));

        categoryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        categoryList.setCellRenderer(new CategoryRenderer());
        categoryList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                populateGlyphs();
            }
        });
        JScrollPane categoryScroll = new JScrollPane(categoryList);
        categoryScroll.setPreferredSize(new Dimension(130, 340));

        glyphList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        glyphList.setVisibleRowCount(14);
        glyphList.setCellRenderer(glyphRenderer);
        glyphList.addListSelectionListener(e -> updateButtons());
        JScrollPane glyphScroll = new JScrollPane(glyphList);
        glyphScroll.setPreferredSize(new Dimension(220, 340));
        glyphScroll.setBorder(BorderFactory.createTitledBorder("Icons"));

        JPanel glyphPicker = new JPanel(new BorderLayout(6, 0));
        glyphPicker.add(categoryScroll, BorderLayout.WEST);
        glyphPicker.add(glyphScroll, BorderLayout.CENTER);

        JButton assign = new JButton("Assign");
        assign.setToolTipText("Use the selected icon for the selected application");
        assign.addActionListener(e -> assignSelected());
        JButton clear = new JButton("Clear");
        clear.setToolTipText("Remove the assigned icon from the selected application");
        clear.addActionListener(e -> clearSelected());
        JPanel assignRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        assignRow.add(assign);
        assignRow.add(clear);

        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.add(appScroll, BorderLayout.CENTER);
        left.add(assignRow, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, glyphPicker);
        split.setDividerLocation(240);
        split.setResizeWeight(0.4);

        JButton save = new JButton("Save Pack\u2026");
        save.addActionListener(e -> savePack());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel south = new JPanel(new BorderLayout(0, 4));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        buttons.add(save);
        buttons.add(cancel);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 10, 4, 10));
        south.add(statusLabel, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        content.add(split, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(save);
        updateButtons();
    }

    private void populateApps() {
        appModel.clear();
        for (AppIconTarget target : IconPackManager.appIconTargets()) {
            appModel.addElement(target);
        }
        if (!appModel.isEmpty()) {
            appList.setSelectedIndex(0);
        }
    }

    private void populateCategories() {
        categoryModel.clear();
        for (IconCategory category : IconGlyphLibrary.categories()) {
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
        for (String name : IconGlyphLibrary.glyphNames(category)) {
            glyphModel.addElement(name);
        }
        glyphList.clearSelection();
        if (!glyphModel.isEmpty()) {
            glyphList.ensureIndexIsVisible(0);
        }
        updateButtons();
    }

    private void assignSelected() {
        AppIconTarget app = appList.getSelectedValue();
        IconCategory category = categoryList.getSelectedValue();
        String glyph = glyphList.getSelectedValue();
        if (app == null || category == null || glyph == null) {
            return;
        }
        assignments.put(app.baseName(), new Selection(category, glyph));
        previewCache.remove(app.baseName());
        appList.repaint();
        statusLabel.setText(app.appName() + " \u2192 " + glyph
                + "  (" + assignments.size() + " assigned)");
    }

    private void clearSelected() {
        AppIconTarget app = appList.getSelectedValue();
        if (app == null) {
            return;
        }
        if (assignments.remove(app.baseName()) != null) {
            previewCache.remove(app.baseName());
            appList.repaint();
            statusLabel.setText(app.appName() + " cleared  ("
                    + assignments.size() + " assigned)");
        }
    }

    private void updateButtons() {
        // Nothing to gate on selection alone; Assign/Clear/Save guard themselves.
    }

    private void savePack() {
        if (assignments.isEmpty()) {
            warn("Assign at least one icon first.");
            return;
        }
        String name = JOptionPane.showInputDialog(this, "Pack name:", "Create Icon Pack",
                JOptionPane.QUESTION_MESSAGE);
        if (name == null || name.isBlank()) {
            return;
        }
        if (IconPackManager.sanitizePackId(name).isEmpty()) {
            warn("That name has no usable letters or digits.");
            return;
        }
        Map<String, BufferedImage> icons = new LinkedHashMap<>();
        for (Map.Entry<String, Selection> entry : assignments.entrySet()) {
            Selection sel = entry.getValue();
            BufferedImage image = IconGlyphLibrary.loadImage(sel.category(), sel.glyph(),
                    IconPackManager.PACK_ICON_EDGE);
            if (image != null) {
                icons.put(entry.getKey(), image);
            }
        }
        IconPack pack = IconPackManager.saveUserPack(name, icons);
        if (pack == null) {
            warn("Could not save the icon pack.");
            return;
        }
        result = pack;
        dispose();
    }

    private void warn(final String message) {
        JOptionPane.showMessageDialog(this, message, "Create Icon Pack",
                JOptionPane.WARNING_MESSAGE);
    }

    /** An assigned glyph preview for one application's base name, or null. */
    private Icon assignedPreview(final String baseName) {
        Selection sel = assignments.get(baseName);
        if (sel == null) {
            return null;
        }
        Icon cached = previewCache.get(baseName);
        if (cached != null) {
            return cached;
        }
        Icon icon = IconGlyphLibrary.loadIcon(sel.category(), sel.glyph(), PREVIEW_EDGE);
        if (icon != null) {
            previewCache.put(baseName, icon);
        }
        return icon;
    }

    /** Renders an application with its assigned glyph preview. */
    private final class AppRenderer extends JLabel
            implements ListCellRenderer<AppIconTarget> {

        AppRenderer() {
            setOpaque(true);
            setHorizontalTextPosition(SwingConstants.RIGHT);
            setIconTextGap(8);
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        }

        @Override
        public Component getListCellRendererComponent(final JList<? extends AppIconTarget> list,
                final AppIconTarget value, final int index, final boolean isSelected,
                final boolean cellHasFocus) {
            setText(value.appName());
            setIcon(assignedPreview(value.baseName()));
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            return this;
        }
    }

    /** Renders a category as its friendly label. */
    private static final class CategoryRenderer extends JLabel
            implements ListCellRenderer<IconCategory> {

        CategoryRenderer() {
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        }

        @Override
        public Component getListCellRendererComponent(final JList<? extends IconCategory> list,
                final IconCategory value, final int index, final boolean isSelected,
                final boolean cellHasFocus) {
            setText(IconGlyphLibrary.label(value));
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            return this;
        }
    }

    /** Renders a glyph name beside a live preview loaded from IconManager. */
    private static final class GlyphRenderer extends JLabel
            implements ListCellRenderer<String> {

        private final Map<String, Icon> cache = new HashMap<>();
        private IconCategory category;

        GlyphRenderer() {
            setOpaque(true);
            setHorizontalTextPosition(SwingConstants.RIGHT);
            setIconTextGap(8);
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
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

        private Icon preview(final String glyph) {
            String key = category + "/" + glyph;
            Icon cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            Icon icon = IconGlyphLibrary.loadIcon(category, glyph, PREVIEW_EDGE);
            if (icon != null) {
                cache.put(key, icon);
            }
            return icon;
        }
    }
}
