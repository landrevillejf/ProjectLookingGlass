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
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

/**
 * The central JDK manager card: list the JDKs Espresso knows about, download a
 * new one into the shared store, register a folder the user already has, and
 * forget one. Following the card rule for every secondary surface, it is a plain
 * in-panel {@link JPanel} &mdash; no popup, no modal dialog, the selector is a
 * {@code JList} &mdash; so it presents correctly when the editor is captured
 * offscreen by a {@code SwingNode}.
 *
 * <p>JDKs live in one shared store, never per project: a project only
 * <em>selects</em> one of the entries listed here (see {@link JdkRegistry} and
 * {@link ProjectToolchain}). Everything substantive &mdash; the off-EDT
 * download, the {@code JFileChooser} for registering a folder, the delete on
 * remove &mdash; is delegated to the {@link Host}; this class only collects
 * intent and renders {@link JdkRegistry#list()}. The palette belongs to the
 * active look and feel, so the list wears a {@link DefaultListCellRenderer} and
 * the card paints no colours of its own.</p>
 */
final class ManageJdksCard extends JPanel {

    /** The panel-side callbacks for the JDK manager card. */
    interface Host {

        /** Downloads {@code version} into the shared store, off the EDT. */
        void downloadJdk(String version);

        /** Asks for an existing JDK folder and registers it with the manager. */
        void registerJdkFolder();

        /** Forgets the JDK with this id (deleting it when Espresso manages it). */
        void removeJdk(String id);

        /** Returns to the editor card. */
        void closeCard();
    }

    private final Host host;
    private JdkRegistry registry;
    private final JTextField versionField = new JTextField(10);
    private final DefaultListModel<JdkEntry> model = new DefaultListModel<>();
    private final JList<JdkEntry> list = new JList<>(model);
    private final JButton removeButton = new JButton("Remove");

    ManageJdksCard(JdkRegistry registry, Host host) {
        this.registry = registry;
        this.host = host;
        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        setName("manageJdksCard");

        versionField.setName("jdkVersionField");
        versionField.setToolTipText("Feature version to download into the shared store (e.g. 21, 25)");
        JButton download = new JButton("Download");
        download.setName("downloadJdkButton");
        download.setToolTipText("Download this JDK version into the shared store");
        download.addActionListener(e -> host.downloadJdk(versionField.getText().trim()));

        JButton register = new JButton("Register Existing Folder\u2026");
        register.setName("registerJdkButton");
        register.setToolTipText("Point Espresso at a JDK folder you already have");
        register.addActionListener(e -> host.registerJdkFolder());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel("JDK version:"));
        top.add(versionField);
        top.add(download);
        top.add(register);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(10);
        list.setName("jdkList");
        list.setCellRenderer(new JdkRenderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                syncButtons();
            }
        });

        removeButton.setName("removeJdkButton");
        removeButton.addActionListener(e -> {
            JdkEntry selected = list.getSelectedValue();
            if (selected != null) {
                host.removeJdk(selected.id());
            }
        });
        JButton back = new JButton("Back to Editor");
        back.setName("jdkBackButton");
        back.addActionListener(e -> host.closeCard());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        buttons.add(removeButton);
        buttons.add(back);

        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.add(new JLabel("Managed and registered JDKs \u2014 a project selects one;"
                + " none is ever copied into it"), BorderLayout.NORTH);
        center.add(new JScrollPane(list), BorderLayout.CENTER);
        center.add(buttons, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        refresh();
    }

    /**
     * Repopulates the list from the registry, keeping the current selection when
     * its id survives. The host calls this after any download/register/remove so
     * the card always reflects {@link JdkRegistry#list()}.
     */
    void refresh() {
        JdkEntry keep = list.getSelectedValue();
        model.clear();
        for (JdkEntry entry : registry.list()) {
            model.addElement(entry);
        }
        if (keep != null) {
            for (int i = 0; i < model.size(); i++) {
                if (model.get(i).id().equals(keep.id())) {
                    list.setSelectedIndex(i);
                    break;
                }
            }
        }
        syncButtons();
    }

    /** The number of JDKs shown (test seam). */
    int jdkCount() {
        return model.size();
    }

    /** The selected JDK's id, or null when nothing is selected (test seam). */
    String selectedId() {
        JdkEntry selected = list.getSelectedValue();
        return (selected == null) ? null : selected.id();
    }

    /** The typed download version (test seam). */
    String versionText() {
        return versionField.getText();
    }

    /** Points the card at a different manager and re-reads it (test seam). */
    void setRegistryForTesting(JdkRegistry replacement) {
        this.registry = replacement;
        refresh();
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
