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
package org.jdesktop.lg3d.apps.webbrowser;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import org.jdesktop.lg3d.apps.webbrowser.ext.ExtensionRegistry;
import org.jdesktop.lg3d.apps.webbrowser.ext.ExtensionRegistry.LoadedExtension;
import org.jdesktop.lg3d.apps.webbrowser.ext.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The in-browser extension manager: a modal Swing dialog listing every
 * discovered {@link org.jdesktop.lg3d.apps.webbrowser.ext.BrowserExtension},
 * letting the user enable/disable each, inspect and edit the granted
 * {@link Permission permissions}, rescan, and open the third-party extensions
 * folder.
 *
 * <p>Enabling a third-party extension is the user's explicit approval: a
 * confirmation lists the permissions it will be granted, and declining leaves
 * it disabled. This is a consent/UX boundary, not a JVM sandbox &mdash; in-JVM
 * code is trusted; the gate keeps an over-reaching extension from silently
 * acting without the user seeing what it asked for.</p>
 */
public final class ExtensionManagerDialog extends JDialog {

    private static final Logger LOG = LoggerFactory.getLogger(ExtensionManagerDialog.class);

    private final ExtensionRegistry registry;
    private final ExtensionTableModel model;
    private final JTable table;

    /**
     * @param parent   the owner component (used to find the parent frame)
     * @param registry the registry to inspect and mutate
     */
    public ExtensionManagerDialog(Component parent, ExtensionRegistry registry) {
        super(parentFrame(parent), "Extensions", true);
        this.registry = registry;
        this.model = new ExtensionTableModel(registry);
        this.table = new JTable(model);
        table.getColumnModel().getColumn(0).setMaxWidth(70);
        table.getColumnModel().getColumn(2).setMaxWidth(90);
        table.setRowHeight(24);
        getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        getContentPane().add(buildHeader(), BorderLayout.NORTH);
        getContentPane().add(buildButtons(), BorderLayout.SOUTH);
        setSize(680, 420);
        setLocationRelativeTo(parent);
    }

    private static Frame parentFrame(Component parent) {
        if (parent instanceof Frame) {
            return (Frame) parent;
        }
        if (parent != null) {
            Frame f = JOptionPane.getFrameForComponent(parent);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
        JLabel title = new JLabel("<html><b>Browser extensions</b><br>"
                + "Enable or disable extensions and review the permissions they hold. "
                + "Drop third-party jars into the extensions folder, then Rescan.</html>");
        header.add(title, BorderLayout.CENTER);
        return header;
    }

    private JPanel buildButtons() {
        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        JButton permissions = new JButton("Permissions\u2026");
        permissions.addActionListener(e -> editPermissions());
        JButton rescan = new JButton("Rescan");
        rescan.addActionListener(e -> {
            registry.scan();
            model.refresh();
        });
        JButton openFolder = new JButton("Open extensions folder");
        openFolder.addActionListener(e -> openFolder());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        south.add(permissions);
        south.add(rescan);
        south.add(openFolder);
        south.add(close);
        return south;
    }

    private void editPermissions() {
        int row = table.getSelectedRow();
        if (row < 0 || row >= model.rows().size()) {
            JOptionPane.showMessageDialog(this, "Select an extension first.",
                    "Permissions", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        LoadedExtension le = model.rows().get(row);
        Set<Permission> granted = EnumSet.noneOf(Permission.class);
        granted.addAll(le.getGranted());
        List<JCheckBox> boxes = new ArrayList<>();
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        for (Permission p : Permission.values()) {
            JCheckBox box = new JCheckBox(p.name(), granted.contains(p));
            boxes.add(box);
            panel.add(box);
        }
        int result = JOptionPane.showConfirmDialog(this, panel,
                "Permissions \u2014 " + le.getManifest().getName(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        Set<Permission> chosen = EnumSet.noneOf(Permission.class);
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i).isSelected()) {
                chosen.add(Permission.values()[i]);
            }
        }
        registry.grant(le.getManifest().getId(), chosen);
        model.refresh();
    }

    private void openFolder() {
        Path dir = registry.getExtensionsDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not create extensions dir {}", dir, e);
        }
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            try {
                Desktop.getDesktop().open(dir.toFile());
                return;
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not open extensions dir {}", dir, e);
            }
        }
        JOptionPane.showMessageDialog(this,
                "Extensions folder:\n" + dir.toAbsolutePath(),
                "Extensions folder", JOptionPane.INFORMATION_MESSAGE);
    }

    private boolean confirmEnable(LoadedExtension le) {
        Set<Permission> perms = le.getManifest().getPermissions();
        String list = perms.isEmpty() ? "(none)" : String.join(", ",
                perms.stream().map(Enum::name).toArray(String[]::new));
        int result = JOptionPane.showConfirmDialog(this,
                "<html><b>" + le.getManifest().getName() + "</b> "
                        + le.getManifest().getVersion() + "<br>by "
                        + (le.getManifest().getAuthor().isBlank()
                                ? "unknown" : le.getManifest().getAuthor())
                        + "<br><br>" + le.getManifest().getDescription()
                        + "<br><br>Loaded from: " + le.getSourcePath()
                        + "<br><br>This extension requests the permissions:<br><b>"
                        + list + "</b><br><br>Grant them and enable?</html>",
                "Approve extension", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return result == JOptionPane.YES_OPTION;
    }

    /** Table model over the registry's discovered extensions. */
    private final class ExtensionTableModel extends AbstractTableModel {

        private final String[] columns = {"Enabled", "Name", "Version", "Source", "Permissions"};
        private final ExtensionRegistry registry;
        private List<LoadedExtension> rows = new ArrayList<>();

        ExtensionTableModel(ExtensionRegistry registry) {
            this.registry = registry;
            refresh();
        }

        void refresh() {
            rows = registry.extensions();
            fireTableDataChanged();
        }

        List<LoadedExtension> rows() {
            return rows;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return (column == 0) ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0;
        }

        @Override
        public Object getValueAt(int row, int column) {
            LoadedExtension le = rows.get(row);
            switch (column) {
                case 0: return le.isEnabled();
                case 1: return le.getManifest().getName();
                case 2: return le.getManifest().getVersion();
                case 3: return le.isBuiltin() ? "Built-in" : le.getSourcePath();
                case 4: return permissionSummary(le);
                default: return "";
            }
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column != 0 || !(value instanceof Boolean)) {
                return;
            }
            LoadedExtension le = rows.get(row);
            boolean want = (Boolean) value;
            // Enabling a third-party extension is the approval gate.
            if (want && !le.isBuiltin() && le.getGranted().isEmpty() && !confirmEnable(le)) {
                SwingUtilities.invokeLater(this::refresh);
                return;
            }
            registry.setEnabled(le.getManifest().getId(), want);
            refresh();
        }

        private String permissionSummary(LoadedExtension le) {
            Set<Permission> declared = le.getManifest().getPermissions();
            if (declared.isEmpty()) {
                return "(none)";
            }
            StringBuilder sb = new StringBuilder();
            for (Permission p : declared) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(p.name());
                if (!le.getGranted().contains(p)) {
                    sb.append(" (not granted)");
                }
            }
            return sb.toString();
        }
    }
}
