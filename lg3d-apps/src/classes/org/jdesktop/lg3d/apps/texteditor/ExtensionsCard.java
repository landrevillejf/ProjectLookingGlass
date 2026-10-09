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
package org.jdesktop.lg3d.apps.texteditor;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

/**
 * The Extensions card. The left half lists every installed
 * {@link TextEditorExtension} with its enable / disable state and offers
 * Enable / Disable buttons (a third-party extension only contributes actions
 * once the user enables it here); the right half lists every action those
 * extensions contribute, grouped under bold, non-selectable category headers.
 * Each action row is labelled {@code <extension>: <action> (accelerator)} and
 * is bound to its index in the host's flat action list, so selecting an entry
 * and pressing Run (or double-clicking) invokes exactly that action against the
 * current tab. Headers are inert: Run stays disabled while one is selected.
 * The card uses plain {@link JList}s, so it works inside the 3D desktop's
 * offscreen capture like every other in-panel surface.
 */
public final class ExtensionsCard extends JPanel {

    /** The panel-side callbacks for the extensions card. */
    public interface Host {

        /** Runs the action with the given index into the host's action list. */
        void runExtensionAction(int index);

        /** Returns to the editor card. */
        void closeCard();

        /** @return one row per installed extension, for the management list. */
        default List<ExtensionInfo> extensionInfos() {
            return List.of();
        }

        /** Enables or disables an extension; the host refreshes the card. */
        default void setExtensionEnabled(String id, boolean enabled) {
            // no-op default keeps third-party hosts source-compatible
        }

        /**
         * @param actionIndex the host's flat action-list index
         * @return the currently-effective accelerator spec for that action
         *         (a user rebound, else the declared default), or "" when unbound
         */
        default String getAccelerator(int actionIndex) {
            return "";
        }

        /**
         * Rebinds the action at {@code actionIndex} to {@code spec} (a KeyStroke
         * string such as {@code "control alt X"}); a blank {@code spec} unbinds
         * it. The host persists the override and refreshes the card.
         */
        default void setAccelerator(int actionIndex, String spec) {
            // no-op default keeps third-party hosts source-compatible
        }

        /**
         * @param id an extension id
         * @return the permissions that extension declares, each with whether the
         *         user has granted it (empty when the id is unknown)
         */
        default List<PermissionInfo> permissionsFor(String id) {
            return List.of();
        }

        /**
         * Grants or revokes one permission on an extension; the host persists the
         * change and refreshes the card. {@code name} is a
         * {@link org.jdesktop.lg3d.apps.texteditor.ext.TextEditorPermission} name.
         */
        default void setPermission(String id, String name, boolean granted) {
            // no-op default keeps third-party hosts source-compatible
        }
    }

    /**
     * One permission an extension declares, with the user's grant state, shown
     * as a checkbox in the management column.
     */
    public record PermissionInfo(String name, boolean granted) { }

    /**
     * One installed extension shown in the management list. {@code enabled}
     * drives the bold / greyed rendering and the Enable vs Disable button.
     */
    public record ExtensionInfo(String id, String name, String version,
                                String category, boolean enabled) {

        @Override
        public String toString() {
            String v = (version == null || version.isBlank()) ? "" : " v" + version;
            String cat = (category == null || category.isBlank()) ? "" : " \u2014 " + category;
            return name + v + cat + "  [" + (enabled ? "Enabled" : "Disabled") + "]";
        }
    }

    /**
     * One row in the actions list: either a non-selectable category
     * {@code header} or a runnable action bound to {@code actionIndex} in the
     * host's flat action list (headers carry {@code -1}).
     */
    public record Row(String text, int actionIndex, boolean header) {

        /** A bold, inert category heading row. */
        public static Row header(String category) {
            return new Row(category, -1, true);
        }

        /** A runnable action row wired to the given host action index. */
        public static Row action(String text, int actionIndex) {
            return new Row(text, actionIndex, false);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private final Host host;
    private final DefaultListModel<Row> model = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(model);
    private final JButton runButton = new JButton("Run");

    private final DefaultListModel<ExtensionInfo> extModel = new DefaultListModel<>();
    private final JList<ExtensionInfo> extList = new JList<>(extModel);
    private final JButton enableButton = new JButton("Enable");
    private final JButton disableButton = new JButton("Disable");

    private final JLabel shortcutLabel = new JLabel("Shortcut: \u2014");
    private final JButton rebindButton = new JButton("Rebind\u2026");
    private final JButton clearShortcutButton = new JButton("Clear");
    private final JPanel permissionPanel = new JPanel(new GridLayout(0, 1, 2, 2));
    /** True while the actions list is grabbing the next key for a rebound. */
    private boolean capturing;

    public ExtensionsCard(Host host) {
        this.host = host;
        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(16);
        list.setCellRenderer(new RowRenderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                runButton.setEnabled(isAction(list.getSelectedIndex()));
                updateShortcutLabel();
            }
        });
        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!capturing) {
                    return;
                }
                e.consume();
                String spec = keySpec(e.getKeyCode(), e.getModifiersEx());
                stopCapturing();
                if (!spec.isEmpty()) {
                    rebindSelectedAccelerator(spec);
                }
            }
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int index = list.getSelectedIndex();
                if (e.getClickCount() == 2 && isAction(index)) {
                    host.runExtensionAction(model.get(index).actionIndex());
                }
            }
        });

        runButton.addActionListener(e -> {
            int index = list.getSelectedIndex();
            if (isAction(index)) {
                host.runExtensionAction(model.get(index).actionIndex());
            }
        });
        rebindButton.addActionListener(e -> startCapturing());
        clearShortcutButton.addActionListener(e -> clearSelectedAccelerator());
        JButton back = new JButton("Back to Editor");
        back.addActionListener(e -> host.closeCard());

        extList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        extList.setVisibleRowCount(16);
        extList.setCellRenderer(new ExtensionRenderer());
        extList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                refreshToggleButtons();
                refreshPermissions();
            }
        });
        enableButton.addActionListener(e -> applyEnabled(true));
        disableButton.addActionListener(e -> applyEnabled(false));

        JPanel extButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        extButtons.add(enableButton);
        extButtons.add(disableButton);
        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.add(extButtons, BorderLayout.NORTH);
        JPanel permBox = new JPanel(new BorderLayout(0, 2));
        permBox.add(new JLabel("Permissions (grant to enable a capability)"),
                BorderLayout.NORTH);
        permBox.add(new JScrollPane(permissionPanel), BorderLayout.CENTER);
        south.add(permBox, BorderLayout.CENTER);
        JPanel extensions = new JPanel(new BorderLayout(4, 4));
        extensions.add(new JLabel("Installed Extensions"), BorderLayout.NORTH);
        extensions.add(new JScrollPane(extList), BorderLayout.CENTER);
        extensions.add(south, BorderLayout.SOUTH);

        JPanel actions = new JPanel(new BorderLayout(4, 4));
        actions.add(new JLabel("Extension Actions, grouped by category "
                + "(double-click to run)"), BorderLayout.NORTH);
        actions.add(new JScrollPane(list), BorderLayout.CENTER);

        JPanel shortcutBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        shortcutBar.add(shortcutLabel);
        shortcutBar.add(rebindButton);
        shortcutBar.add(clearShortcutButton);
        actions.add(shortcutBar, BorderLayout.SOUTH);

        JPanel columns = new JPanel(new GridLayout(1, 2, 8, 0));
        columns.add(extensions);
        columns.add(actions);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        buttons.add(runButton);
        buttons.add(back);

        add(columns, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        runButton.setEnabled(false);
        enableButton.setEnabled(false);
        disableButton.setEnabled(false);
        rebindButton.setEnabled(false);
        clearShortcutButton.setEnabled(false);
    }

    /** @return true when the row at {@code modelIndex} is a runnable action. */
    private boolean isAction(int modelIndex) {
        return modelIndex >= 0 && modelIndex < model.size()
                && !model.get(modelIndex).header();
    }

    /**
     * Replaces the shown entries with a flat, sequentially-indexed action list
     * (no category headers); index order matches {@code labels}. The management
     * list is left untouched.
     */
    public void load(List<String> labels) {
        List<Row> rows = new ArrayList<>();
        if (labels != null) {
            int i = 0;
            for (String label : labels) {
                rows.add(Row.action(label, i++));
            }
        }
        loadRows(rows);
    }

    /** Replaces the shown action entries with grouped rows (headers + actions). */
    public void loadRows(List<Row> rows) {
        model.clear();
        if (rows != null) {
            for (Row row : rows) {
                model.addElement(row);
            }
        }
        stopCapturing();
        runButton.setEnabled(false);
        rebindButton.setEnabled(false);
        clearShortcutButton.setEnabled(false);
        updateShortcutLabel();
    }

    /** Replaces both views: the grouped action rows and the extension list. */
    public void show(List<Row> rows, List<ExtensionInfo> infos) {
        // Preserve the current management selection across the rebuild so an
        // enable/disable or permission toggle does not drop the user's place.
        String keepId = (extList.getSelectedValue() != null)
                ? extList.getSelectedValue().id() : null;
        loadRows(rows);
        extModel.clear();
        if (infos != null) {
            for (ExtensionInfo info : infos) {
                extModel.addElement(info);
            }
        }
        int index = (keepId == null) ? -1 : indexOfExtension(infos, keepId);
        if (index >= 0) {
            extList.setSelectedIndex(index);
        } else {
            extList.clearSelection();
            enableButton.setEnabled(false);
            disableButton.setEnabled(false);
        }
        // Re-read the checkboxes even when the selection index is unchanged,
        // so a permission/enable toggle is reflected immediately.
        refreshPermissions();
    }

    private static int indexOfExtension(List<ExtensionInfo> infos, String id) {
        if (infos != null) {
            for (int i = 0; i < infos.size(); i++) {
                if (infos.get(i).id().equals(id)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** The number of runnable (non-header) action entries shown (test seam). */
    public int entryCount() {
        int n = 0;
        for (int i = 0; i < model.size(); i++) {
            if (!model.get(i).header()) {
                n++;
            }
        }
        return n;
    }

    /** The number of installed extensions shown in the management list. */
    public int extensionCount() {
        return extModel.size();
    }

    /** Selects an action row programmatically (test seam); headers clear Run. */
    public void select(int index) {
        if (index >= 0 && index < model.size()) {
            list.setSelectedIndex(index);
            runButton.setEnabled(!model.get(index).header());
        } else {
            list.clearSelection();
            runButton.setEnabled(false);
        }
    }

    /** Selects an extension row programmatically (test seam). */
    public void selectExtension(int index) {
        if (index >= 0 && index < extModel.size()) {
            extList.setSelectedIndex(index);
        } else {
            extList.clearSelection();
        }
        refreshToggleButtons();
        refreshPermissions();
    }

    /** Enables the selected extension (test seam mirroring the Enable button). */
    final void enableSelectedExtension() {
        applyEnabled(true);
    }

    /** Disables the selected extension (test seam mirroring the Disable button). */
    final void disableSelectedExtension() {
        applyEnabled(false);
    }

    private void applyEnabled(boolean enabled) {
        ExtensionInfo selected = extList.getSelectedValue();
        if (selected != null) {
            host.setExtensionEnabled(selected.id(), enabled);
        }
    }

    /** Rebuilds the permission checkboxes for the selected extension. */
    private void refreshPermissions() {
        permissionPanel.removeAll();
        ExtensionInfo sel = extList.getSelectedValue();
        if (sel == null) {
            permissionPanel.add(new JLabel("Select an extension"));
        } else {
            List<PermissionInfo> perms = host.permissionsFor(sel.id());
            if (perms.isEmpty()) {
                permissionPanel.add(new JLabel("No permissions declared"));
            } else {
                for (PermissionInfo p : perms) {
                    JCheckBox box = new JCheckBox(p.name(), p.granted());
                    final String id = sel.id();
                    final String name = p.name();
                    box.addActionListener(e -> host.setPermission(id, name, box.isSelected()));
                    permissionPanel.add(box);
                }
            }
        }
        permissionPanel.revalidate();
        permissionPanel.repaint();
    }

    /** Grants or revokes a permission on the selected extension (test seam). */
    final void setPermissionOnSelected(String name, boolean granted) {
        ExtensionInfo sel = extList.getSelectedValue();
        if (sel != null) {
            host.setPermission(sel.id(), name, granted);
        }
    }

    /** The selected extension's declared permissions from the host (test seam). */
    final List<PermissionInfo> selectedPermissions() {
        ExtensionInfo sel = extList.getSelectedValue();
        return (sel == null) ? List.of() : host.permissionsFor(sel.id());
    }

    private void refreshToggleButtons() {
        boolean hasSelection = extList.getSelectedIndex() >= 0;
        enableButton.setEnabled(hasSelection);
        disableButton.setEnabled(hasSelection);
    }

    /** Refreshes the shortcut label and the Rebind / Clear buttons. */
    private void updateShortcutLabel() {
        int idx = currentActionIndex();
        boolean hasAction = idx >= 0;
        rebindButton.setEnabled(hasAction);
        clearShortcutButton.setEnabled(hasAction);
        if (!hasAction) {
            shortcutLabel.setText("Shortcut: \u2014");
            return;
        }
        String shown = AdvancedTextEditorPanel.formatAccelerator(host.getAccelerator(idx));
        shortcutLabel.setText("Shortcut: " + (shown.isEmpty() ? "(none)" : shown));
    }

    /** @return the host action index of the selected action row, or -1. */
    private int currentActionIndex() {
        int sel = list.getSelectedIndex();
        return isAction(sel) ? model.get(sel).actionIndex() : -1;
    }

    private void startCapturing() {
        if (currentActionIndex() < 0) {
            return;
        }
        capturing = true;
        shortcutLabel.setText("Press a shortcut\u2026");
        list.requestFocusInWindow();
    }

    private void stopCapturing() {
        capturing = false;
    }

    /** Rebinds the selected action's accelerator (test seam for key capture). */
    final void rebindSelectedAccelerator(String spec) {
        int idx = currentActionIndex();
        if (idx >= 0) {
            host.setAccelerator(idx, spec);
        }
    }

    /** Unbinds the selected action's accelerator (test seam for the Clear button). */
    final void clearSelectedAccelerator() {
        rebindSelectedAccelerator("");
    }

    /**
     * Builds a KeyStroke spec (such as {@code "control alt X"}) from a captured
     * key, or the empty string when the key is not assignable (only A-Z and
     * 0-9 can carry an extension shortcut). Pure.
     */
    static String keySpec(int keyCode, int modifiers) {
        String key = keyName(keyCode);
        if (key.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) {
            parts.add("control");
        }
        if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0) {
            parts.add("alt");
        }
        if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) {
            parts.add("shift");
        }
        if ((modifiers & InputEvent.META_DOWN_MASK) != 0) {
            parts.add("meta");
        }
        parts.add(key);
        return String.join(" ", parts);
    }

    /** @return the assignable token (A-Z, 0-9) for {@code keyCode}, else "". Pure. */
    static String keyName(int keyCode) {
        if (keyCode >= KeyEvent.VK_A && keyCode <= KeyEvent.VK_Z) {
            return String.valueOf((char) ('A' + (keyCode - KeyEvent.VK_A)));
        }
        if (keyCode >= KeyEvent.VK_0 && keyCode <= KeyEvent.VK_9) {
            return String.valueOf((char) ('0' + (keyCode - KeyEvent.VK_0)));
        }
        return "";
    }

    /** Renders category headers bold and greyed, actions as normal rows. */
    private static final class RowRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value,
                int index, boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    l, value, index, selected, focus);
            Row row = (Row) value;
            label.setText(row.text());
            if (row.header()) {
                label.setFont(label.getFont().deriveFont(Font.BOLD));
                label.setEnabled(false);
            } else {
                label.setFont(label.getFont().deriveFont(Font.PLAIN));
                label.setEnabled(true);
            }
            return label;
        }
    }

    /** Renders enabled extensions bold, disabled ones greyed. */
    private static final class ExtensionRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value,
                int index, boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    l, value, index, selected, focus);
            ExtensionInfo info = (ExtensionInfo) value;
            label.setText(info.toString());
            label.setEnabled(info.enabled());
            label.setFont(label.getFont().deriveFont(
                    info.enabled() ? Font.BOLD : Font.PLAIN));
            return label;
        }
    }
}
