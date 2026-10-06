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
import java.util.EnumMap;
import java.util.Map;
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
import javax.swing.DefaultListModel;
import org.jdesktop.lg3d.apps.TitledSwingWindow;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D;
import org.jdesktop.lg3d.scenemanager.utils.event.DesktopConfigChangeEvent;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;
import org.jdesktop.lg3d.wg.event.LgEventConnector;

/**
 * Desktop configuration panel: taskbar thickness, docking position, icon size,
 * the Swing application UI font, the taskbar auto-hide toggle, the taskbar's
 * visible buttons and their label style, and the calendar's holiday region. Edits are
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

    /** Display names, in {@link DesktopConfig.TaskbarItem} ordinal order. */
    private static final String[] TASKBAR_ITEM_LABELS = {
        "Start button", "Quick-launch strip", "Documents", "Downloads",
        "Workspace pager", "System indicators", "Notifications", "Clock",
        "Exit button"
    };
    private static final String[] TASKBAR_LABEL_LABELS = {
        "Icons only", "Icons and text"
    };

    /** Display names, in {@link DesktopConfig.Indicator} ordinal order. */
    private static final String[] INDICATOR_LABELS = { "Volume", "Network" };
    private static final String[] SYSTEM_TRAY_LABELS = { "Off", "On" };

    // Holiday region for the 2D desktop's calendar popup. "AUTO" resolves from
    // the system locale; the rest are explicit jbusinessday region tokens
    // persisted in DesktopConfig (see HolidayCalendar's token grammar).
    private static final String[] HOLIDAY_REGION_LABELS = {
        "Automatic (system locale)", "United States (federal)", "Canada (federal)",
        "Canada \u2013 Quebec", "Canada \u2013 Ontario",
        "Canada \u2013 British Columbia", "Canada \u2013 Alberta"
    };
    private static final String[] HOLIDAY_REGION_VALUES = {
        "AUTO", "US", "CA", "CA:QUEBEC", "CA:ONTARIO",
        "CA:BRITISH_COLUMBIA", "CA:ALBERTA"
    };

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
    private final JList<String> holidayRegionList = new JList<>(HOLIDAY_REGION_LABELS);
    private final DefaultListModel<String> itemsModel = new DefaultListModel<>();
    private final JList<String> itemsList = new JList<>(itemsModel);
    private final JList<String> labelsList = new JList<>(TASKBAR_LABEL_LABELS);
    /** Pending per-item visibility, edited by the toggle and written on Apply. */
    private final Map<DesktopConfig.TaskbarItem, Boolean> pendingShown =
            new EnumMap<>(DesktopConfig.TaskbarItem.class);
    private final JList<String> systemTrayList = new JList<>(SYSTEM_TRAY_LABELS);
    private final DefaultListModel<String> indicatorsModel = new DefaultListModel<>();
    private final JList<String> indicatorsList = new JList<>(indicatorsModel);
    /** Pending per-indicator tray visibility, edited by the toggle, written on Apply. */
    private final Map<DesktopConfig.Indicator, Boolean> pendingIndicatorShown =
            new EnumMap<>(DesktopConfig.Indicator.class);
    private final JLabel statusLabel = new JLabel(" ");

    public DesktopPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        // The taskbar-contents editor goes first, top-left, so "which buttons
        // show and icon-only vs icon+text" is the first thing in the panel
        // instead of a second row that fell under the fold of the 720x500
        // window (the reason the taskbar settings looked absent).
        left.add(taskbarContentsBlock());
        left.add(systemTrayBlock());
        left.add(listBlock("Taskbar thickness", barList, 3));
        left.add(listBlock("Icon size", iconList, 3));
        left.add(listBlock("Taskbar auto-hide", autoHideList, 2));
        left.add(Box.createVerticalGlue());

        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.add(listBlock("Taskbar position", positionList, 2));
        right.add(hint("Left / right docking is planned for a future release."));
        right.add(listBlock("Application font family", fontList, 5));
        right.add(listBlock("Application font size", fontSizeList, 4));
        right.add(listBlock("Calendar holiday region", holidayRegionList, 4));
        right.add(Box.createVerticalGlue());

        JPanel columns = new JPanel(new GridLayout(1, 2, 10, 0));
        columns.add(left);
        columns.add(right);

        JPanel outer = new JPanel(new BorderLayout(10, 10));
        outer.add(columns, BorderLayout.CENTER);

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
        holidayRegionList.setSelectedIndex(regionIndex(cfg.getHolidayRegion()));
        for (DesktopConfig.TaskbarItem item : DesktopConfig.TaskbarItem.values()) {
            pendingShown.put(item, cfg.isTaskbarItemShown(item));
        }
        refreshItemsList();
        labelsList.setSelectedIndex(
                cfg.getTaskbarLabels() == DesktopConfig.Labels.ICONS_AND_TEXT ? 1 : 0);
        systemTrayList.setSelectedIndex(cfg.isIndicatorsSystemTray() ? 1 : 0);
        for (DesktopConfig.Indicator ind : DesktopConfig.Indicator.values()) {
            pendingIndicatorShown.put(ind, cfg.isIndicatorShown(ind));
        }
        refreshIndicatorsList();
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
        for (DesktopConfig.TaskbarItem item : DesktopConfig.TaskbarItem.values()) {
            cfg.setTaskbarItemShown(item, Boolean.TRUE.equals(pendingShown.get(item)));
        }
        cfg.setTaskbarLabels(index(labelsList, 2) == 1
                ? DesktopConfig.Labels.ICONS_AND_TEXT
                : DesktopConfig.Labels.ICONS_ONLY);
        cfg.setIndicatorsSystemTray(index(systemTrayList, 2) == 1);
        for (DesktopConfig.Indicator ind : DesktopConfig.Indicator.values()) {
            cfg.setIndicatorShown(ind, Boolean.TRUE.equals(pendingIndicatorShown.get(ind)));
        }
        cfg.setHolidayRegion(HOLIDAY_REGION_VALUES[index(holidayRegionList, HOLIDAY_REGION_VALUES.length)]);
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
    // Taskbar contents (which buttons show, and icon-only vs icon+text)
    // ------------------------------------------------------------------

    /**
     * The taskbar-contents editor: the list of fixed taskbar buttons with their
     * pending shown/hidden state, a toggle for the selected row, and the label
     * style selector (icon-only vs icon+text).
     */
    private JPanel taskbarContentsBlock() {
        itemsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        itemsList.setVisibleRowCount(6);
        JScrollPane sp = new JScrollPane(itemsList);
        sp.setBorder(BorderFactory.createLineBorder(new Color(190, 196, 206)));

        JButton toggle = new JButton("Toggle shown / hidden");
        toggle.addActionListener(e -> toggleSelectedItem());

        JPanel south = new JPanel(new BorderLayout(4, 4));
        south.add(listBlock("Taskbar button labels", labelsList, 2), BorderLayout.NORTH);
        south.add(toggle, BorderLayout.SOUTH);

        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createTitledBorder("Taskbar buttons"));
        JLabel title = new JLabel("Select a button, then toggle it shown / hidden.");
        title.setFont(title.getFont().deriveFont(
                java.awt.Font.ITALIC, title.getFont().getSize2D() - 1f));
        p.add(title, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    /** Flips the pending shown/hidden state of the selected taskbar button. */
    private void toggleSelectedItem() {
        int i = itemsList.getSelectedIndex();
        DesktopConfig.TaskbarItem[] items = DesktopConfig.TaskbarItem.values();
        if (i < 0 || i >= items.length) {
            statusLabel.setText("Select a taskbar button first.");
            return;
        }
        DesktopConfig.TaskbarItem item = items[i];
        pendingShown.put(item, !Boolean.TRUE.equals(pendingShown.get(item)));
        refreshItemsList();
        itemsList.setSelectedIndex(i);
    }

    /** Rebuilds the taskbar-button rows from the pending state, keeping selection. */
    private void refreshItemsList() {
        int keep = itemsList.getSelectedIndex();
        itemsModel.clear();
        DesktopConfig.TaskbarItem[] items = DesktopConfig.TaskbarItem.values();
        for (int i = 0; i < items.length && i < TASKBAR_ITEM_LABELS.length; i++) {
            boolean shown = Boolean.TRUE.equals(pendingShown.get(items[i]));
            itemsModel.addElement(TASKBAR_ITEM_LABELS[i]
                    + (shown ? "  \u2014  shown" : "  \u2014  hidden"));
        }
        if (keep >= 0 && keep < itemsModel.size()) {
            itemsList.setSelectedIndex(keep);
        } else if (itemsModel.size() > 0) {
            itemsList.setSelectedIndex(0);
        }
    }

    // ------------------------------------------------------------------
    // System tray indicators (host java.awt.SystemTray mirror)
    // ------------------------------------------------------------------

    /**
     * The system-tray editor: a mirror on/off selector, the list of indicators
     * (volume, network) with their pending shown/hidden state, and a toggle for
     * the selected row. The mirror puts live volume/network glyphs into the host
     * {@code java.awt.SystemTray}; the setting persists regardless, but the
     * mirror is silently skipped on a host with no system tray (e.g. GNOME on
     * Wayland, which dropped the XEmbed tray protocol).
     */
    private JPanel systemTrayBlock() {
        indicatorsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        indicatorsList.setVisibleRowCount(2);
        JScrollPane sp = new JScrollPane(indicatorsList);
        sp.setBorder(BorderFactory.createLineBorder(new Color(190, 196, 206)));

        JButton toggle = new JButton("Toggle shown / hidden");
        toggle.addActionListener(e -> toggleSelectedIndicator());

        JPanel south = new JPanel(new BorderLayout(4, 4));
        south.add(listBlock("Mirror into system tray", systemTrayList, 2), BorderLayout.NORTH);
        south.add(toggle, BorderLayout.SOUTH);

        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createTitledBorder("System tray indicators"));
        JLabel title = new JLabel("Select an indicator, then toggle it shown / hidden.");
        title.setFont(title.getFont().deriveFont(
                java.awt.Font.ITALIC, title.getFont().getSize2D() - 1f));
        p.add(title, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    /** Flips the pending shown/hidden state of the selected tray indicator. */
    private void toggleSelectedIndicator() {
        int i = indicatorsList.getSelectedIndex();
        DesktopConfig.Indicator[] inds = DesktopConfig.Indicator.values();
        if (i < 0 || i >= inds.length) {
            statusLabel.setText("Select a system tray indicator first.");
            return;
        }
        DesktopConfig.Indicator ind = inds[i];
        pendingIndicatorShown.put(ind, !Boolean.TRUE.equals(pendingIndicatorShown.get(ind)));
        refreshIndicatorsList();
        indicatorsList.setSelectedIndex(i);
    }

    /** Rebuilds the tray-indicator rows from the pending state, keeping selection. */
    private void refreshIndicatorsList() {
        int keep = indicatorsList.getSelectedIndex();
        indicatorsModel.clear();
        DesktopConfig.Indicator[] inds = DesktopConfig.Indicator.values();
        for (int i = 0; i < inds.length && i < INDICATOR_LABELS.length; i++) {
            boolean shown = Boolean.TRUE.equals(pendingIndicatorShown.get(inds[i]));
            indicatorsModel.addElement(INDICATOR_LABELS[i]
                    + (shown ? "  \u2014  shown" : "  \u2014  hidden"));
        }
        if (keep >= 0 && keep < indicatorsModel.size()) {
            indicatorsList.setSelectedIndex(keep);
        } else if (indicatorsModel.size() > 0) {
            indicatorsList.setSelectedIndex(0);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static int index(JList<String> list, int count) {
        int i = list.getSelectedIndex();
        return (i < 0 || i >= count) ? 0 : i;
    }

    /** The list row for a stored region token; unknown tokens fall back to row 0 (Automatic). */
    private static int regionIndex(String token) {
        if (token != null) {
            for (int i = 0; i < HOLIDAY_REGION_VALUES.length; i++) {
                if (HOLIDAY_REGION_VALUES[i].equalsIgnoreCase(token.trim())) {
                    return i;
                }
            }
        }
        return 0;
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
