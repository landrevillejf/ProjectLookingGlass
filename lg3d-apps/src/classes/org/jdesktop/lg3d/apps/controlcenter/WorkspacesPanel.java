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
import org.jdesktop.lg3d.displayserver.desktop2d.WorkspaceModel;

/**
 * Workspaces panel: how many virtual desktops the 2D shell offers, which one is
 * shown, and how many windows sit on each, over the {@link Desktop2D}
 * control-center hooks. Both controls are {@link JList}s (never a combo box) so
 * the panel keeps working when hosted offscreen in a {@code SwingNode}. Changing
 * the count persists to {@code DesktopConfig} through the hook (so it survives a
 * restart) and resizes the live model; in 3D mode or headless the snapshot falls
 * back to the persisted count with no windows, and switching is a no-op.
 */
public class WorkspacesPanel implements ControlPanel {

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final DefaultListModel<String> countModel = new DefaultListModel<>();
    private final JList<String> countList = new JList<>(countModel);
    private final DefaultListModel<String> wsModel = new DefaultListModel<>();
    private final JList<String> wsList = new JList<>(wsModel);
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton applyCount = new JButton("Apply");
    private final JButton switchButton = new JButton("Switch");

    public WorkspacesPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        for (int n = WorkspaceModel.MIN_COUNT; n <= WorkspaceModel.MAX_COUNT; n++) {
            countModel.addElement(n + (n == 1 ? " workspace" : " workspaces"));
        }
        countList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        countList.setVisibleRowCount(5);
        JScrollPane countScroll = new JScrollPane(countList);
        countScroll.setPreferredSize(new Dimension(170, 130));
        applyCount.addActionListener(e -> applyCount());
        JPanel countPanel = new JPanel(new BorderLayout(4, 4));
        countPanel.setBorder(BorderFactory.createTitledBorder("Number of workspaces"));
        countPanel.add(countScroll, BorderLayout.CENTER);
        countPanel.add(applyCount, BorderLayout.SOUTH);

        wsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        wsList.setVisibleRowCount(8);
        JScrollPane wsScroll = new JScrollPane(wsList);
        wsScroll.setBorder(BorderFactory.createTitledBorder("Workspaces"));
        switchButton.addActionListener(e -> switchWorkspace());
        JPanel wsPanel = new JPanel(new BorderLayout(4, 4));
        wsPanel.add(wsScroll, BorderLayout.CENTER);
        wsPanel.add(switchButton, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(countPanel, BorderLayout.WEST);
        center.add(wsPanel, BorderLayout.CENTER);

        root.add(statusLabel, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);

        reload();
    }

    @Override
    public String displayName() {
        return "Workspaces";
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
        Desktop2D.WorkspaceSnapshot snap = Desktop2D.workspaceSnapshot();
        countList.setSelectedIndex(countIndexOf(snap.count()));
        wsModel.clear();
        List<Integer> counts = snap.windowCounts();
        for (int i = 0; i < snap.count(); i++) {
            int windows = (i < counts.size()) ? counts.get(i) : 0;
            String marker = (i == snap.current()) ? "   (current)" : "";
            wsModel.addElement("Workspace " + (i + 1) + "  -  "
                    + windows + " window(s)" + marker);
        }
        if (snap.count() > 0) {
            wsList.setSelectedIndex(Math.max(0, Math.min(snap.current(), snap.count() - 1)));
        }
        statusLabel.setText(snap.count() + " workspace(s); showing #" + (snap.current() + 1));
    }

    private void applyCount() {
        int index = countList.getSelectedIndex();
        if (index < 0) {
            statusLabel.setText("Select a workspace count first.");
            return;
        }
        Desktop2D.setWorkspaceCount(WorkspaceModel.MIN_COUNT + index);
        reload();
    }

    private void switchWorkspace() {
        int index = wsList.getSelectedIndex();
        if (index < 0) {
            statusLabel.setText("Select a workspace first.");
            return;
        }
        Desktop2D.switchWorkspace(index);
        reload();
    }

    /**
     * The count-list index for a workspace count (clamped to the model range).
     * Pure so it can be unit-tested headless.
     */
    static int countIndexOf(int count) {
        int clamped = Math.max(WorkspaceModel.MIN_COUNT,
                Math.min(WorkspaceModel.MAX_COUNT, count));
        return clamped - WorkspaceModel.MIN_COUNT;
    }
}
