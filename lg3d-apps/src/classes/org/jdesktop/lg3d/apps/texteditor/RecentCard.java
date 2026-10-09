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
import java.awt.FlowLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

/**
 * The Recent Files card: the persisted list of recently opened paths, shown
 * in-panel (a {@link JList}, never a popup menu, so it presents correctly
 * inside the 3D desktop's offscreen capture). Double-click or Open reopens a
 * file; entries can be forgotten individually or as a whole.
 */
public final class RecentCard extends JPanel {

    /** The panel-side callbacks for the recent-files card. */
    public interface Host {

        /** Opens the selected path in a tab (or a new one). */
        void openRecent(String path);

        /** Forgets one entry from the persisted list. */
        void removeRecent(String path);

        /** Forgets every entry. */
        void clearRecent();

        /** Returns to the editor card. */
        void closeCard();
    }

    private final Host host;
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);
    private final JButton removeButton = new JButton("Remove");

    public RecentCard(Host host) {
        this.host = host;
        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(12);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                syncButtons();
            }
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && list.getSelectedValue() != null) {
                    host.openRecent(list.getSelectedValue());
                }
            }
        });

        JButton open = new JButton("Open");
        open.addActionListener(e -> {
            String path = list.getSelectedValue();
            if (path != null) {
                host.openRecent(path);
            }
        });
        removeButton.addActionListener(e -> {
            String path = list.getSelectedValue();
            if (path != null) {
                host.removeRecent(path);
            }
        });
        JButton clear = new JButton("Clear List");
        clear.addActionListener(e -> host.clearRecent());
        JButton back = new JButton("Back to Editor");
        back.addActionListener(e -> host.closeCard());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        buttons.add(open);
        buttons.add(removeButton);
        buttons.add(clear);
        buttons.add(back);

        add(new JLabel("Recent Files (double-click to open)"),
                BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        syncButtons();
    }

    /** Replaces the shown list with the persisted recent files. */
    public void load(List<String> recentFiles) {
        model.clear();
        if (recentFiles != null) {
            for (String path : recentFiles) {
                model.addElement(path);
            }
        }
        syncButtons();
    }

    /** The currently selected path, or null. */
    public String selectedPath() {
        return list.getSelectedValue();
    }

    /** The number of entries shown (test seam). */
    public int entryCount() {
        return model.size();
    }

    private void syncButtons() {
        boolean has = list.getSelectedValue() != null;
        removeButton.setEnabled(has);
    }
}
