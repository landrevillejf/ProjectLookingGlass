/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

/**
 * A small row of numbered workspace buttons for the 2D/Swing desktop's taskbar:
 * one toggle per workspace, the current one highlighted, each showing how many
 * windows it holds. Clicking a button switches the desktop to that workspace.
 *
 * <p>This is thin Swing glue over the pure {@link WorkspaceModel}: it reads the
 * count, the current index and the per-workspace window counts from the model,
 * and reports a click through a {@link Listener} (the shell switches and then
 * calls {@link #refresh()}). It holds no window state of its own.</p>
 */
final class WorkspacePager extends JPanel {

    /** Where a pager click goes; {@link Desktop2D} switches the workspace. */
    interface Listener {
        void switchToWorkspace(int index);
    }

    private final WorkspaceModel model;
    private final Listener listener;
    private final List<JToggleButton> buttons = new ArrayList<>();

    WorkspacePager(WorkspaceModel model, Listener listener) {
        super(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        setOpaque(false);
        this.model = model;
        this.listener = listener;
        rebuild();
    }

    /**
     * (Re)creates one button per workspace. Called on construction and again if
     * the workspace count ever changes, so the row always matches the model.
     */
    void rebuild() {
        removeAll();
        buttons.clear();
        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < model.count(); i++) {
            final int index = i;
            JToggleButton button = new JToggleButton(String.valueOf(i + 1));
            button.setFocusable(false);
            button.setMargin(new java.awt.Insets(0, 4, 0, 4));
            button.addActionListener(e -> listener.switchToWorkspace(index));
            group.add(button);
            buttons.add(button);
            add(button);
        }
        refresh();
    }

    /** Re-highlights the current workspace and refreshes the window counts. */
    void refresh() {
        for (int i = 0; i < buttons.size(); i++) {
            JToggleButton button = buttons.get(i);
            button.setSelected(i == model.current());
            int windows = model.countOn(i);
            button.setToolTipText("Workspace " + (i + 1)
                    + (windows == 0 ? " (empty)"
                            : " (" + windows + (windows == 1 ? " window)" : " windows)")));
        }
        revalidate();
        repaint();
    }

    /** How many workspace buttons are shown (package-visible for tests). */
    int buttonCount() {
        return buttons.size();
    }

    /** The index of the highlighted button, or -1 (package-visible for tests). */
    int selectedButton() {
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i).isSelected()) {
                return i;
            }
        }
        return -1;
    }
}
