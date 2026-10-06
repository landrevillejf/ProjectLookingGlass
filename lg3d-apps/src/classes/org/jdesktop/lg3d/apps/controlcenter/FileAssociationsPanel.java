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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DAppRegistry;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;
import org.jdesktop.lg3d.utils.prefs.FileAssociations;
import org.jdesktop.lg3d.utils.prefs.FileAssociations.Type;

/**
 * Default Applications panel: configures which application opens which kind of
 * file (for example, the desktop's PDF Viewer opens {@code .pdf}).
 *
 * <p>The left list shows the file types - the curated common set from
 * {@link FileAssociations#commonTypes()} plus any custom extension the user has
 * added, and any type that already has a stored association. Selecting a type
 * shows its current handler on the right; picking one of the desktop's installed
 * applications and pressing <b>Open with this</b> (or entering a
 * <b>Custom command…</b>) persists the association through
 * {@link FileAssociations}, and <b>Use system default</b> clears it so the file
 * type falls back to {@code xdg-open}.</p>
 *
 * <p>The associations take effect everywhere a file is opened, because
 * {@link org.jdesktop.lg3d.utils.system.Opener#open(java.nio.file.Path)}
 * consults them before the system default: the file manager, the desktop
 * Documents/Downloads folders and the dock stacks all honour the choice. A
 * handler naming one of the desktop's own applications opens inside the desktop
 * (hosted by {@code Desktop2D}); an external command runs as a child process.</p>
 *
 * <p>Uses {@link JList} selectors throughout, never a combo box, per the
 * offscreen {@code SwingNode} rule the control center follows.</p>
 */
public class FileAssociationsPanel implements ControlPanel {

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final DefaultListModel<TypeEntry> typeModel = new DefaultListModel<>();
    private final JList<TypeEntry> typeList = new JList<>(typeModel);
    private final DefaultListModel<ItemSpec> appModel = new DefaultListModel<>();
    private final JList<ItemSpec> appList = new JList<>(appModel);
    private final JLabel currentLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");

    /** A row in the type list: a canonical type key and its display label. */
    private static final class TypeEntry {
        final String key;
        final String label;

        TypeEntry(String key, String label) {
            this.key = key;
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public FileAssociationsPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        typeList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        typeList.setVisibleRowCount(14);
        typeList.setCellRenderer(new TypeRenderer());
        typeList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showCurrent();
            }
        });
        JScrollPane typeScroll = new JScrollPane(typeList);
        typeScroll.setPreferredSize(new Dimension(230, 300));
        typeScroll.setBorder(BorderFactory.createTitledBorder("File type"));

        appList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        appList.setVisibleRowCount(14);
        appList.setCellRenderer(new AppRenderer());
        JScrollPane appScroll = new JScrollPane(appList);
        appScroll.setBorder(BorderFactory.createTitledBorder("Application"));

        JButton assign = new JButton("Open with this");
        assign.addActionListener(e -> assignSelectedApp());
        JButton custom = new JButton("Custom command…");
        custom.addActionListener(e -> assignCustomCommand());
        JButton clear = new JButton("Use system default");
        clear.addActionListener(e -> clearAssociation());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(assign);
        buttons.add(custom);
        buttons.add(clear);

        JButton addType = new JButton("Add type…");
        addType.addActionListener(e -> addCustomType());
        JPanel typeButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        typeButtons.add(addType);

        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.add(typeScroll, BorderLayout.CENTER);
        left.add(typeButtons, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout(6, 6));
        right.add(currentLabel, BorderLayout.NORTH);
        right.add(appScroll, BorderLayout.CENTER);
        right.add(buttons, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(left, BorderLayout.WEST);
        center.add(right, BorderLayout.CENTER);

        root.add(statusLabel, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);

        reload();
    }

    @Override
    public String displayName() {
        return "Default Applications";
    }

    @Override
    public javax.swing.Icon icon() {
        return null;
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void onShow() {
        reload();
    }

    // ------------------------------------------------------------------

    /** Rebuilds the type and application lists, keeping the current selection. */
    private void reload() {
        String keep = (typeList.getSelectedValue() == null)
                ? null : typeList.getSelectedValue().key;
        typeModel.clear();
        for (TypeEntry entry : typeEntries()) {
            typeModel.addElement(entry);
        }
        selectTypeKey(keep);

        appModel.clear();
        for (ItemSpec item : installedApps()) {
            appModel.addElement(item);
        }
        showCurrent();
        int count = FileAssociations.get().handlers().size();
        statusLabel.setText(count + " association(s) configured.");
    }

    /**
     * The type rows to show: every common type, plus any type that already has a
     * stored association (so a custom extension survives a reload). Ordered by
     * label, de-duplicated by key.
     */
    private List<TypeEntry> typeEntries() {
        Set<String> seen = new LinkedHashSet<>();
        List<TypeEntry> entries = new ArrayList<>();
        for (Type t : FileAssociations.commonTypes()) {
            if (seen.add(t.key())) {
                entries.add(new TypeEntry(t.key(), t.toString()));
            }
        }
        for (String key : FileAssociations.get().handlers().keySet()) {
            if (seen.add(key)) {
                entries.add(new TypeEntry(key, describe(key)));
            }
        }
        entries.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return entries;
    }

    /**
     * The installed applications a file type can be handed to: the start-menu
     * items the desktop can actually run (an in-desktop panel/frame, or an
     * external command whose executable is present), de-duplicated by command.
     */
    private List<ItemSpec> installedApps() {
        List<ItemSpec> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ItemSpec item : Desktop2DMenuConfig.load().getItems()) {
            String command = item.getCommand();
            if (command == null || !seen.add(command)) {
                continue;
            }
            Desktop2DAppRegistry.Kind kind = Desktop2DAppRegistry.classify(command);
            boolean usable = kind == Desktop2DAppRegistry.Kind.PANEL
                    || kind == Desktop2DAppRegistry.Kind.SWING_FRAME
                    || (kind == Desktop2DAppRegistry.Kind.EXTERNAL
                        && Desktop2DAppRegistry.isExternalAvailable(command));
            if (usable) {
                out.add(item);
            }
        }
        out.sort((a, b) -> safeName(a).compareToIgnoreCase(safeName(b)));
        return out;
    }

    private void selectTypeKey(String key) {
        if (key == null) {
            if (typeModel.size() > 0) {
                typeList.setSelectedIndex(0);
            }
            return;
        }
        for (int i = 0; i < typeModel.size(); i++) {
            if (typeModel.get(i).key.equals(key)) {
                typeList.setSelectedIndex(i);
                return;
            }
        }
        if (typeModel.size() > 0) {
            typeList.setSelectedIndex(0);
        }
    }

    private void showCurrent() {
        TypeEntry entry = typeList.getSelectedValue();
        if (entry == null) {
            currentLabel.setText(" ");
            return;
        }
        String command = FileAssociations.get().handlerForType(entry.key);
        currentLabel.setText("Current:  " + entry.label + "  →  "
                + (command == null ? "(system default)" : appName(command)));
    }

    private void assignSelectedApp() {
        TypeEntry entry = typeList.getSelectedValue();
        ItemSpec app = appList.getSelectedValue();
        if (entry == null) {
            statusLabel.setText("Select a file type first.");
            return;
        }
        if (app == null) {
            statusLabel.setText("Select an application first.");
            return;
        }
        FileAssociations.get().setHandler(entry.key, app.getCommand());
        showCurrent();
        statusLabel.setText(safeName(app) + " now opens " + entry.label + " files.");
        refreshTypeRow(entry);
    }

    private void assignCustomCommand() {
        TypeEntry entry = typeList.getSelectedValue();
        if (entry == null) {
            statusLabel.setText("Select a file type first.");
            return;
        }
        String current = FileAssociations.get().handlerForType(entry.key);
        String command = (String) JOptionPane.showInputDialog(root,
                "Command that opens a " + entry.label + " file.\n"
                + "Use %f for the file path (appended if omitted):",
                "Custom command", JOptionPane.QUESTION_MESSAGE,
                null, null, current == null ? "" : current);
        if (command == null) {
            return;
        }
        if (command.isBlank()) {
            clearAssociation();
            return;
        }
        FileAssociations.get().setHandler(entry.key, command.trim());
        showCurrent();
        statusLabel.setText(entry.label + " now opens with: " + command.trim());
        refreshTypeRow(entry);
    }

    private void clearAssociation() {
        TypeEntry entry = typeList.getSelectedValue();
        if (entry == null) {
            statusLabel.setText("Select a file type first.");
            return;
        }
        FileAssociations.get().removeHandler(entry.key);
        showCurrent();
        statusLabel.setText(entry.label + " uses the system default again.");
        refreshTypeRow(entry);
    }

    private void addCustomType() {
        String ext = JOptionPane.showInputDialog(root,
                "File extension to associate (e.g. epub):", "Add file type",
                JOptionPane.QUESTION_MESSAGE);
        if (ext == null) {
            return;
        }
        String key = FileAssociations.normalizeTypeKey(ext.trim());
        if (key == null) {
            statusLabel.setText("Enter a valid extension.");
            return;
        }
        reload();
        selectTypeKey(key);
        // Add it to the list even before a handler is set, so it can be chosen.
        if (typeList.getSelectedValue() == null
                || !typeList.getSelectedValue().key.equals(key)) {
            typeModel.addElement(new TypeEntry(key, describe(key)));
            selectTypeKey(key);
        }
        showCurrent();
        statusLabel.setText("Now choose an application for " + describe(key) + ".");
    }

    /** Re-sorts/refreshes the type list without losing the selection. */
    private void refreshTypeRow(TypeEntry entry) {
        reload();
        selectTypeKey(entry.key);
    }

    /** The display label for a canonical type key. */
    private static String describe(String typeKey) {
        String label = FileAssociations.labelFor(typeKey);
        if (typeKey.startsWith(FileAssociations.EXT_PREFIX)) {
            return label + " (." + typeKey.substring(FileAssociations.EXT_PREFIX.length()) + ")";
        }
        return label;
    }

    /** The friendly name for a stored handler command (its app name, or itself). */
    private String appName(String command) {
        for (int i = 0; i < appModel.size(); i++) {
            ItemSpec item = appModel.get(i);
            if (command.equals(item.getCommand())) {
                return safeName(item);
            }
        }
        return command;
    }

    private static String safeName(ItemSpec item) {
        String name = item.getName();
        if (name == null || name.isBlank()) {
            String command = item.getCommand();
            return (command == null) ? "" : command;
        }
        return name;
    }

    // ------------------------------------------------------------------
    // Renderers

    /** Type rows show the label plus the current handler as a subtitle. */
    private final class TypeRenderer extends JLabel implements ListCellRenderer<TypeEntry> {
        @Override
        public Component getListCellRendererComponent(JList<? extends TypeEntry> list,
                TypeEntry value, int index, boolean selected, boolean focus) {
            String handler = FileAssociations.get().handlerForType(value.key);
            String sub = (handler == null) ? "system default" : appName(handler);
            setText("<html><b>" + value.label + "</b><br><font size='-2' color='gray'>"
                    + sub + "</font></html>");
            setOpaque(true);
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            return this;
        }
    }

    /** Application rows show the app name plus its command as a subtitle. */
    private static final class AppRenderer extends JLabel
            implements ListCellRenderer<ItemSpec> {
        @Override
        public Component getListCellRendererComponent(JList<? extends ItemSpec> list,
                ItemSpec value, int index, boolean selected, boolean focus) {
            String name = safeName(value);
            setText("<html><b>" + name + "</b><br><font size='-2' color='gray'>"
                    + value.getCommand() + "</font></html>");
            setOpaque(true);
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            return this;
        }
    }
}
