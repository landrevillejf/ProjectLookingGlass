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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D;
import org.jdesktop.lg3d.displayserver.desktop2d.ShortcutMap;
import org.jdesktop.lg3d.displayserver.desktop2d.WorkspaceModel;
import org.jdesktop.lg3d.utils.prefs.DesktopConfig;

/**
 * Shortcuts panel: rebinds the 2D desktop's global keyboard shortcuts. The action
 * is chosen from a {@link JList} and its keystroke typed into a {@link JTextField}
 * (text input is {@code SwingNode}-safe); free-form key capture is deliberately
 * avoided, since an offscreen-hosted panel cannot reliably receive raw key
 * events. Apply validates the spec through {@link ShortcutMap#parse(String)},
 * persists it to {@code DesktopConfig}'s {@code shortcuts.custom} overrides and
 * calls {@link Desktop2D#applyShortcuts()} so the running shell picks it up
 * immediately; Reset clears the overrides back to the built-in defaults.
 */
public class ShortcutsPanel implements ControlPanel {

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final List<String> actions = new ArrayList<>();
    private final DefaultListModel<String> actionModel = new DefaultListModel<>();
    private final JList<String> actionList = new JList<>(actionModel);
    private final JLabel currentLabel = new JLabel(" ");
    private final JTextField specField = new JTextField(18);
    private final JLabel statusLabel = new JLabel(" ");

    public ShortcutsPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        actions.addAll(actionIds());
        for (String action : actions) {
            actionModel.addElement(action);
        }
        actionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        actionList.setVisibleRowCount(10);
        actionList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showCurrent();
            }
        });
        JScrollPane scroll = new JScrollPane(actionList);
        scroll.setPreferredSize(new Dimension(240, 220));
        scroll.setBorder(BorderFactory.createTitledBorder("Action"));

        JPanel editor = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        editor.add(new JLabel("Key spec:"));
        editor.add(specField);
        JButton apply = new JButton("Apply");
        apply.addActionListener(e -> applyBinding());
        JButton reset = new JButton("Reset to defaults");
        reset.addActionListener(e -> resetDefaults());
        editor.add(apply);
        editor.add(reset);

        JLabel hint = new JLabel("<html>Spec form: modifiers + key, e.g. <b>control alt T</b>, "
                + "<b>alt F2</b>, <b>alt shift PAGE_DOWN</b>.</html>");
        JPanel right = new JPanel(new BorderLayout(4, 4));
        right.add(currentLabel, BorderLayout.NORTH);
        right.add(editor, BorderLayout.CENTER);
        right.add(hint, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(scroll, BorderLayout.WEST);
        center.add(right, BorderLayout.CENTER);

        root.add(statusLabel, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);

        reload();
    }

    @Override
    public String displayName() {
        return "Shortcuts";
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
        reload();
    }

    // ------------------------------------------------------------------

    private void reload() {
        int keep = actionList.getSelectedIndex();
        if (!actions.isEmpty()) {
            actionList.setSelectedIndex(keep >= 0 ? keep : 0);
        }
        showCurrent();
        statusLabel.setText(effectiveBindings().size() + " binding(s) active.");
    }

    private void showCurrent() {
        String action = actionList.getSelectedValue();
        if (action == null) {
            currentLabel.setText(" ");
            specField.setText("");
            return;
        }
        String spec = effectiveBindings().get(action);
        currentLabel.setText("Current:  " + action + "  =  "
                + (spec == null ? "(unbound)" : spec));
        specField.setText(spec == null ? "" : spec);
    }

    private void applyBinding() {
        String action = actionList.getSelectedValue();
        if (action == null) {
            statusLabel.setText("Select an action first.");
            return;
        }
        String spec = (specField.getText() == null) ? "" : specField.getText().trim();
        if (!isValidKeySpec(spec)) {
            statusLabel.setText("Invalid key spec: \"" + spec + "\".");
            return;
        }
        DesktopConfig cfg = DesktopConfig.get();
        Map<String, String> custom = DesktopConfig.parseCustomShortcuts(cfg.getCustomShortcuts());
        custom.put(action, spec);
        cfg.setCustomShortcuts(DesktopConfig.serializeCustomShortcuts(custom));
        cfg.save();
        Desktop2D.applyShortcuts();
        showCurrent();
        statusLabel.setText("Applied  " + action + "  =  " + spec + ".");
    }

    private void resetDefaults() {
        DesktopConfig cfg = DesktopConfig.get();
        cfg.setCustomShortcuts("");
        cfg.save();
        Desktop2D.applyShortcuts();
        reload();
        statusLabel.setText("Shortcuts reset to the built-in defaults.");
    }

    /**
     * The action ids a user may rebind: the named desktop actions plus the
     * per-workspace "move window" actions. Pure so it can be unit-tested.
     */
    static List<String> actionIds() {
        List<String> ids = new ArrayList<>();
        ids.add(ShortcutMap.SHOW_DESKTOP);
        ids.add(ShortcutMap.SNAP_LEFT);
        ids.add(ShortcutMap.SNAP_RIGHT);
        ids.add(ShortcutMap.SNAP_MAXIMIZE);
        ids.add(ShortcutMap.RUN_DIALOG);
        ids.add(ShortcutMap.OPEN_TERMINAL);
        ids.add(ShortcutMap.WINDOW_CLOSE);
        ids.add(ShortcutMap.WORKSPACE_NEXT);
        ids.add(ShortcutMap.WORKSPACE_PREVIOUS);
        for (int i = 0; i < WorkspaceModel.MAX_COUNT; i++) {
            ids.add(ShortcutMap.MOVE_TO_WORKSPACE_PREFIX + i);
        }
        return ids;
    }

    /**
     * The effective action -> keystroke-spec bindings: the defaults overlaid by
     * the persisted custom overrides, inverted from {@link ShortcutMap}'s
     * spec -> action form. Pure apart from reading {@link DesktopConfig}, so it
     * can be unit-tested headless.
     */
    static Map<String, String> effectiveBindings() {
        Map<String, String> specToAction = ShortcutMap.mergeBindings(
                DesktopConfig.parseCustomShortcuts(DesktopConfig.get().getCustomShortcuts()));
        Map<String, String> actionToSpec = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : specToAction.entrySet()) {
            actionToSpec.put(e.getValue(), e.getKey());
        }
        return actionToSpec;
    }

    /** True when {@code spec} parses to a real keystroke. Pure/testable. */
    static boolean isValidKeySpec(String spec) {
        return ShortcutMap.parse(spec) != null;
    }
}
