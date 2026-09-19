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
package org.jdesktop.lg3d.apps.controlcenter;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import org.jdesktop.lg3d.apps.TitledSwingWindow;
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
 * <p>Every control here is deliberately <em>click-based</em> (radio buttons, a
 * check box, a {@link JList} and buttons) rather than a {@code JSlider} or
 * {@code JComboBox}. This panel is rendered offscreen into a texture by
 * {@link org.jdesktop.lg3d.wg.SwingNode}: a combo's drop-down is a separate
 * popup window that the offscreen buffer cannot capture or route clicks into,
 * and a slider needs continuous drag tracking. Clicks and list selections are
 * the interactions proven to work in the other hosted panels (e.g. the
 * Appearance wallpaper list), so the settings are offered as discrete presets.
 */
public class DesktopPanel implements ControlPanel {

    private static final String[] SCALE_LABELS = {
        "Small", "Normal", "Large", "Extra large"
    };
    private static final Float[] SCALE_VALUES = { 0.8f, 1.0f, 1.3f, 1.6f };

    private static final String[] SIZE_LABELS = { "10", "12", "14", "16", "18", "24" };
    private static final Integer[] SIZE_VALUES = { 10, 12, 14, 16, 18, 24 };

    private static final String[] FALLBACK_FONTS = {
        "SansSerif", "Serif", "Monospaced", "Dialog"
    };

    private final JPanel root = new JPanel(new BorderLayout(8, 8));

    private final RadioGroup<Float> barGroup =
            new RadioGroup<>(SCALE_LABELS, SCALE_VALUES);
    private final RadioGroup<Float> iconGroup =
            new RadioGroup<>(SCALE_LABELS, SCALE_VALUES);
    private final RadioGroup<DesktopConfig.Position> positionGroup =
            new RadioGroup<>(new String[] { "Bottom", "Top" },
                    new DesktopConfig.Position[] {
                        DesktopConfig.Position.BOTTOM, DesktopConfig.Position.TOP });
    private final RadioGroup<Integer> fontSizeGroup =
            new RadioGroup<>(SIZE_LABELS, SIZE_VALUES);

    private final JList<String> fontList = new JList<>(fontFamilies());
    private final JCheckBox autoHideCheck = new JCheckBox("Auto-hide the taskbar");
    private final JLabel statusLabel = new JLabel(" ");

    public DesktopPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        fontList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fontList.setVisibleRowCount(6);
        JScrollPane fontScroll = new JScrollPane(fontList);
        fontScroll.setPreferredSize(new Dimension(220, 110));

        JPanel form = new JPanel();
        form.setLayout(new javax.swing.BoxLayout(form, javax.swing.BoxLayout.Y_AXIS));

        form.add(section("Taskbar"));
        form.add(row("Thickness", barGroup.panel));
        form.add(row("Position", positionGroup.panel));
        form.add(hint("Left / right docking is planned for a future release."));
        form.add(row("Icon size", iconGroup.panel));
        form.add(row("Auto-hide", autoHideCheck));

        form.add(section("Application font"));
        form.add(row("Family", fontScroll));
        form.add(row("Size", fontSizeGroup.panel));

        JButton apply = new JButton("Apply");
        apply.addActionListener(e -> apply());
        JButton reset = new JButton("Reset to Defaults");
        reset.addActionListener(e -> reset());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(apply);
        buttons.add(reset);

        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.CENTER);
        south.add(statusLabel, BorderLayout.SOUTH);

        root.add(new JScrollPane(form), BorderLayout.CENTER);
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
        barGroup.select(cfg.getBarScale());
        iconGroup.select(cfg.getIconScale());
        positionGroup.select(cfg.getPosition());
        fontSizeGroup.select(cfg.getFontSize());
        autoHideCheck.setSelected(cfg.isAutoHide());
        fontList.setSelectedValue(cfg.getFontName(), true);
        if (fontList.getSelectedValue() == null && fontList.getModel().getSize() > 0) {
            fontList.setSelectedIndex(0);
        }
        statusLabel.setText(" ");
    }

    private void apply() {
        DesktopConfig cfg = DesktopConfig.get();
        cfg.setBarScale(barGroup.get());
        cfg.setIconScale(iconGroup.get());
        cfg.setPosition(positionGroup.get());
        cfg.setFontSize(fontSizeGroup.get());
        cfg.setAutoHide(autoHideCheck.isSelected());
        String family = fontList.getSelectedValue();
        if (family != null) {
            cfg.setFontName(family);
        }
        cfg.save();

        // Live-apply the Swing font to this window (the taskbar picks up its
        // geometry from the posted event below).
        TitledSwingWindow.applySwingFontDefaults();
        Window w = SwingUtilities.getWindowAncestor(root);
        if (w != null) {
            SwingUtilities.updateComponentTreeUI(w);
        }

        // Notify the scene-manager taskbar to re-lay-out live.
        LgEventConnector.getLgEventConnector().postEvent(
                new DesktopConfigChangeEvent(), null);

        statusLabel.setText("Settings applied and saved.");
    }

    private void reset() {
        DesktopConfig.get().resetToDefaults();
        syncFromConfig();
        apply();
        statusLabel.setText("Restored default desktop settings.");
    }

    // ------------------------------------------------------------------
    // Layout helpers
    // ------------------------------------------------------------------

    private JPanel section(String title) {
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() + 1f));
        label.setBorder(BorderFactory.createEmptyBorder(8, 4, 2, 4));
        JPanel p = new JPanel(new BorderLayout());
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        p.add(label, BorderLayout.WEST);
        return p;
    }

    private JPanel row(String label, JComponent control) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        JLabel l = new JLabel(label);
        l.setPreferredSize(new Dimension(110, 24));
        l.setVerticalAlignment(JLabel.TOP);
        p.add(l, BorderLayout.WEST);
        p.add(control, BorderLayout.CENTER);
        return p;
    }

    private JPanel hint(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.ITALIC, l.getFont().getSize2D() - 1f));
        l.setForeground(java.awt.Color.GRAY);
        l.setBorder(BorderFactory.createEmptyBorder(0, 118, 4, 4));
        JPanel p = new JPanel(new BorderLayout());
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        p.add(l, BorderLayout.WEST);
        return p;
    }

    /**
     * A small group of mutually-exclusive radio buttons bound to typed values.
     * Radio buttons toggle on a single click, which the {@code SwingNode}
     * input forwarding handles reliably (unlike a combo popup or slider drag).
     */
    private static final class RadioGroup<T> {
        private final ButtonGroup group = new ButtonGroup();
        private final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        private final List<JRadioButton> buttons = new ArrayList<>();
        private final List<T> values = new ArrayList<>();

        RadioGroup(String[] labels, T[] vals) {
            for (int i = 0; i < labels.length; i++) {
                JRadioButton b = new JRadioButton(labels[i]);
                group.add(b);
                panel.add(b);
                buttons.add(b);
                values.add(vals[i]);
            }
            if (!buttons.isEmpty()) {
                buttons.get(0).setSelected(true);
            }
        }

        /** Selects the button for {@code value}; falls back to the nearest
         *  numeric preset (or the first) when there is no exact match. */
        void select(T value) {
            int best = -1;
            for (int i = 0; i < values.size(); i++) {
                if (values.get(i).equals(value)) {
                    best = i;
                    break;
                }
            }
            if (best < 0 && value instanceof Number && !values.isEmpty()
                    && values.get(0) instanceof Number) {
                double target = ((Number) value).doubleValue();
                double bd = Double.MAX_VALUE;
                for (int i = 0; i < values.size(); i++) {
                    double d = Math.abs(((Number) values.get(i)).doubleValue() - target);
                    if (d < bd) {
                        bd = d;
                        best = i;
                    }
                }
            }
            if (best < 0) {
                best = 0;
            }
            if (best >= 0 && best < buttons.size()) {
                buttons.get(best).setSelected(true);
            }
        }

        T get() {
            for (int i = 0; i < buttons.size(); i++) {
                if (buttons.get(i).isSelected()) {
                    return values.get(i);
                }
            }
            return values.get(0);
        }
    }
}
