/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

/**
 * The bottom Completions strip (Espresso Phase 4): a plain {@link JList} of
 * candidate strings fed by the completion extension through
 * {@link org.jdesktop.lg3d.apps.texteditor.ext.EditorContext#publishCompletions}.
 * Accepting a candidate (Enter or Tab on a selected row, or double-click)
 * hands it to the host hook, which inserts it at the caret — no popups and no
 * dialogs, so the strip renders correctly when the editor is captured
 * offscreen. EDT-only.
 */
final class CompletionPanel extends JPanel {

    private final JLabel titleLabel = new JLabel("Completions");
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);
    private Consumer<String> onActivate = s -> { };

    CompletionPanel() {
        super(new BorderLayout());
        setName("completionPanel");

        JPanel header = new JPanel(new BorderLayout());
        titleLabel.setName("completionTitle");
        header.add(titleLabel, BorderLayout.WEST);

        list.setName("completionList");
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    activateSelected();
                }
            }
        });
        // Enter/Tab accept while the strip has focus; the editor's own Tab
        // (indent) and Enter (newline) are untouched while it does not.
        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_TAB) {
                    e.consume();
                    activateSelected();
                }
            }
        });

        add(header, BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
        setPreferredSize(new Dimension(240, 160));
    }

    /**
     * Replaces the candidate list. A null/empty list clears the strip and
     * relabels it; the first row is pre-selected so one Enter accepts.
     */
    void setCompletions(String title, List<String> candidates) {
        model.clear();
        if (candidates != null) {
            for (String c : candidates) {
                if (c != null) {
                    model.addElement(c);
                }
            }
        }
        titleLabel.setText((title == null || title.isBlank())
                ? "Completions" : title + " (" + model.getSize() + ")");
        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    /** Empties the strip. */
    void clear() {
        setCompletions(null, List.of());
    }

    /** Host hook: invoked with the accepted candidate text. */
    void setOnActivate(Consumer<String> handler) {
        this.onActivate = (handler != null) ? handler : s -> { };
    }

    /** @return the selected candidate, or null when the strip is empty. */
    String selectedCompletion() {
        return list.getSelectedValue();
    }

    /** Accepts the current selection (Enter/Tab/double-click path). */
    void activateSelected() {
        String value = list.getSelectedValue();
        if (value != null) {
            onActivate.accept(value);
        }
    }

    /** @return the strip header text (test seam). */
    String titleText() {
        return titleLabel.getText();
    }

    /** @return how many candidates are shown (test seam). */
    int rowCount() {
        return model.getSize();
    }

    /** @return the candidate at {@code index} (test seam). */
    String rowAt(int index) {
        return model.get(index);
    }
}
