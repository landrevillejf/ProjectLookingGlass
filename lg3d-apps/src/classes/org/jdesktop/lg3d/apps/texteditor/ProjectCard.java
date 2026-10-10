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
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

/**
 * The Project card (Espresso "Projet" menu): create, open and manage project
 * folders from inside the editor. Following the card rule for every secondary
 * surface, it is a plain in-panel {@link JPanel} &mdash; no popup, no modal
 * dialog, selectors are {@code JList}s &mdash; so it presents correctly when
 * the editor is captured offscreen by a {@code SwingNode}.
 *
 * <p>Everything substantive (choosing directories, scaffolding the skeleton,
 * re-rooting the west tree, persisting recents, opening the Git view) is
 * delegated to the {@link Host}; this class only collects intent. The
 * "Git GUI\u2026" row is Espresso's integration point with the desktop Git
 * client ({@code org.jdesktop.lg3d.apps.gitgui.GitGuiPanel}), which the host
 * embeds as its own card.</p>
 */
public final class ProjectCard extends JPanel {

    /** The panel-side callbacks for the project card. */
    public interface Host {

        /**
         * Creates a new project skeleton named {@code name} after asking the
         * user for a parent directory, then attaches it.
         */
        void newProject(String name);

        /** Asks for an existing folder and attaches it as the project. */
        void openProjectFolder();

        /** Re-roots the tree on the project of the document being edited. */
        void reRootToCurrentFile();

        /** Detaches the manually attached project (back to per-file roots). */
        void closeProject();

        /** Opens the embedded Git GUI for the current project root. */
        void openGitGui();

        /** Attaches a recent project root (or reports it as missing). */
        void openRecentProject(String path);

        /** Forgets one entry from the persisted recent-project list. */
        void removeRecentProject(String path);

        /** Forgets every recent-project entry. */
        void clearRecentProjects();

        /** Returns to the editor card. */
        void closeCard();
    }

    private final Host host;
    private final JLabel rootLabel = new JLabel("No project");
    private final JTextField nameField = new JTextField(18);
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);
    private final JButton removeButton = new JButton("Remove");

    public ProjectCard(Host host) {
        this.host = host;
        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        setName("projectCard");

        rootLabel.setName("projectRootLabel");

        nameField.setName("projectNameField");
        nameField.setToolTipText("New project name (letters, digits, - and _)");
        JButton create = new JButton("New Project\u2026");
        create.setName("newProjectButton");
        create.addActionListener(e -> host.newProject(nameField.getText()));

        JButton openFolder = new JButton("Open Project Folder\u2026");
        openFolder.setName("openProjectFolderButton");
        openFolder.addActionListener(e -> host.openProjectFolder());
        JButton reroot = new JButton("Re-root to Current File");
        reroot.addActionListener(e -> host.reRootToCurrentFile());
        JButton close = new JButton("Close Project");
        close.addActionListener(e -> host.closeProject());
        JButton git = new JButton("Git GUI\u2026");
        git.setName("gitGuiButton");
        git.setToolTipText("Open the desktop Git client for this project");
        git.addActionListener(e -> host.openGitGui());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        actions.add(nameField);
        actions.add(create);
        actions.add(openFolder);
        actions.add(reroot);
        actions.add(close);
        actions.add(git);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(8);
        list.setName("recentProjectsList");
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                syncButtons();
            }
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && list.getSelectedValue() != null) {
                    host.openRecentProject(list.getSelectedValue());
                }
            }
        });

        JButton open = new JButton("Open");
        open.addActionListener(e -> {
            String path = list.getSelectedValue();
            if (path != null) {
                host.openRecentProject(path);
            }
        });
        removeButton.addActionListener(e -> {
            String path = list.getSelectedValue();
            if (path != null) {
                host.removeRecentProject(path);
            }
        });
        JButton clear = new JButton("Clear List");
        clear.addActionListener(e -> host.clearRecentProjects());
        JButton back = new JButton("Back to Editor");
        back.addActionListener(e -> host.closeCard());

        JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        listButtons.add(open);
        listButtons.add(removeButton);
        listButtons.add(clear);
        listButtons.add(back);

        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.add(new JLabel("Recent Projects (double-click to open)"),
                BorderLayout.NORTH);
        center.add(new JScrollPane(list), BorderLayout.CENTER);
        center.add(listButtons, BorderLayout.SOUTH);

        JPanel header = new JPanel(new BorderLayout());
        header.add(rootLabel, BorderLayout.NORTH);
        header.add(actions, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        syncButtons();
    }

    /**
     * Refreshes the card: the current project root in the header (or
     * "No project") and the persisted recent-project list.
     */
    public void load(String currentRoot, List<String> recentProjects) {
        rootLabel.setText((currentRoot == null || currentRoot.isBlank())
                ? "No project" : "Project: " + currentRoot);
        rootLabel.setToolTipText(currentRoot);
        model.clear();
        if (recentProjects != null) {
            for (String path : recentProjects) {
                model.addElement(path);
            }
        }
        syncButtons();
    }

    /** The typed new-project name (test seam). */
    public String projectNameText() {
        return nameField.getText();
    }

    /** The currently selected recent-project path, or null. */
    public String selectedPath() {
        return list.getSelectedValue();
    }

    /** The number of recent projects shown (test seam). */
    public int entryCount() {
        return model.size();
    }

    /** The header text (test seam). */
    public String rootLabelText() {
        return rootLabel.getText();
    }

    private void syncButtons() {
        removeButton.setEnabled(list.getSelectedValue() != null);
    }
}
