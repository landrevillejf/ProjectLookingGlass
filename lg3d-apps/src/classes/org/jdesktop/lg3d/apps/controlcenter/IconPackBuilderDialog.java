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

import com.protonmail.landrevillejf.IconColor;
import com.protonmail.landrevillejf.IconManager.IconCategory;
import com.protonmail.landrevillejf.IconManager.IconEffect;
import com.protonmail.landrevillejf.IconManager.IconStyle;
import com.protonmail.landrevillejf.IconManager.PatternType;
import com.protonmail.landrevillejf.IconManager.StatusType;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import org.jdesktop.lg3d.displayserver.desktop2d.IconGlyphLibrary;
import org.jdesktop.lg3d.displayserver.desktop2d.IconPack;
import org.jdesktop.lg3d.displayserver.desktop2d.IconPackManager;
import org.jdesktop.lg3d.displayserver.desktop2d.IconPackManager.AppIconTarget;
import org.jdesktop.lg3d.displayserver.desktop2d.IconRecipe;
import org.jdesktop.lg3d.displayserver.desktop2d.IconRecipe.Preset;
import org.jdesktop.lg3d.displayserver.desktop2d.IconRecipe.Source;

/**
 * A modal <em>icon studio</em> that lets the user compose a new icon pack by
 * driving the desktop's bundled IconManager library to the full extent of its
 * vocabulary. Applications the 2D desktop can override are listed down the left
 * (from {@link IconPackManager#appIconTargets()}); the glyph catalogue -
 * categories, a search box and a live-preview glyph list - sits in the middle;
 * and an appearance studio occupies the right: a {@link Preset} list plus the
 * {@link Source} (a bundled glyph or any generative IconManager tile), tint and
 * accent colours, {@link IconStyle}/{@link PatternType}/{@link StatusType}
 * selectors, the {@link IconEffect} post-processors, and sliders for tint
 * strength, corner radius, brightness, contrast and rotation, with shadow and
 * flip toggles. A large preview reflects every change live.
 *
 * <p>The appearance defines one coherent <em>pack style</em>; the user assigns a
 * glyph per application and, on <em>Save Pack&hellip;</em>, each assignment is
 * rendered through {@link IconGlyphLibrary#render} and persisted by
 * {@link IconPackManager#saveUserPack}. Like the rest of the control center,
 * every enumeration is a {@link JList} selector (never a combo box) so the panel
 * keeps working when it is hosted offscreen in a {@code SwingNode}.
 * {@link #show(Component)} returns {@code null} when the user cancels.</p>
 */
final class IconPackBuilderDialog extends JDialog {

    /** A glyph assigned to one application, with the label generative tiles draw. */
    private record GlyphRef(IconCategory category, String glyph, String label) {
    }

    /** One catalogue entry: a glyph base name and the category it came from. */
    private record GlyphItem(IconCategory category, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** A cached per-application preview, tagged with the style version it drew. */
    private record Cached(Icon icon, int version) {
    }

    private static final int PREVIEW_EDGE = 16;
    private static final int BIG_PREVIEW_EDGE = 96;
    private static final String NONE = "\u2014 None \u2014";
    private static final String AUTO = "\u2014 Auto \u2014";

    private final DefaultListModel<AppIconTarget> appModel = new DefaultListModel<>();
    private final DefaultListModel<IconCategory> categoryModel = new DefaultListModel<>();
    private final DefaultListModel<GlyphItem> glyphModel = new DefaultListModel<>();
    private final DefaultListModel<Preset> presetModel = new DefaultListModel<>();
    private final DefaultListModel<Source> sourceModel = new DefaultListModel<>();
    private final DefaultListModel<Object> primaryModel = new DefaultListModel<>();
    private final DefaultListModel<Object> accentModel = new DefaultListModel<>();
    private final DefaultListModel<Object> styleModel = new DefaultListModel<>();
    private final DefaultListModel<Object> patternModel = new DefaultListModel<>();
    private final DefaultListModel<Object> statusModel = new DefaultListModel<>();
    private final DefaultListModel<IconEffect> effectModel = new DefaultListModel<>();

    private final JList<AppIconTarget> appList = new JList<>(appModel);
    private final JList<IconCategory> categoryList = new JList<>(categoryModel);
    private final JList<GlyphItem> glyphList = new JList<>(glyphModel);
    private final JList<Preset> presetList = new JList<>(presetModel);
    private final JList<Source> sourceList = new JList<>(sourceModel);
    private final JList<Object> primaryList = new JList<>(primaryModel);
    private final JList<Object> accentList = new JList<>(accentModel);
    private final JList<Object> styleList = new JList<>(styleModel);
    private final JList<Object> patternList = new JList<>(patternModel);
    private final JList<Object> statusList = new JList<>(statusModel);
    private final JList<IconEffect> effectList = new JList<>(effectModel);

    private final GlyphRenderer glyphRenderer = new GlyphRenderer();
    private final JTextField searchField = new JTextField(10);
    private final JLabel bigPreview = new JLabel(" ", SwingConstants.CENTER);
    private final JLabel statusLabel = new JLabel(" ");

    private final JSlider tintSlider = slider(0, 100, 100);
    private final JSlider cornerSlider = slider(0, 24, 0);
    private final JSlider brightSlider = slider(-100, 100, 0);
    private final JSlider contrastSlider = slider(-100, 100, 0);
    private final JSlider rotateSlider = slider(0, 360, 0);
    private final JCheckBox shadowCheck = new JCheckBox("Shadow");
    private final JCheckBox flipHCheck = new JCheckBox("Flip H");
    private final JCheckBox flipVCheck = new JCheckBox("Flip V");

    /** baseName -> assigned glyph; the pack the user is composing. */
    private final Map<String, GlyphRef> assignments = new LinkedHashMap<>();
    private final Map<String, Cached> previewCache = new LinkedHashMap<>();

    /** Bumped on any appearance change so cached previews re-render. */
    private int styleVersion;
    /** The resolved tint colours (may be a preset colour with no named match). */
    private Color primaryColor;
    private Color accentColor;
    /** Guards list/slider listeners while a preset is being applied. */
    private boolean adjusting;

    private IconPack result;

    private IconPackBuilderDialog(final Component parent) {
        super(SwingUtilities.getWindowAncestor(parent), "Icon Pack Studio",
                ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        initComponents();
        populateApps();
        populateCategories();
        populateStudio();
        applyPreset(Preset.FLAT);
        pack();
        setLocationRelativeTo(parent);
        refreshPreview();
    }

    /**
     * Shows the studio modally and returns the newly created pack, or
     * {@code null} when the user cancels or nothing could be saved.
     */
    static IconPack show(final Component parent) {
        IconPackBuilderDialog dialog = new IconPackBuilderDialog(parent);
        dialog.setVisible(true);
        return dialog.result;
    }

    private static JSlider slider(final int min, final int max, final int value) {
        JSlider s = new JSlider(min, max, value);
        s.setMajorTickSpacing((max - min) / 2);
        s.setPaintTicks(true);
        return s;
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private void initComponents() {
        JPanel left = buildAppsPanel();
        JPanel middle = buildGlyphPanel();
        JScrollPane right = buildStudioPanel();

        JSplitPane rightSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, middle, right);
        rightSplit.setDividerLocation(360);
        rightSplit.setResizeWeight(0.5);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, rightSplit);
        split.setDividerLocation(210);
        split.setResizeWeight(0.25);

        JButton save = new JButton("Save Pack\u2026");
        save.addActionListener(e -> savePack());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        buttons.add(save);
        buttons.add(cancel);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 10, 4, 10));
        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.add(statusLabel, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        content.add(split, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(save);
    }

    private JPanel buildAppsPanel() {
        appList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        appList.setCellRenderer(new AppRenderer());
        appList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                refreshPreview();
            }
        });
        JScrollPane appScroll = new JScrollPane(appList);
        appScroll.setPreferredSize(new Dimension(200, 420));
        appScroll.setBorder(BorderFactory.createTitledBorder("Applications"));

        JButton assign = new JButton("Assign");
        assign.setToolTipText("Use the selected icon + style for the selected application");
        assign.addActionListener(e -> assignSelected());
        JButton clear = new JButton("Clear");
        clear.setToolTipText("Remove the assigned icon from the selected application");
        clear.addActionListener(e -> clearSelected());
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(assign);
        row.add(clear);

        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.add(appScroll, BorderLayout.CENTER);
        left.add(row, BorderLayout.SOUTH);
        return left;
    }

    private JPanel buildGlyphPanel() {
        categoryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        categoryList.setCellRenderer(new CategoryRenderer());
        categoryList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !adjusting) {
                listCategory();
            }
        });
        JScrollPane categoryScroll = new JScrollPane(categoryList);
        categoryScroll.setPreferredSize(new Dimension(120, 420));

        glyphList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        glyphList.setVisibleRowCount(16);
        glyphList.setCellRenderer(glyphRenderer);
        glyphList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                refreshPreview();
            }
        });
        JScrollPane glyphScroll = new JScrollPane(glyphList);
        glyphScroll.setPreferredSize(new Dimension(220, 420));
        glyphScroll.setBorder(BorderFactory.createTitledBorder("Icons"));

        JButton search = new JButton("Search");
        search.addActionListener(e -> runSearch());
        JButton all = new JButton("All");
        all.setToolTipText("Clear the search and list the selected category");
        all.addActionListener(e -> {
            searchField.setText("");
            listCategory();
        });
        JPanel searchRow = new JPanel(new BorderLayout(4, 0));
        searchRow.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));
        searchRow.add(searchField, BorderLayout.CENTER);
        JPanel searchButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        searchButtons.add(search);
        searchButtons.add(all);
        searchRow.add(searchButtons, BorderLayout.EAST);

        JPanel picker = new JPanel(new BorderLayout(6, 0));
        picker.add(searchRow, BorderLayout.NORTH);
        picker.add(categoryScroll, BorderLayout.WEST);
        picker.add(glyphScroll, BorderLayout.CENTER);
        return picker;
    }

    private JScrollPane buildStudioPanel() {
        presetList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        presetList.setVisibleRowCount(4);
        presetList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !adjusting) {
                Preset p = presetList.getSelectedValue();
                if (p != null) {
                    applyPreset(p);
                }
            }
        });

        sourceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sourceList.setVisibleRowCount(5);
        sourceList.setCellRenderer(new SourceRenderer());
        sourceList.addListSelectionListener(e -> onChange());

        primaryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        primaryList.setVisibleRowCount(5);
        primaryList.setCellRenderer(new ColorRenderer());
        primaryList.addListSelectionListener(e -> {
            if (!adjusting) {
                primaryColor = colorOf(primaryList.getSelectedValue());
                onChange();
            }
        });
        accentList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        accentList.setVisibleRowCount(5);
        accentList.setCellRenderer(new ColorRenderer());
        accentList.addListSelectionListener(e -> {
            if (!adjusting) {
                accentColor = colorOf(accentList.getSelectedValue());
                onChange();
            }
        });

        styleList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        styleList.setVisibleRowCount(4);
        styleList.setCellRenderer(new EnumRenderer());
        styleList.addListSelectionListener(e -> onChange());
        patternList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        patternList.setVisibleRowCount(4);
        patternList.setCellRenderer(new EnumRenderer());
        patternList.addListSelectionListener(e -> onChange());
        statusList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        statusList.setVisibleRowCount(4);
        statusList.setCellRenderer(new EnumRenderer());
        statusList.addListSelectionListener(e -> onChange());

        effectList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        effectList.setVisibleRowCount(4);
        effectList.setCellRenderer(new EnumRenderer());
        effectList.addListSelectionListener(e -> onChange());

        for (JSlider s : new JSlider[] {tintSlider, cornerSlider, brightSlider,
            contrastSlider, rotateSlider}) {
            s.addChangeListener(e -> onChange());
        }
        for (JCheckBox c : new JCheckBox[] {shadowCheck, flipHCheck, flipVCheck}) {
            c.addActionListener(e -> onChange());
        }

        JPanel sliders = new JPanel(new GridLayout(0, 1, 0, 2));
        sliders.add(titled("Tint", tintSlider));
        sliders.add(titled("Corner", cornerSlider));
        sliders.add(titled("Bright", brightSlider));
        sliders.add(titled("Contrast", contrastSlider));
        sliders.add(titled("Rotate", rotateSlider));
        JPanel toggles = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        toggles.add(shadowCheck);
        toggles.add(flipHCheck);
        toggles.add(flipVCheck);

        bigPreview.setPreferredSize(new Dimension(BIG_PREVIEW_EDGE + 24, BIG_PREVIEW_EDGE + 24));
        bigPreview.setBorder(BorderFactory.createTitledBorder("Preview"));

        JPanel studio = new JPanel();
        studio.setLayout(new BoxLayout(studio, BoxLayout.Y_AXIS));
        studio.add(listBlock("Preset", presetList, 4));
        studio.add(listBlock("Source", sourceList, 5));
        studio.add(twoLists("Colour", "Tint", primaryList, "Accent", accentList));
        studio.add(twoLists("Style / Pattern", "Style", styleList, "Pattern", patternList));
        studio.add(listBlock("Status", statusList, 3));
        studio.add(listBlock("Effects", effectList, 4));
        JPanel shape = new JPanel(new BorderLayout(0, 4));
        shape.setBorder(BorderFactory.createTitledBorder("Shape"));
        shape.add(sliders, BorderLayout.CENTER);
        shape.add(toggles, BorderLayout.SOUTH);
        studio.add(shape);
        studio.add(Box.createVerticalStrut(6));
        studio.add(bigPreview);

        JScrollPane scroll = new JScrollPane(studio);
        scroll.setPreferredSize(new Dimension(300, 420));
        scroll.setBorder(BorderFactory.createTitledBorder("Style studio"));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private static JPanel titled(final String label, final JSlider slider) {
        JPanel p = new JPanel(new BorderLayout(4, 0));
        p.add(new JLabel(label), BorderLayout.WEST);
        p.add(slider, BorderLayout.CENTER);
        return p;
    }

    private static JPanel listBlock(final String title, final JList<?> list, final int rows) {
        list.setVisibleRowCount(rows);
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createTitledBorder(title));
        JPanel p = new JPanel(new BorderLayout());
        p.add(sp, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        return p;
    }

    private static JPanel twoLists(final String title, final String leftTitle,
            final JList<?> left, final String rightTitle, final JList<?> right) {
        JScrollPane l = new JScrollPane(left);
        l.setBorder(BorderFactory.createTitledBorder(leftTitle));
        JScrollPane r = new JScrollPane(right);
        r.setBorder(BorderFactory.createTitledBorder(rightTitle));
        JPanel grid = new JPanel(new GridLayout(1, 2, 4, 0));
        grid.add(l);
        grid.add(r);
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.add(grid, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        return p;
    }

    // ------------------------------------------------------------------
    // Population
    // ------------------------------------------------------------------

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
        listCategory();
    }

    private void populateStudio() {
        for (Preset p : Preset.values()) {
            presetModel.addElement(p);
        }
        for (Source s : Source.values()) {
            sourceModel.addElement(s);
        }
        sourceList.setSelectedIndex(0);
        fillColors(primaryModel);
        fillColors(accentModel);
        fillEnum(styleModel, IconGlyphLibrary.styles());
        fillEnum(patternModel, IconGlyphLibrary.patterns());
        fillEnum(statusModel, IconGlyphLibrary.statuses());
        for (IconEffect fx : IconGlyphLibrary.effects()) {
            effectModel.addElement(fx);
        }
    }

    private static void fillColors(final DefaultListModel<Object> model) {
        model.clear();
        model.addElement(NONE);
        for (IconColor c : IconGlyphLibrary.colors()) {
            model.addElement(c);
        }
    }

    private static void fillEnum(final DefaultListModel<Object> model, final List<? extends Enum<?>> values) {
        model.clear();
        model.addElement(AUTO);
        for (Enum<?> v : values) {
            model.addElement(v);
        }
    }

    /** Lists every glyph in the selected category (clears any search). */
    private void listCategory() {
        glyphModel.clear();
        IconCategory category = categoryList.getSelectedValue();
        if (category == null) {
            return;
        }
        glyphRenderer.setCategory(category);
        for (String name : IconGlyphLibrary.glyphNames(category)) {
            glyphModel.addElement(new GlyphItem(category, name));
        }
        if (!glyphModel.isEmpty()) {
            glyphList.setSelectedIndex(0);
        }
    }

    /** Lists catalogue-wide glyph base names matching the search box. */
    private void runSearch() {
        String query = searchField.getText();
        if (query == null || query.isBlank()) {
            listCategory();
            return;
        }
        glyphModel.clear();
        Map<IconCategory, List<String>> hits = IconGlyphLibrary.searchGlyphs(query);
        for (Map.Entry<IconCategory, List<String>> entry : hits.entrySet()) {
            for (String name : entry.getValue()) {
                glyphModel.addElement(new GlyphItem(entry.getKey(), name));
            }
        }
        glyphRenderer.setCategory(null);
        if (!glyphModel.isEmpty()) {
            glyphList.setSelectedIndex(0);
            statusLabel.setText(hits.size() + " categories matched \"" + query.trim() + "\"");
        } else {
            statusLabel.setText("No icons matched \"" + query.trim() + "\"");
        }
        refreshPreview();
    }

    // ------------------------------------------------------------------
    // Appearance <-> controls
    // ------------------------------------------------------------------

    /** The appearance defined by the studio controls, with no glyph/label yet. */
    private IconRecipe currentTemplate() {
        IconRecipe.Builder b = IconRecipe.builder();
        Source source = sourceList.getSelectedValue();
        b.source(source != null ? source : Source.GLYPH);
        b.primary(primaryColor).secondary(accentColor);
        b.tintAmount(tintSlider.getValue() / 100f);
        b.cornerRadius(cornerSlider.getValue());
        b.brightness(brightSlider.getValue() / 100f);
        b.contrast(contrastSlider.getValue() / 100f);
        b.rotation(rotateSlider.getValue());
        b.shadow(shadowCheck.isSelected());
        b.flipHorizontal(flipHCheck.isSelected());
        b.flipVertical(flipVCheck.isSelected());
        b.style(enumOf(styleList.getSelectedValue(), IconStyle.class));
        b.pattern(enumOf(patternList.getSelectedValue(), PatternType.class));
        b.status(enumOf(statusList.getSelectedValue(), StatusType.class));
        List<IconEffect> fx = effectList.getSelectedValuesList();
        b.effects(fx.toArray(new IconEffect[0]));
        return b.build();
    }

    /** Pushes a preset's template into every studio control. */
    private void applyPreset(final Preset preset) {
        adjusting = true;
        try {
            IconRecipe t = preset.template();
            selectValue(presetList, preset);
            selectValue(sourceList, t.source());
            primaryColor = t.primary();
            accentColor = t.secondary();
            selectColor(primaryList, t.primary());
            selectColor(accentList, t.secondary());
            selectEnum(styleList, t.style());
            selectEnum(patternList, t.pattern());
            selectEnum(statusList, t.status());
            tintSlider.setValue(Math.round(t.tintAmount() * 100));
            cornerSlider.setValue(t.cornerRadius());
            brightSlider.setValue(Math.round(t.brightness() * 100));
            contrastSlider.setValue(Math.round(t.contrast() * 100));
            rotateSlider.setValue((int) Math.round(t.rotation()));
            shadowCheck.setSelected(t.shadow());
            flipHCheck.setSelected(t.flipHorizontal());
            flipVCheck.setSelected(t.flipVertical());
            effectList.clearSelection();
            for (int i = 0; i < effectModel.size(); i++) {
                if (t.effects().contains(effectModel.get(i))) {
                    effectList.addSelectionInterval(i, i);
                }
            }
        } finally {
            adjusting = false;
        }
        styleVersion++;
        refreshPreview();
    }

    private static <T> void selectValue(final JList<T> list, final T value) {
        for (int i = 0; i < list.getModel().getSize(); i++) {
            if (list.getModel().getElementAt(i) == value) {
                list.setSelectedIndex(i);
                return;
            }
        }
        list.clearSelection();
    }

    private static void selectColor(final JList<Object> list, final Color color) {
        if (color == null) {
            list.setSelectedIndex(0);
            return;
        }
        for (int i = 0; i < list.getModel().getSize(); i++) {
            Object v = list.getModel().getElementAt(i);
            if (v instanceof IconColor && color.equals(IconGlyphLibrary.color((IconColor) v))) {
                list.setSelectedIndex(i);
                return;
            }
        }
        list.clearSelection();
    }

    private static void selectEnum(final JList<Object> list, final Enum<?> value) {
        if (value == null) {
            list.setSelectedIndex(0);
            return;
        }
        selectValue(list, value);
    }

    private static Color colorOf(final Object value) {
        return (value instanceof IconColor) ? IconGlyphLibrary.color((IconColor) value) : null;
    }

    private static <E extends Enum<E>> E enumOf(final Object value, final Class<E> type) {
        return type.isInstance(value) ? type.cast(value) : null;
    }

    /** Called by any appearance control: re-render the previews. */
    private void onChange() {
        if (adjusting) {
            return;
        }
        styleVersion++;
        refreshPreview();
    }

    // ------------------------------------------------------------------
    // Assignment + preview
    // ------------------------------------------------------------------

    private void assignSelected() {
        AppIconTarget app = appList.getSelectedValue();
        GlyphItem item = glyphList.getSelectedValue();
        if (app == null || item == null) {
            statusLabel.setText("Select an application and an icon first.");
            return;
        }
        assignments.put(app.baseName(),
                new GlyphRef(item.category(), item.name(), initials(app.appName())));
        previewCache.remove(app.baseName());
        appList.repaint();
        statusLabel.setText(app.appName() + " \u2192 " + item.name()
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

    private void refreshPreview() {
        GlyphItem item = glyphList.getSelectedValue();
        IconCategory category = (item != null) ? item.category()
                : categoryList.getSelectedValue();
        String glyph = (item != null) ? item.name() : null;
        IconRecipe recipe = currentTemplate().forApp(category, glyph, "Ag");
        Icon icon = IconGlyphLibrary.renderIcon(recipe, BIG_PREVIEW_EDGE);
        bigPreview.setIcon(icon);
        bigPreview.setText(icon == null ? "no preview" : " ");
        appList.repaint();
    }

    /** The live preview for one application's assigned glyph, at the current style. */
    private Icon assignedPreview(final String baseName) {
        GlyphRef ref = assignments.get(baseName);
        if (ref == null) {
            return null;
        }
        Cached cached = previewCache.get(baseName);
        if (cached != null && cached.version() == styleVersion) {
            return cached.icon();
        }
        IconRecipe recipe = currentTemplate().forApp(ref.category(), ref.glyph(), ref.label());
        Icon icon = IconGlyphLibrary.renderIcon(recipe, PREVIEW_EDGE);
        if (icon != null) {
            previewCache.put(baseName, new Cached(icon, styleVersion));
        }
        return icon;
    }

    private void savePack() {
        if (assignments.isEmpty()) {
            warn("Assign at least one icon first.");
            return;
        }
        String name = JOptionPane.showInputDialog(this, "Pack name:", "Icon Pack Studio",
                JOptionPane.QUESTION_MESSAGE);
        if (name == null || name.isBlank()) {
            return;
        }
        if (IconPackManager.sanitizePackId(name).isEmpty()) {
            warn("That name has no usable letters or digits.");
            return;
        }
        IconRecipe template = currentTemplate();
        Map<String, BufferedImage> icons = new LinkedHashMap<>();
        for (Map.Entry<String, GlyphRef> entry : assignments.entrySet()) {
            GlyphRef ref = entry.getValue();
            IconRecipe recipe = template.forApp(ref.category(), ref.glyph(), ref.label());
            BufferedImage image = IconGlyphLibrary.render(recipe, IconPackManager.PACK_ICON_EDGE);
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
        JOptionPane.showMessageDialog(this, message, "Icon Pack Studio",
                JOptionPane.WARNING_MESSAGE);
    }

    /** Up to two upper-case initials from an application name. */
    static String initials(final String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String[] words = name.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            out.append(Character.toUpperCase(word.charAt(0)));
            if (out.length() == 2) {
                break;
            }
        }
        return out.toString();
    }

    private static String sourceLabel(final Source source) {
        if (source == null) {
            return "";
        }
        return switch (source) {
            case GLYPH -> "Bundled glyph";
            case GLASS -> "Glass tile";
            case COLOR -> "Colour tile";
            case GRADIENT -> "Gradient tile";
            case CIRCULAR -> "Circular tile";
            case NEUMORPHISM -> "Neumorphism";
            case TEXT -> "Text tile";
            case PATTERN -> "Pattern tile";
            case STATUS -> "Status dot";
        };
    }

    // ------------------------------------------------------------------
    // Renderers
    // ------------------------------------------------------------------

    /** Renders an application with its live assigned-glyph preview. */
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
            implements ListCellRenderer<GlyphItem> {

        private final Map<String, Icon> cache = new LinkedHashMap<>();
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
        public Component getListCellRendererComponent(final JList<? extends GlyphItem> list,
                final GlyphItem value, final int index, final boolean isSelected,
                final boolean cellHasFocus) {
            setText(value.name());
            setIcon(preview(value));
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            return this;
        }

        private Icon preview(final GlyphItem value) {
            IconCategory cat = (category != null) ? category : value.category();
            String key = cat + "/" + value.name();
            Icon cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            Icon icon = IconGlyphLibrary.loadIcon(cat, value.name(), PREVIEW_EDGE);
            if (icon != null) {
                cache.put(key, icon);
            }
            return icon;
        }
    }

    /** Renders an {@link IconColor} with a colour swatch, or the sentinel text. */
    private static final class ColorRenderer extends JLabel
            implements ListCellRenderer<Object> {

        ColorRenderer() {
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        }

        @Override
        public Component getListCellRendererComponent(final JList<? extends Object> list,
                final Object value, final int index, final boolean isSelected,
                final boolean cellHasFocus) {
            if (value instanceof IconColor) {
                IconColor named = (IconColor) value;
                setText(IconGlyphLibrary.label(named));
                Color swatch = IconGlyphLibrary.color(named);
                setIcon(swatch == null ? null : new ColorSwatch(swatch));
            } else {
                setText(String.valueOf(value));
                setIcon(null);
            }
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            return this;
        }
    }

    /** A small square colour swatch for the colour lists. */
    private static final class ColorSwatch implements Icon {

        private final Color color;

        ColorSwatch(final Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(final Component c, final java.awt.Graphics g,
                final int x, final int y) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                g2.setColor(color);
                g2.fillRect(x, y, 12, 12);
                g2.setColor(Color.BLACK);
                g2.drawRect(x, y, 12, 12);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return 13;
        }

        @Override
        public int getIconHeight() {
            return 13;
        }
    }

    /** Renders any IconManager enum (or the Auto sentinel) as a friendly label. */
    private static final class EnumRenderer extends JLabel
            implements ListCellRenderer<Object> {

        EnumRenderer() {
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        }

        @Override
        public Component getListCellRendererComponent(final JList<? extends Object> list,
                final Object value, final int index, final boolean isSelected,
                final boolean cellHasFocus) {
            setText((value instanceof Enum)
                    ? IconGlyphLibrary.label((Enum<?>) value) : String.valueOf(value));
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            return this;
        }
    }

    /** Renders a {@link Source} as its friendly label. */
    private static final class SourceRenderer extends JLabel
            implements ListCellRenderer<Source> {

        SourceRenderer() {
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        }

        @Override
        public Component getListCellRendererComponent(final JList<? extends Source> list,
                final Source value, final int index, final boolean isSelected,
                final boolean cellHasFocus) {
            setText(sourceLabel(value));
            setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            return this;
        }
    }
}
