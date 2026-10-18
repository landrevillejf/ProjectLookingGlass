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
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

/**
 * The Project card (Espresso "Projet" menu): create, open and manage project
 * folders from inside the editor. Following the card rule for every secondary
 * surface, it is a plain in-panel {@link JPanel} &mdash; no popup, no modal
 * dialog, selectors are {@code JList}s and radio buttons, never combo boxes
 * &mdash; so it presents correctly when the editor is captured offscreen by a
 * {@code SwingNode}. The palette belongs to the active look and feel, so the
 * lists wear a {@link DefaultListCellRenderer} and the card paints no colours.
 *
 * <p>The New Project form collects a {@link NewProjectRequest}: a name, a build
 * system (Gradle, Maven or plain Java), that tool's version, and a JDK selected
 * from the central manager. The card only collects intent &mdash; choosing
 * directories, downloading the tool, scaffolding, re-rooting the west tree,
 * persisting recents, opening the Git view and the JDK manager are all delegated
 * to the {@link Host}. A project <em>selects</em> a JDK; none is ever downloaded
 * into it (see {@link ManageJdksCard} and {@link JdkRegistry}).</p>
 */
public final class ProjectCard extends JPanel {

    /** The panel-side callbacks for the project card. */
    public interface Host {

        /**
         * Creates a new project from {@code request} after asking the user for a
         * parent directory: scaffolds the sources, downloads the chosen build
         * tool into the project and records the selected JDK, then attaches it.
         */
        void newProject(NewProjectRequest request);

        /** Asks for an existing folder and attaches it as the project. */
        void openProjectFolder();

        /** Re-roots the tree on the project of the document being edited. */
        void reRootToCurrentFile();

        /** Detaches the manually attached project (back to per-file roots). */
        void closeProject();

        /** Opens the embedded Git GUI for the current project root. */
        void openGitGui();

        /** Opens the central JDK manager card. */
        void openJdkManager();

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
    private final JTextField nameField = new JTextField(16);
    private final JTextField versionField = new JTextField(8);
    private final JRadioButton gradleRadio = new JRadioButton("Gradle");
    private final JRadioButton mavenRadio = new JRadioButton("Maven");
    private final JRadioButton plainRadio = new JRadioButton("Plain Java");
    private final DefaultListModel<JdkEntry> jdkModel = new DefaultListModel<>();
    private final JList<JdkEntry> jdkList = new JList<>(jdkModel);
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);
    private final JButton removeButton = new JButton("Remove");

    public ProjectCard(Host host) {
        this.host = host;
        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        setName("projectCard");

        rootLabel.setName("projectRootLabel");
        add(buildHeader(), BorderLayout.NORTH);
        add(buildRecents(), BorderLayout.CENTER);
        syncButtons();
    }

    /** The header: the current root, the New Project form and the project actions. */
    private Component buildHeader() {
        JPanel header = new JPanel(new BorderLayout(4, 6));
        header.add(rootLabel, BorderLayout.NORTH);
        header.add(buildNewProjectForm(), BorderLayout.CENTER);
        header.add(buildProjectActions(), BorderLayout.SOUTH);
        return header;
    }

    /** The New Project form: name, build system, tool version and JDK selection. */
    private Component buildNewProjectForm() {
        nameField.setName("projectNameField");
        nameField.setToolTipText("New project name (letters, digits, - and _)");
        versionField.setName("projectVersionField");
        versionField.setToolTipText("Build-tool version to download into the project"
                + " (e.g. 8.10 for Gradle, 3.9.9 for Maven)");

        gradleRadio.setName("gradleRadio");
        mavenRadio.setName("mavenRadio");
        plainRadio.setName("plainRadio");
        gradleRadio.setSelected(true);
        ButtonGroup buildGroup = new ButtonGroup();
        buildGroup.add(gradleRadio);
        buildGroup.add(mavenRadio);
        buildGroup.add(plainRadio);
        // Plain Java downloads no tool, so its version field steps aside.
        gradleRadio.addActionListener(e -> syncVersionField());
        mavenRadio.addActionListener(e -> syncVersionField());
        plainRadio.addActionListener(e -> syncVersionField());

        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        nameRow.add(new JLabel("Name:"));
        nameRow.add(nameField);
        nameRow.add(new JLabel("Build:"));
        nameRow.add(gradleRadio);
        nameRow.add(mavenRadio);
        nameRow.add(plainRadio);
        nameRow.add(new JLabel("Version:"));
        nameRow.add(versionField);

        jdkList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        jdkList.setVisibleRowCount(3);
        jdkList.setName("jdkList");
        jdkList.setCellRenderer(new JdkRenderer());
        JButton manageJdks = new JButton("Manage JDKs\u2026");
        manageJdks.setName("manageJdksButton");
        manageJdks.setToolTipText("Download, register and select the JDKs Espresso manages");
        manageJdks.addActionListener(e -> host.openJdkManager());

        JPanel jdkRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        jdkRow.add(new JLabel("JDK:"));
        jdkRow.add(new JScrollPane(jdkList));
        jdkRow.add(manageJdks);

        JButton create = new JButton("New Project\u2026");
        create.setName("newProjectButton");
        create.addActionListener(e -> host.newProject(currentRequest()));
        JPanel createRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        createRow.add(create);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createTitledBorder("New Project"));
        nameRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        jdkRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        createRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(nameRow);
        form.add(jdkRow);
        form.add(createRow);
        form.add(Box.createVerticalStrut(2));
        syncVersionField();
        return form;
    }

    /** The open / re-root / close / Git actions for the attached project. */
    private Component buildProjectActions() {
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
        actions.add(openFolder);
        actions.add(reroot);
        actions.add(close);
        actions.add(git);
        return actions;
    }

    /** The recent-project list and its buttons. */
    private Component buildRecents() {
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
        return center;
    }

    /**
     * Refreshes the card: the current project root in the header (or
     * "No project"), the persisted recent-project list, and the JDKs the manager
     * offers (newest first), selecting the newest so a new project has a JDK by
     * default.
     */
    public void load(String currentRoot, List<String> recentProjects, List<JdkEntry> jdks) {
        rootLabel.setText((currentRoot == null || currentRoot.isBlank())
                ? "No project" : "Project: " + currentRoot);
        rootLabel.setToolTipText(currentRoot);
        model.clear();
        if (recentProjects != null) {
            for (String path : recentProjects) {
                model.addElement(path);
            }
        }
        jdkModel.clear();
        if (jdks != null) {
            for (JdkEntry entry : jdks) {
                jdkModel.addElement(entry);
            }
        }
        if (jdkModel.size() > 0) {
            jdkList.setSelectedIndex(0);
        }
        syncButtons();
    }

    /** The creation intent the form currently holds (test seam). */
    NewProjectRequest currentRequest() {
        BuildSystem buildSystem = selectedBuildSystem();
        String version = (buildSystem == BuildSystem.NONE) ? "" : versionField.getText().trim();
        JdkEntry jdk = jdkList.getSelectedValue();
        return new NewProjectRequest(nameField.getText(), buildSystem, version,
                (jdk == null) ? "" : jdk.id());
    }

    /** The build system the radios select (test seam). */
    BuildSystem selectedBuildSystem() {
        if (mavenRadio.isSelected()) {
            return BuildSystem.MAVEN;
        }
        if (plainRadio.isSelected()) {
            return BuildSystem.NONE;
        }
        return BuildSystem.GRADLE;
    }

    /** The typed new-project name (test seam). */
    public String projectNameText() {
        return nameField.getText();
    }

    /** The typed build-tool version (test seam). */
    public String versionText() {
        return versionField.getText();
    }

    /** Whether the version field is editable (false for plain Java; test seam). */
    public boolean versionEditable() {
        return versionField.isEnabled();
    }

    /** The selected JDK's id, or null when none is selected (test seam). */
    public String selectedJdkId() {
        JdkEntry jdk = jdkList.getSelectedValue();
        return (jdk == null) ? null : jdk.id();
    }

    /** The number of JDKs offered (test seam). */
    public int jdkCount() {
        return jdkModel.size();
    }

    /** Selects the JDK at {@code index} (test seam). */
    public void selectJdk(int index) {
        jdkList.setSelectedIndex(index);
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

    private void syncVersionField() {
        versionField.setEnabled(selectedBuildSystem().needsTool());
    }

    private void syncButtons() {
        removeButton.setEnabled(list.getSelectedValue() != null);
    }

    /** LAF-native row rendering: the look and feel owns the palette, not us. */
    private static final class JdkRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            String text = (value instanceof JdkEntry entry) ? entry.display() : "";
            return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
        }
    }
}
