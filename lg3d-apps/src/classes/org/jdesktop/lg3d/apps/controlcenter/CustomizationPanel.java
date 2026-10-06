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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D;
import org.jdesktop.lg3d.displayserver.desktop2d.MetalThemeManager;
import org.jdesktop.lg3d.displayserver.desktop2d.MetalThemeSpec;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;

/**
 * Personalisation ("Customization") category for the conventional 2D desktop:
 * the independent look-and-feel sections that go <em>beyond</em> the wallpaper -
 * the colour theme (Metal palette plus a single accent colour), and, in later
 * phases, the icon pack and the window-decoration style. Each section is
 * self-contained: it reads and writes its own {@link DesktopConfig} preference
 * and applies live, so they never have to be changed together.
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

    public CustomizationPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        if (Boolean.getBoolean(Desktop2D.MODE_PROPERTY)) {
            JPanel sections = new JPanel();
            sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
            sections.add(buildThemePanel());
            // Later phases append their independent sections here (icon pack,
            // window decoration) before the trailing glue.
            sections.add(Box.createVerticalGlue());
            root.add(sections, BorderLayout.CENTER);
            root.add(statusLabel, BorderLayout.SOUTH);
            loadThemeState();
        } else {
            JLabel note = new JLabel(
                    "<html><center>Theme, icon pack and window-decoration<br>"
                            + "personalisation apply to the conventional 2D<br>"
                            + "desktop only.</center></html>",
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

    private void warn(String message) {
        JOptionPane.showMessageDialog(root, message, "Customization",
                JOptionPane.WARNING_MESSAGE);
    }
}
