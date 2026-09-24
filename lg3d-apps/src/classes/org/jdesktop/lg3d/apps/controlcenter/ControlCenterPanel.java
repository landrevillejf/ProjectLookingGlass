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
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ListSelectionModel;

/**
 * The control center shell: a category navigation list on the left and a
 * {@link CardLayout} on the right showing the selected {@link ControlPanel}.
 * Panels are discovered from {@link ControlPanelRegistry}, and each is notified
 * via {@code onShow}/{@code onHide} as it becomes visible or is hidden.
 */
public class ControlCenterPanel extends JPanel {

    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final DefaultListModel<ControlPanel> model = new DefaultListModel<>();
    private final JList<ControlPanel> nav = new JList<>(model);
    private ControlPanel current;

    public ControlCenterPanel() {
        super(new BorderLayout());
        setPreferredSize(new Dimension(720, 500));
        setBackground(new java.awt.Color(238, 240, 244));

        for (ControlPanel p : ControlPanelRegistry.panels()) {
            model.addElement(p);
            cardPanel.add(p.component(), p.displayName());
        }

        nav.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nav.setCellRenderer(new NavRenderer());
        nav.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                select(nav.getSelectedValue());
            }
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(nav), cardPanel);
        split.setDividerLocation(160);
        split.setResizeWeight(0.2);
        split.setBorder(BorderFactory.createEmptyBorder());

        add(split, BorderLayout.CENTER);

        if (!model.isEmpty()) {
            nav.setSelectedIndex(0);
        }
    }

    private void select(ControlPanel p) {
        if (p == null || p == current) {
            return;
        }
        if (current != null) {
            current.onHide();
        }
        current = p;
        cards.show(cardPanel, p.displayName());
        p.onShow();
    }

    /** Navigation list renderer: category icon + display name. */
    private static final class NavRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            ControlPanel p = (ControlPanel) value;
            super.getListCellRendererComponent(list, p.displayName(), index,
                    isSelected, cellHasFocus);
            setIcon(p.icon());
            setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            return this;
        }
    }
}
