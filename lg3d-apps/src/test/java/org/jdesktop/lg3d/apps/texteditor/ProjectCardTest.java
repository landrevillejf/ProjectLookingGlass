/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.texteditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JTextField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link ProjectCard}, the Espresso "Projet" menu card:
 * every button routes exactly one {@link ProjectCard.Host} callback, and
 * {@code load} repaints the header and the recent-project list. No chooser or
 * dialog is ever constructed here; the host implementation (and its file
 * choosers) is covered by the panel tests.
 */
class ProjectCardTest {

    /** Records every callback the card makes, in order. */
    private static final class RecordingHost implements ProjectCard.Host {
        final List<String> calls = new ArrayList<>();

        @Override
        public void newProject(String name) {
            calls.add("new:" + name);
        }

        @Override
        public void openProjectFolder() {
            calls.add("openFolder");
        }

        @Override
        public void reRootToCurrentFile() {
            calls.add("reroot");
        }

        @Override
        public void closeProject() {
            calls.add("close");
        }

        @Override
        public void openGitGui() {
            calls.add("git");
        }

        @Override
        public void openRecentProject(String path) {
            calls.add("openRecent:" + path);
        }

        @Override
        public void removeRecentProject(String path) {
            calls.add("removeRecent:" + path);
        }

        @Override
        public void clearRecentProjects() {
            calls.add("clearProjects");
        }

        @Override
        public void closeCard() {
            calls.add("back");
        }
    }

    private static java.awt.Component find(Container root, String name) {
        if (name.equals(root.getName())) {
            return root;
        }
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof Container container) {
                java.awt.Component found = find(container, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static AbstractButton button(ProjectCard card, String name) {
        return (AbstractButton) find(card, name);
    }

    @SuppressWarnings("unchecked")
    private static JList<String> list(ProjectCard card) {
        return (JList<String>) find(card, "recentProjectsList");
    }

    private static JTextField field(ProjectCard card) {
        return (JTextField) find(card, "projectNameField");
    }

    @Test
    @DisplayName("the action row routes each button to its host callback")
    void actionRouting() {
        RecordingHost host = new RecordingHost();
        ProjectCard card = new ProjectCard(host);
        field(card).setText("demo-app");

        button(card, "newProjectButton").doClick();
        button(card, "openProjectFolderButton").doClick();
        button(card, "gitGuiButton").doClick();
        assertEquals(List.of("new:demo-app", "openFolder", "git"), host.calls);
    }

    @Test
    @DisplayName("the list buttons route selection and the back button closes")
    void listRouting() {
        RecordingHost host = new RecordingHost();
        ProjectCard card = new ProjectCard(host);
        card.load("/ws/demo", List.of("/ws/demo", "/ws/old"));
        assertEquals(2, card.entryCount());
        assertEquals("Project: /ws/demo", card.rootLabelText());

        list(card).setSelectedIndex(0);
        JButton open = null;
        JButton remove = null;
        JButton clear = null;
        JButton back = null;
        for (java.awt.Component c : allButtons(card)) {
            JButton b = (JButton) c;
            switch (b.getText()) {
                case "Open" -> open = b;
                case "Remove" -> remove = b;
                case "Clear List" -> clear = b;
                case "Back to Editor" -> back = b;
                default -> { }
            }
        }
        assertTrue(open.isEnabled() && remove.isEnabled());
        open.doClick();
        remove.doClick();
        clear.doClick();
        back.doClick();
        assertEquals(List.of("openRecent:/ws/demo", "removeRecent:/ws/demo",
                "clearProjects", "back"), host.calls);
    }

    private static List<java.awt.Component> allButtons(Container root) {
        List<java.awt.Component> out = new ArrayList<>();
        if (root instanceof JButton) {
            out.add(root);
        }
        for (java.awt.Component c : root.getComponents()) {
            if (c instanceof Container container) {
                out.addAll(allButtons(container));
            }
        }
        return out;
    }

    @Test
    @DisplayName("load with no project and no recents clears the card")
    void emptyLoad() {
        RecordingHost host = new RecordingHost();
        ProjectCard card = new ProjectCard(host);
        card.load(null, null);
        assertEquals("No project", card.rootLabelText());
        assertEquals(0, card.entryCount());
        assertNull(card.selectedPath());
        for (java.awt.Component c : allButtons(card)) {
            if (c instanceof JButton b && b.getText().equals("Remove")) {
                assertFalse(b.isEnabled(), "nothing to remove without a selection");
            }
        }
        assertEquals(0, host.calls.size());
    }
}
