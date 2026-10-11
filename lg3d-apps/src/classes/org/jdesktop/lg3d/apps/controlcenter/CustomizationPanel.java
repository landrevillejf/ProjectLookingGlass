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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.MouseInputAdapter;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D;
import org.jdesktop.lg3d.displayserver.desktop2d.DesktopTransparency;
import org.jdesktop.lg3d.displayserver.desktop2d.IconGlyphLibrary;
import org.jdesktop.lg3d.displayserver.desktop2d.IconPack;
import org.jdesktop.lg3d.displayserver.desktop2d.IconPackManager;
import org.jdesktop.lg3d.displayserver.desktop2d.MetalThemeManager;
import org.jdesktop.lg3d.displayserver.desktop2d.MetalThemeSpec;
import org.jdesktop.lg3d.displayserver.desktop2d.TransparencySurface;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;

/**
 * Personalisation ("Customization") category for the conventional 2D desktop:
 * the independent look-and-feel sections that go <em>beyond</em> the wallpaper -
 * the colour theme (Metal palette plus a single accent colour), the icon pack,
 * the surface transparency and, in later phases, the window-decoration style.
 * Each section is self-contained: it reads and writes its own {@link
 * DesktopConfig} preference and applies live, so they never have to be changed
 * together.
 *
 * <p>The colour-theme manager lived in {@link AppearancePanel} before; it is
 * hosted here so every personalisation control sits in one category, while
 * Appearance keeps the wallpaper and slideshow. Every section is gated on
 * {@link Desktop2D#MODE_PROPERTY} - on the 3D desktop, whose window chrome is
 * driven by the scene graph rather than Swing, the panel explains that these
 * controls apply to the 2D shell only.</p>
 *
 * <p>List selectors are used throughout (never a combo box or radio buttons) so
 * the panel keeps working when the control center is hosted offscreen in a
 * {@code SwingNode}.</p>
 */
public class CustomizationPanel implements ControlPanel {

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final JLabel statusLabel = new JLabel(" ");

    /** Colour-theme manager: theme names plus the parallel specs. */
    private final DefaultListModel<String> themeNames = new DefaultListModel<>();
    private final JList<String> themeList = new JList<>(themeNames);
    private final List<MetalThemeSpec> themeSpecs = new ArrayList<>();

    /** Icon-pack selector: the available packs, default first. */
    private final DefaultListModel<IconPack> packModel = new DefaultListModel<>();
    private final JList<IconPack> packList = new JList<>(packModel);

    /**
     * Surface-opacity editors, one slider per {@link TransparencySurface}, in
     * enum order so the panel and the persisted settings always list the same
     * four surfaces in the same sequence.
     */
    private final Map<TransparencySurface, JSlider> opacitySliders =
            new LinkedHashMap<>();

    public CustomizationPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        if (Boolean.getBoolean(Desktop2D.MODE_PROPERTY)) {
            JPanel sections = new JPanel();
            sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
            sections.add(buildThemePanel());
            sections.add(buildIconPackPanel());
            sections.add(buildTransparencyPanel());
            // Later phases append their independent sections here (window
            // decoration) before the trailing glue.
            sections.add(Box.createVerticalGlue());
            root.add(sections, BorderLayout.CENTER);
            root.add(statusLabel, BorderLayout.SOUTH);
            loadThemeState();
            loadIconPackState();
            loadTransparencyState();
        } else {
            JLabel note = new JLabel(
                    "<html><center>Theme, icon pack, transparency and<br>"
                            + "window-decoration personalisation apply<br>"
                            + "to the conventional 2D desktop only.<br>"
                            + "The 3D desktop's surfaces are already<br>"
                            + "translucent by construction.</center></html>",
                    JLabel.CENTER);
            root.add(note, BorderLayout.CENTER);
        }
    }

    @Override
    public String displayName() {
        return "Customization";
    }

    @Override
    public javax.swing.Icon icon() {
        return null;
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void onShow() {
        if (Boolean.getBoolean(Desktop2D.MODE_PROPERTY)) {
            loadThemeState();
            loadIconPackState();
            loadTransparencyState();
        }
    }

    // ------------------------------------------------------------------
    // Colour theme (Metal palette + accent)
    // ------------------------------------------------------------------

    /**
     * Builds the theme section: a {@link JList} of the built-in themes (see
     * {@link MetalThemeManager#builtIns()}) plus any user-created themes, and
     * Apply / Accent colour / New / Delete / System Look buttons. Applying a
     * theme switches the 2D shell onto the Metal look-and-feel with the chosen
     * palette and persists the selection, so it is restored at the next
     * start-up; the accent button derives a whole theme from one colour.
     */
    private JComponent buildThemePanel() {
        themeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        themeList.setVisibleRowCount(6);
        JScrollPane themeScroll = new JScrollPane(themeList);
        themeScroll.setPreferredSize(new Dimension(180, 130));

        JButton applyTheme = new JButton("Apply");
        applyTheme.addActionListener(e -> applyTheme());
        JButton accent = new JButton("Accent colour...");
        accent.setToolTipText("Recolour the whole desktop from a single accent colour");
        accent.addActionListener(e -> chooseAccent());
        JButton newTheme = new JButton("New...");
        newTheme.addActionListener(e -> newTheme());
        JButton deleteTheme = new JButton("Delete");
        deleteTheme.addActionListener(e -> deleteTheme());
        JButton systemLook = new JButton("System Look");
        systemLook.setToolTipText("Revert to the native platform look and feel (GTK/Synth)");
        systemLook.addActionListener(e -> useSystemLook());

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(new JLabel("Theme:"));
        row.add(themeScroll);
        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 4));
        buttons.add(applyTheme);
        buttons.add(accent);
        buttons.add(newTheme);
        buttons.add(deleteTheme);
        buttons.add(systemLook);
        row.add(buttons);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(row, BorderLayout.CENTER);
        panel.setBorder(BorderFactory.createTitledBorder("Colour Theme (2D desktop)"));
        return panel;
    }

    /** Rebuilds the theme list and reflects the persisted selection. */
    private void loadThemeState() {
        themeNames.clear();
        themeSpecs.clear();
        for (MetalThemeSpec spec : MetalThemeManager.available()) {
            themeSpecs.add(spec);
            themeNames.addElement(spec.name());
        }
        String current = DesktopConfig.get().getMetalTheme();
        if (current == null || current.isBlank()) {
            // No Metal theme is active (the native platform look is showing),
            // so leave nothing selected rather than implying one is applied.
            themeList.clearSelection();
        } else {
            themeList.setSelectedValue(current, true);
        }
    }

    /** The spec behind the current list selection, or null. */
    private MetalThemeSpec selectedTheme() {
        int i = themeList.getSelectedIndex();
        return (i >= 0 && i < themeSpecs.size()) ? themeSpecs.get(i) : null;
    }

    private void applyTheme() {
        MetalThemeSpec spec = selectedTheme();
        if (spec == null) {
            warn("Select a theme first.");
            return;
        }
        // A named theme leads with its own palette, so drop any explicit accent
        // first (apply() persists both the theme name and the cleared accent).
        DesktopConfig.get().setAccentColor("");
        MetalThemeManager.apply(spec);
        statusLabel.setText("Theme applied: " + spec.name());
    }

    /**
     * Derives a whole theme from a single accent colour and applies it, so the
     * entire shell recolours at once rather than just the wallpaper. The accent
     * is persisted separately (it seeds the window-decoration title colour), and
     * the derived theme is stored as a user theme named "Accent" so the look
     * survives a restart. The colour dialog is native Swing and is only reachable
     * on the 2D desktop, where the control center is a real window rather than an
     * offscreen {@code SwingNode} texture.
     */
    private void chooseAccent() {
        Color accent = JColorChooser.showDialog(root,
                "Choose the desktop accent colour", MetalThemeManager.accentColor());
        if (accent == null) {
            return;
        }
        String hex = String.format("#%06x", accent.getRGB() & 0xFFFFFF);
        DesktopConfig.get().setAccentColor(hex);
        MetalThemeSpec spec = MetalThemeSpec.fromAccent("Accent", accent);
        MetalThemeManager.addCustom(spec);
        MetalThemeManager.apply(spec);
        loadThemeState();
        themeList.setSelectedValue(spec.name(), true);
        statusLabel.setText("Accent colour applied: " + hex.toUpperCase(Locale.ROOT));
    }

    /**
     * Reverts the 2D desktop to the native platform look-and-feel (GTK/Synth),
     * undoing any applied theme, and clears the persisted selection and accent so
     * the next start-up keeps the native look.
     */
    private void useSystemLook() {
        DesktopConfig.get().setAccentColor("");
        MetalThemeManager.applySystem();
        themeList.clearSelection();
        statusLabel.setText("Restored the system look and feel (native GTK/Synth)");
    }

    /**
     * Creates a user theme from a name and one accent colour (the rest of the
     * palette is derived), stores it, and selects it.
     */
    private void newTheme() {
        String name = JOptionPane.showInputDialog(root, "New theme name:",
                "New Theme", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) {
            return;
        }
        Color accent = JColorChooser.showDialog(root,
                "Choose the theme accent colour", new Color(0x66, 0x99, 0xCC));
        if (accent == null) {
            return;
        }
        MetalThemeSpec spec = MetalThemeSpec.fromAccent(name.trim(), accent);
        MetalThemeManager.addCustom(spec);
        loadThemeState();
        themeList.setSelectedValue(spec.name(), true);
        statusLabel.setText("Created theme: " + spec.name()
                + " - select it and press Apply");
    }

    /** Deletes the selected user theme (the built-ins cannot be removed). */
    private void deleteTheme() {
        MetalThemeSpec spec = selectedTheme();
        if (spec == null) {
            warn("Select a theme to delete.");
            return;
        }
        if (!MetalThemeManager.removeCustom(spec.name())) {
            warn("\"" + spec.name() + "\" is a built-in theme and cannot be deleted.");
            return;
        }
        loadThemeState();
        statusLabel.setText("Deleted theme: " + spec.name());
    }

    // ------------------------------------------------------------------
    // Icon pack
    // ------------------------------------------------------------------

    /**
     * Builds the icon-pack section: a {@link JList} of the available packs (the
     * default, the bundled packs and any imported pack - see
     * {@link IconPackManager#available()}) plus Apply / Import Pack / Use
     * Default buttons. Applying a pack re-resolves every 2D icon surface live
     * through {@link Desktop2D#applyIconPack}; the default pack keeps the
     * procedurally generated IconManager icons. A list selector (never a combo
     * box) keeps the panel working when it is hosted offscreen in a
     * {@code SwingNode}.
     */
    private JComponent buildIconPackPanel() {
        packList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        packList.setVisibleRowCount(5);
        JScrollPane packScroll = new JScrollPane(packList);
        packScroll.setPreferredSize(new Dimension(180, 110));

        JButton applyPack = new JButton("Apply");
        applyPack.addActionListener(e -> applyIconPack());
        JButton createPack = new JButton("Create Pack...");
        createPack.setToolTipText("Open the icon studio: compose a styled pack from the desktop's IconManager glyphs");
        createPack.addActionListener(e -> createIconPack());
        JButton importPack = new JButton("Import Pack...");
        importPack.setToolTipText("Import a folder of PNGs or a .zip as an icon pack");
        importPack.addActionListener(e -> importIconPack());
        JButton deletePack = new JButton("Delete Pack");
        deletePack.setToolTipText("Delete the selected user-created pack");
        deletePack.addActionListener(e -> deleteIconPack());
        JButton useDefault = new JButton("Use Default");
        useDefault.setToolTipText("Restore the generated icons (no pack)");
        useDefault.addActionListener(e -> useDefaultIconPack());

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(new JLabel("Icon pack:"));
        row.add(packScroll);
        JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 4));
        buttons.add(applyPack);
        buttons.add(createPack);
        buttons.add(importPack);
        buttons.add(deletePack);
        buttons.add(useDefault);
        row.add(buttons);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(row, BorderLayout.CENTER);
        panel.setBorder(BorderFactory.createTitledBorder("Icon Pack (2D desktop)"));
        return panel;
    }

    /** Rebuilds the pack list and reflects the persisted selection. */
    private void loadIconPackState() {
        packModel.clear();
        for (IconPack pack : IconPackManager.available()) {
            packModel.addElement(pack);
        }
        String current = DesktopConfig.get().getIconPack();
        for (int i = 0; i < packModel.size(); i++) {
            if (packModel.get(i).id().equals(current)) {
                packList.setSelectedIndex(i);
                return;
            }
        }
        // The persisted pack is gone (a removed import, say): show the default.
        packList.setSelectedIndex(packModel.isEmpty() ? -1 : 0);
    }

    /** Applies the selected pack, preserving any imported pack's location. */
    private void applyIconPack() {
        IconPack pack = packList.getSelectedValue();
        if (pack == null) {
            warn("Select an icon pack first.");
            return;
        }
        // Keep the imported folder/zip on record even when a bundled pack or the
        // default is chosen, so the imported pack stays listed.
        String dir = (pack.source() == IconPack.Source.IMPORTED)
                ? pack.location() : DesktopConfig.get().getIconPackDir();
        Desktop2D.applyIconPack(pack.id(), dir);
        loadIconPackState();
        statusLabel.setText("Icon pack applied: " + pack.displayName());
    }

    /**
     * Imports a folder of PNGs or a {@code .zip} as an icon pack and activates
     * it. The chooser is native Swing and is only reachable on the 2D desktop,
     * where the control center is a real window rather than an offscreen
     * {@code SwingNode} texture.
     */
    private void importIconPack() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose an icon-pack folder or .zip");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (chooser.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (file == null || !file.exists()) {
            return;
        }
        Desktop2D.applyIconPack(IconPack.IMPORTED_ID, file.getAbsolutePath());
        loadIconPackState();
        for (int i = 0; i < packModel.size(); i++) {
            if (IconPack.IMPORTED_ID.equals(packModel.get(i).id())) {
                packList.setSelectedIndex(i);
                break;
            }
        }
        statusLabel.setText("Imported icon pack: " + file.getName());
    }

    /**
     * Opens the icon-pack builder, which lets the user compose a new pack from
     * the desktop's own IconManager glyphs, then activates the created pack. The
     * builder is native Swing and is only reachable on the 2D desktop, where the
     * control center is a real window rather than an offscreen {@code SwingNode}
     * texture.
     */
    private void createIconPack() {
        if (!IconGlyphLibrary.isAvailable()) {
            warn("The IconManager glyph library is not available.");
            return;
        }
        if (IconPackManager.appIconTargets().isEmpty()) {
            warn("No applications were found to theme.");
            return;
        }
        IconPack pack = IconPackBuilderDialog.show(root);
        if (pack == null) {
            return;
        }
        // Activate the freshly created pack; any imported pack stays on record.
        Desktop2D.applyIconPack(pack.id(), DesktopConfig.get().getIconPackDir());
        loadIconPackState();
        statusLabel.setText("Created icon pack: " + pack.displayName());
    }

    /**
     * Deletes the selected user-created pack. Only {@link IconPack.Source#USER}
     * packs (folders under {@code ~/.config/lg3d/icon-packs}) can be deleted;
     * bundled and imported packs are left alone. When the deleted pack was the
     * active one, the desktop falls back to the generated icons.
     */
    private void deleteIconPack() {
        IconPack pack = packList.getSelectedValue();
        if (pack == null || pack.source() != IconPack.Source.USER) {
            warn("Select a user-created pack to delete.");
            return;
        }
        int choice = JOptionPane.showConfirmDialog(root,
                "Delete the icon pack \"" + pack.displayName() + "\"?", "Customization",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        boolean deleted = IconPackManager.deleteUserPack(pack.id());
        if (deleted && pack.id().equals(DesktopConfig.get().getIconPack())) {
            Desktop2D.applyIconPack("", DesktopConfig.get().getIconPackDir());
        }
        loadIconPackState();
        statusLabel.setText(deleted ? "Deleted icon pack: " + pack.displayName()
                : "Could not delete the icon pack.");
    }

    /** Restores the generated icons (no pack), keeping any import on record. */
    private void useDefaultIconPack() {
        Desktop2D.applyIconPack("", DesktopConfig.get().getIconPackDir());
        loadIconPackState();
        statusLabel.setText("Icon pack: default (generated icons)");
    }

    // ------------------------------------------------------------------
    // Surface transparency
    // ------------------------------------------------------------------

    /**
     * Builds the transparency section: one {@link JSlider} per 2D-desktop surface
     * (windows, taskbar, widgets, notifications) setting how solid that surface
     * paints, from {@link DesktopConfig#MIN_SURFACE_OPACITY} (maximally faded,
     * still readable) to 100 percent (fully solid, the default and the historical
     * look). The four are independent - translucent windows do not fade the bar -
     * and each takes effect as soon as the slider is released: the value is
     * persisted and pushed live into the running shell through {@link
     * DesktopTransparency#applyOpacity}, so there is no Apply button to forget.
     *
     * <p>Sliders are used rather than a spin field or a combo because they are
     * offscreen-safe: the control center keeps working when hosted in a {@code
     * SwingNode} on the 3D desktop, where popup-heavy controls do not.</p>
     */
    private JComponent buildTransparencyPanel() {
        JPanel rows = new JPanel(new GridLayout(0, 1, 0, 6));
        for (TransparencySurface surface : TransparencySurface.values()) {
            rows.add(opacityRow(surface));
        }
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(rows, BorderLayout.CENTER);
        panel.setBorder(BorderFactory.createTitledBorder(
                "Transparency (2D desktop)"));
        return panel;
    }

    /**
     * The label, slider and percentage readout for one surface. Applied on
     * release rather than on every tick: a full-desktop repaint per drag pixel
     * would make the control feel sluggish, while the released value lands the
     * same way with one repaint.
     */
    private JComponent opacityRow(TransparencySurface surface) {
        JSlider slider = new JSlider(DesktopConfig.MIN_SURFACE_OPACITY,
                DesktopConfig.MAX_SURFACE_OPACITY,
                DesktopConfig.DEFAULT_SURFACE_OPACITY);
        slider.setToolTipText(hintFor(surface));
        JLabel value = new JLabel("100%", SwingConstants.RIGHT);
        value.setPreferredSize(new Dimension(42, 20));
        MouseInputAdapter applyOnRelease = new MouseInputAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                applyOpacity(surface, slider, value);
            }
        };
        slider.addMouseListener(applyOnRelease);
        slider.addMouseMotionListener(applyOnRelease);
        opacitySliders.put(surface, slider);

        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.add(new JLabel(nounFor(surface) + ":"), BorderLayout.WEST);
        row.add(slider, BorderLayout.CENTER);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    /** Re-reads the persisted opacities into the sliders. */
    private void loadTransparencyState() {
        DesktopConfig cfg = DesktopConfig.get();
        for (Map.Entry<TransparencySurface, JSlider> entry
                : opacitySliders.entrySet()) {
            entry.getValue().setValue(entry.getKey().opacityOf(cfg));
        }
    }

    /**
     * Persists and applies one surface's opacity, then reports it in the row and
     * the panel's status line. A live-apply that is a no-op on a 3D session still
     * stores the value, so the next 2D start-up picks it up.
     */
    private void applyOpacity(TransparencySurface surface, JSlider slider,
                              JLabel value) {
        int percent = slider.getValue();
        DesktopTransparency.applyOpacity(surface, percent);
        value.setText(percent + "%");
        statusLabel.setText("Faded " + nounFor(surface) + " to " + percent + "%");
    }

    /** The surface's name in sentence case, for a row label and the status line. */
    private static String nounFor(TransparencySurface surface) {
        switch (surface) {
            case WINDOW: return "windows";
            case TASKBAR: return "the taskbar";
            case WIDGET: return "widgets";
            case NOTIFICATION: return "notifications";
            default: return surface.name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * What fading each surface actually does, because the four differ: a window
     * or a widget fades whole, while the bar and a toast fade only their backdrop
     * and keep their text at full strength. The tooltip states it so a user is not
     * surprised that a faded taskbar still reads normally.
     */
    private static String hintFor(TransparencySurface surface) {
        switch (surface) {
            case WINDOW:
                return "Fade whole windows - title bar and content - over the wallpaper";
            case TASKBAR:
                return "Fade the taskbar's backdrop; its buttons and clock stay readable";
            case WIDGET:
                return "Fade the desktop widget cards over the wallpaper";
            case NOTIFICATION:
                return "Fade a notification's card; its title and text stay readable";
            default:
                return null;
        }
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(root, message, "Customization",
                JOptionPane.WARNING_MESSAGE);
    }
}
