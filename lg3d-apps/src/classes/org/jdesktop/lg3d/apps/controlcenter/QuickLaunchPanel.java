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
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2D;

/**
 * Quick Launch panel: edits the pinned shortcut strip on the conventional Swing
 * (2D) desktop's taskbar - the equivalent of the 3D taskbar's shortcut shelf -
 * over the {@link Desktop2D} control-center hooks. The pinned launchers are
 * listed in taskbar order and can be moved up/down, removed, reset to the seeded
 * defaults, and extended from the available start-menu applications; every
 * change writes straight through the hook (so it persists and the live taskbar
 * strip updates). Both lists are {@link JList}s (never a combo box) so the panel
 * keeps working when hosted offscreen in a {@code SwingNode}. With no 2D shell
 * running (3D mode or headless) the hooks report empty lists and the mutations
 * are no-ops, so the panel simply shows an empty strip.
 */
public class QuickLaunchPanel implements ControlPanel {

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final JLabel statusLabel = new JLabel(" ");

    private final List<Desktop2D.QuickLaunchItem> pinned = new ArrayList<>();
    private final DefaultListModel<String> pinnedModel = new DefaultListModel<>();
    private final JList<String> pinnedList = new JList<>(pinnedModel);
    private final JButton moveUp = new JButton("Move Up");
    private final JButton moveDown = new JButton("Move Down");
    private final JButton remove = new JButton("Remove");

    private final List<Desktop2D.QuickLaunchItem> candidates = new ArrayList<>();
    private final DefaultListModel<String> candidateModel = new DefaultListModel<>();
    private final JList<String> candidateList = new JList<>(candidateModel);
    private final JButton add = new JButton("Add");

    private final JButton reset = new JButton("Reset to Defaults");

    public QuickLaunchPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Pinned strip, in taskbar order, with reorder / remove controls.
        pinnedList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        pinnedList.setVisibleRowCount(8);
        pinnedList.addListSelectionListener(e -> updateButtons());
        JScrollPane pinnedScroll = new JScrollPane(pinnedList);
        pinnedScroll.setBorder(
                BorderFactory.createTitledBorder("Pinned (taskbar order)"));
        moveUp.addActionListener(e -> moveSelected(-1));
        moveDown.addActionListener(e -> moveSelected(1));
        remove.addActionListener(e -> removeSelected());
        JPanel pinnedButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        pinnedButtons.add(moveUp);
        pinnedButtons.add(moveDown);
        pinnedButtons.add(remove);
        JPanel pinnedPanel = new JPanel(new BorderLayout(4, 4));
        pinnedPanel.add(pinnedScroll, BorderLayout.CENTER);
        pinnedPanel.add(pinnedButtons, BorderLayout.SOUTH);

        // Available start-menu applications, with the add control.
        candidateList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        candidateList.setVisibleRowCount(8);
        candidateList.addListSelectionListener(e -> updateButtons());
        JScrollPane candidateScroll = new JScrollPane(candidateList);
        candidateScroll.setBorder(
                BorderFactory.createTitledBorder("Available applications"));
        candidateScroll.setPreferredSize(new Dimension(230, 200));
        add.addActionListener(e -> addSelected());
        JPanel candidatePanel = new JPanel(new BorderLayout(4, 4));
        candidatePanel.add(candidateScroll, BorderLayout.CENTER);
        candidatePanel.add(add, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(pinnedPanel, BorderLayout.CENTER);
        center.add(candidatePanel, BorderLayout.EAST);

        reset.addActionListener(e -> resetDefaults());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        south.add(reset);

        root.add(statusLabel, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        reload();
    }

    @Override
    public String displayName() {
        return "Quick Launch";
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

    /** Re-reads both lists through the hooks, keeping the pinned selection. */
    private void reload() {
        int selected = pinnedList.getSelectedIndex();

        pinned.clear();
        pinned.addAll(Desktop2D.quickLaunchPinned());
        pinnedModel.clear();
        for (Desktop2D.QuickLaunchItem item : pinned) {
            pinnedModel.addElement(labelFor(item));
        }

        candidates.clear();
        candidates.addAll(Desktop2D.quickLaunchCandidates());
        candidateModel.clear();
        for (Desktop2D.QuickLaunchItem item : candidates) {
            candidateModel.addElement(labelFor(item));
        }

        if (!pinned.isEmpty()) {
            pinnedList.setSelectedIndex(
                    Math.max(0, Math.min(selected, pinned.size() - 1)));
        }
        updateButtons();
        statusLabel.setText(pinned.size() + " pinned; "
                + candidates.size() + " available");
    }

    /** Enables each button only when its list has a usable selection. */
    private void updateButtons() {
        int index = pinnedList.getSelectedIndex();
        moveUp.setEnabled(index > 0);
        moveDown.setEnabled(index >= 0 && index < pinned.size() - 1);
        remove.setEnabled(index >= 0);
        add.setEnabled(candidateList.getSelectedIndex() >= 0);
    }

    private void moveSelected(int delta) {
        int index = pinnedList.getSelectedIndex();
        if (index < 0) {
            statusLabel.setText("Select a pinned launcher first.");
            return;
        }
        int target = index + delta;
        if (target < 0 || target >= pinned.size()) {
            return;
        }
        Desktop2D.quickLaunchMove(index, target);
        reload();
        pinnedList.setSelectedIndex(target);
        updateButtons();
    }

    private void removeSelected() {
        int index = pinnedList.getSelectedIndex();
        if (index < 0 || index >= pinned.size()) {
            statusLabel.setText("Select a pinned launcher to remove first.");
            return;
        }
        Desktop2D.quickLaunchUnpin(pinned.get(index).command());
        reload();
    }

    private void addSelected() {
        int index = candidateList.getSelectedIndex();
        if (index < 0 || index >= candidates.size()) {
            statusLabel.setText("Select an application to pin first.");
            return;
        }
        Desktop2D.QuickLaunchItem item = candidates.get(index);
        Desktop2D.quickLaunchPin(item.command());
        reload();
        statusLabel.setText("Pinned " + labelFor(item));
    }

    private void resetDefaults() {
        Desktop2D.quickLaunchResetDefaults();
        reload();
        statusLabel.setText("Reset to the default quick launchers.");
    }

    /**
     * The list label for a launcher: its name, falling back to the launch command
     * when the name is blank. Pure so it can be unit-tested headless.
     */
    static String labelFor(Desktop2D.QuickLaunchItem item) {
        if (item == null) {
            return "";
        }
        String name = item.name();
        return (name == null || name.isBlank()) ? item.command() : name;
    }
}
