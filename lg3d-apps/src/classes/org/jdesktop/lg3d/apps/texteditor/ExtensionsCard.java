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
 * The Extensions card: the flat list of every action contributed by the
 * installed {@link TextEditorExtension}s, labelled
 * {@code <extension>: <action>}. Selecting an entry and pressing Run (or
 * double-clicking) invokes it against the current tab. The card is a plain
 * {@link JList} view, so it works inside the 3D desktop's offscreen capture
 * like every other in-panel surface.
 */
public final class ExtensionsCard extends JPanel {

    /** The panel-side callbacks for the extensions card. */
    public interface Host {

        /** Runs the action with the given index into the loaded list. */
        void runExtensionAction(int index);

        /** Returns to the editor card. */
        void closeCard();
    }

    private final Host host;
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);
    private final JButton runButton = new JButton("Run");

    public ExtensionsCard(Host host) {
        this.host = host;
        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(12);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                runButton.setEnabled(list.getSelectedIndex() >= 0);
            }
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && list.getSelectedIndex() >= 0) {
                    host.runExtensionAction(list.getSelectedIndex());
                }
            }
        });

        runButton.addActionListener(e -> {
            int index = list.getSelectedIndex();
            if (index >= 0) {
                host.runExtensionAction(index);
            }
        });
        JButton back = new JButton("Back to Editor");
        back.addActionListener(e -> host.closeCard());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        buttons.add(runButton);
        buttons.add(back);

        add(new JLabel("Installed Extension Actions (double-click to run)"),
                BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        runButton.setEnabled(false);
    }

    /** Replaces the shown entries; index order matches {@code labels}. */
    public void load(List<String> labels) {
        model.clear();
        if (labels != null) {
            for (String label : labels) {
                model.addElement(label);
            }
        }
        runButton.setEnabled(false);
    }

    /** The number of actions shown (test seam). */
    public int entryCount() {
        return model.size();
    }

    /** Selects an entry programmatically (test seam). */
    public void select(int index) {
        list.setSelectedIndex(index);
        runButton.setEnabled(index >= 0 && index < model.size());
    }
}
