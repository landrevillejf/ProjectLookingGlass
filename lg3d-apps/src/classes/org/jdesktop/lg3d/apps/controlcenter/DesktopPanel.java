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
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import org.jdesktop.lg3d.apps.TitledSwingWindow;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D;
import org.jdesktop.lg3d.scenemanager.utils.event.DesktopConfigChangeEvent;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
import org.jdesktop.lg3d.wg.event.LgEventConnector;

/**
 * Desktop configuration panel: taskbar thickness, docking position, icon size,
 * the Swing application UI font, and the taskbar auto-hide toggle. Edits are
 * written to {@link DesktopConfig} (persisted via {@code java.util.prefs}) and
 * applied live by posting a {@link DesktopConfigChangeEvent} - the same bridge
 * pattern {@link AppearancePanel} uses for the wallpaper, so the running taskbar
 * re-lays-out immediately and the change survives a restart.
 *
 * <p>Every control is a {@link JList} or a {@link JButton} on purpose. This
 * panel is painted offscreen into a texture by
 * {@link org.jdesktop.lg3d.wg.SwingNode}: the "native" Swing look-and-feel on
 * Linux is GTK, which is Synth-based, and Synth button UIs (radio buttons,
 * check boxes, toggle buttons) throw a {@code NullPointerException} from
 * {@code SynthContext.getStyle()} when painted into that offscreen buffer - and
 * because {@code SwingNode.captureNow} paints the whole window in one pass, one
 * such widget aborts the entire repaint. Combo boxes are also unusable (their
 * drop-down is a separate popup window the offscreen frame cannot capture).
 * Lists and plain buttons are the selectors proven to render in the other hosted
 * panels (Appearance wallpaper list, Control Center navigation), so settings are
 * offered as discrete preset lists.
 */
public class DesktopPanel implements ControlPanel {

    private static final String[] SCALE_LABELS = {
        "Small", "Normal", "Large", "Extra large"
    };
    private static final float[] SCALE_VALUES = { 0.8f, 1.0f, 1.3f, 1.6f };

    private static final String[] POSITION_LABELS = { "Bottom", "Top" };

    private static final String[] SIZE_LABELS = { "10", "12", "14", "16", "18", "24" };
    private static final int[] SIZE_VALUES = { 10, 12, 14, 16, 18, 24 };

    private static final String[] AUTO_HIDE_LABELS = { "Off", "On" };

    private static final String[] FALLBACK_FONTS = {
        "SansSerif", "Serif", "Monospaced", "Dialog"
    };

    private final JPanel root = new JPanel(new BorderLayout(8, 8));

    private final JList<String> barList = new JList<>(SCALE_LABELS);
    private final JList<String> iconList = new JList<>(SCALE_LABELS);
    private final JList<String> positionList = new JList<>(POSITION_LABELS);
    private final JList<String> fontSizeList = new JList<>(SIZE_LABELS);
    private final JList<String> autoHideList = new JList<>(AUTO_HIDE_LABELS);
    private final JList<String> fontList = new JList<>(fontFamilies());
    private final JLabel statusLabel = new JLabel(" ");

    public DesktopPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(listBlock("Taskbar thickness", barList, 4));
        left.add(listBlock("Icon size", iconList, 4));
        left.add(listBlock("Taskbar auto-hide", autoHideList, 2));
        left.add(Box.createVerticalGlue());

        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.add(listBlock("Taskbar position", positionList, 2));
        right.add(hint("Left / right docking is planned for a future release."));
        right.add(listBlock("Application font size", fontSizeList, 6));
        right.add(Box.createVerticalGlue());

        JPanel columns = new JPanel(new GridLayout(1, 2, 10, 0));
        columns.add(left);
        columns.add(right);

        JPanel outer = new JPanel(new BorderLayout(10, 10));
        outer.add(columns, BorderLayout.NORTH);
        outer.add(listBlock("Application font family", fontList, 6), BorderLayout.CENTER);

        JButton apply = new JButton("Apply");
        apply.addActionListener(e -> apply());
        JButton reset = new JButton("Reset to Defaults");
        reset.addActionListener(e -> reset());

        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        buttons.add(apply);
        buttons.add(reset);

        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.CENTER);
        south.add(statusLabel, BorderLayout.SOUTH);

        root.add(new JScrollPane(outer), BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        syncFromConfig();
    }

    @Override
    public String displayName() {
        return "Desktop";
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
        syncFromConfig();
    }

    // ------------------------------------------------------------------

    private static String[] fontFamilies() {
        try {
            String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames();
            if (families != null && families.length > 0) {
                return families;
            }
        } catch (Throwable t) {
            // headless / no font manager - fall back to the logical fonts
        }
        return FALLBACK_FONTS;
    }

    private void syncFromConfig() {
        DesktopConfig cfg = DesktopConfig.get();
        selectNearest(barList, SCALE_VALUES, cfg.getBarScale());
        selectNearest(iconList, SCALE_VALUES, cfg.getIconScale());
        positionList.setSelectedIndex(
                cfg.getPosition() == DesktopConfig.Position.TOP ? 1 : 0);
        selectNearest(fontSizeList, SIZE_VALUES, cfg.getFontSize());
        autoHideList.setSelectedIndex(cfg.isAutoHide() ? 1 : 0);
        fontList.setSelectedValue(cfg.getFontName(), true);
        if (fontList.getSelectedValue() == null && fontList.getModel().getSize() > 0) {
            fontList.setSelectedIndex(0);
        }
        statusLabel.setText(" ");
    }

    private void apply() {
        DesktopConfig cfg = DesktopConfig.get();
        cfg.setBarScale(SCALE_VALUES[index(barList, SCALE_VALUES.length)]);
        cfg.setIconScale(SCALE_VALUES[index(iconList, SCALE_VALUES.length)]);
        cfg.setPosition(index(positionList, 2) == 1
                ? DesktopConfig.Position.TOP : DesktopConfig.Position.BOTTOM);
        cfg.setFontSize(SIZE_VALUES[index(fontSizeList, SIZE_VALUES.length)]);
        cfg.setAutoHide(index(autoHideList, 2) == 1);
        String family = fontList.getSelectedValue();
        if (family != null) {
            cfg.setFontName(family);
        }
        cfg.save();

        if (Boolean.getBoolean(Desktop2D.MODE_PROPERTY)) {
            // Conventional 2D desktop: there is no scene manager or
            // LgEventConnector here. Desktop2D re-applies the Swing font
            // defaults and re-lays-out its own taskbar live (thickness, docking
            // edge, icon scale, auto-hide), then we refresh this panel so the
            // new font shows immediately.
            Desktop2D.applyDesktopConfig();
            SwingUtilities.updateComponentTreeUI(root);
        } else {
            // Live-apply the Swing font to this window (the 3D taskbar picks up
            // its geometry from the posted event below).
            TitledSwingWindow.applySwingFontDefaults();
            Window w = SwingUtilities.getWindowAncestor(root);
            if (w != null) {
                SwingUtilities.updateComponentTreeUI(w);
            }

            // Notify the scene-manager taskbar to re-lay-out live.
            LgEventConnector.getLgEventConnector().postEvent(
                    new DesktopConfigChangeEvent(), null);
        }

        statusLabel.setText("Settings applied and saved.");
    }

    private void reset() {
        DesktopConfig.get().resetToDefaults();
        syncFromConfig();
        apply();
        statusLabel.setText("Restored default desktop settings.");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int index(JList<String> list, int count) {
        int i = list.getSelectedIndex();
        return (i < 0 || i >= count) ? 0 : i;
    }

    private static void selectNearest(JList<String> list, float[] values, float target) {
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            float d = Math.abs(values[i] - target);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        list.setSelectedIndex(best);
    }

    private static void selectNearest(JList<String> list, int[] values, int target) {
        int best = 0;
        int bd = Integer.MAX_VALUE;
        for (int i = 0; i < values.length; i++) {
            int d = Math.abs(values[i] - target);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        list.setSelectedIndex(best);
    }

    private JPanel listBlock(String label, JList<String> list, int rows) {
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(rows);
        JScrollPane sp = new JScrollPane(list);
        sp.setBorder(BorderFactory.createLineBorder(new Color(190, 196, 206)));
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JLabel l = new JLabel(label);
        p.add(l, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private JPanel hint(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(java.awt.Font.ITALIC, l.getFont().getSize2D() - 1f));
        l.setForeground(Color.GRAY);
        l.setBorder(BorderFactory.createEmptyBorder(0, 6, 2, 4));
        JPanel p = new JPanel(new BorderLayout());
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        p.add(l, BorderLayout.WEST);
        return p;
    }
}
