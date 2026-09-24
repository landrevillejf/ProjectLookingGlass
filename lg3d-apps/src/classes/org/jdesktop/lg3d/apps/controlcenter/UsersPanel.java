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
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import org.jdesktop.lg3d.utils.system.PrivilegedRunner;
import org.jdesktop.lg3d.utils.system.UserService;

/**
 * User administration panel backed by {@link UserService}: lists local
 * accounts (optionally including system accounts), shows their details and
 * group membership, and performs add / edit / set-password / groups / remove
 * through {@code pkexec}. When polkit is unavailable the panel is read-only.
 */
public class UsersPanel implements ControlPanel {

    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final DefaultListModel<UserService.User> users = new DefaultListModel<>();
    private final JList<UserService.User> userList = new JList<>(users);
    private final JTextArea details = new JTextArea();
    private final JCheckBox showSystem = new JCheckBox("Show system accounts");

    private final JButton addButton = new JButton("Add User...");
    private final JButton editButton = new JButton("Edit...");
    private final JButton passwordButton = new JButton("Set Password...");
    private final JButton groupsButton = new JButton("Groups...");
    private final JButton removeButton = new JButton("Remove User...");
    private final JLabel noteLabel = new JLabel(" ");

    public UsersPanel() {
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetails();
            }
        });
        showSystem.addActionListener(e -> reload());

        details.setEditable(false);
        details.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));

        boolean writable = PrivilegedRunner.isAvailable();
        addButton.setEnabled(writable);
        editButton.setEnabled(writable);
        passwordButton.setEnabled(writable);
        groupsButton.setEnabled(writable);
        removeButton.setEnabled(writable);
        if (!writable) {
            noteLabel.setText("Privilege escalation (pkexec) is unavailable; users are read-only.");
        }

        addButton.addActionListener(e -> addUser());
        editButton.addActionListener(e -> editUser());
        passwordButton.addActionListener(e -> setPassword());
        groupsButton.addActionListener(e -> editGroups());
        removeButton.addActionListener(e -> removeUser());

        JSplitPaneH split = new JSplitPaneH();
        root.add(split, BorderLayout.CENTER);
        root.add(buildSouth(), BorderLayout.SOUTH);

        reload();
    }

    @Override
    public String displayName() {
        return "Users";
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

    /** Left list + right details in a horizontal split. */
    private final class JSplitPaneH extends javax.swing.JSplitPane {
        JSplitPaneH() {
            super(javax.swing.JSplitPane.HORIZONTAL_SPLIT,
                    buildLeft(), new JScrollPane(details));
            setDividerLocation(200);
            setResizeWeight(0.35);
            setBorder(BorderFactory.createEmptyBorder());
        }
    }

    private JComponent buildLeft() {
        JPanel left = new JPanel(new BorderLayout(4, 4));
        left.add(new JScrollPane(userList), BorderLayout.CENTER);
        showSystem.setOpaque(false);
        left.add(showSystem, BorderLayout.SOUTH);
        return left;
    }

    private JComponent buildSouth() {
        JPanel south = new JPanel(new BorderLayout());
        noteLabel.setForeground(new java.awt.Color(160, 90, 20));
        south.add(noteLabel, BorderLayout.NORTH);
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(addButton);
        row.add(editButton);
        row.add(passwordButton);
        row.add(groupsButton);
        row.add(removeButton);
        south.add(row, BorderLayout.SOUTH);
        return south;
    }

    private void reload() {
        long selectedUid = -1;
        UserService.User sel = userList.getSelectedValue();
        if (sel != null) {
            selectedUid = sel.getUid();
        }
        users.clear();
        for (UserService.User u : UserService.listUsers(showSystem.isSelected())) {
            users.addElement(u);
        }
        for (int i = 0; i < users.size(); i++) {
            if (users.get(i).getUid() == selectedUid) {
                userList.setSelectedIndex(i);
                return;
            }
        }
        if (!users.isEmpty()) {
            userList.setSelectedIndex(0);
        } else {
            showDetails();
        }
    }

    private void showDetails() {
        UserService.User u = userList.getSelectedValue();
        if (u == null) {
            details.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Login:     ").append(u.getName()).append('\n');
        sb.append("Full name: ").append(u.getFullName()).append('\n');
        sb.append("Type:      ").append(u.isSystem() ? "System / service" : "Standard").append('\n');
        sb.append("UID:       ").append(u.getUid()).append('\n');
        sb.append("GID:       ").append(u.getGid()).append('\n');
        sb.append("Home:      ").append(u.getHome()).append('\n');
        sb.append("Shell:     ").append(u.getShell()).append('\n');
        sb.append("Groups:    ").append(String.join(", ",
                UserService.groupsFor(u.getName()))).append('\n');
        if (u.getAvatarPath() != null) {
            sb.append("Avatar:    ").append(u.getAvatarPath()).append('\n');
        }
        details.setText(sb.toString());
        details.setCaretPosition(0);
    }

    // ------------------------------------------------------------------
    // Mutations

    private void addUser() {
        JTextField name = new JTextField(14);
        JTextField full = new JTextField(14);
        JTextField shell = new JTextField("/bin/bash", 14);
        JCheckBox createHome = new JCheckBox("Create home directory", true);
        JPanel form = form(new Object[] {
                "Username:", name, "Full name:", full, "Shell:", shell, "", createHome });
        if (JOptionPane.showConfirmDialog(root, form, "Add User",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        String username = name.getText().trim();
        if (username.isEmpty()) {
            warn("A username is required.");
            return;
        }
        if (UserService.userExists(username)) {
            warn("The user '" + username + "' already exists.");
            return;
        }
        report(UserService.addUser(username, full.getText().trim(),
                shell.getText().trim(), null, createHome.isSelected()),
                "User '" + username + "' created.");
    }

    private void editUser() {
        UserService.User u = userList.getSelectedValue();
        if (u == null) {
            return;
        }
        JTextField full = new JTextField(u.getFullName(), 14);
        JTextField shell = new JTextField(u.getShell(), 14);
        JTextField home = new JTextField(u.getHome(), 14);
        JPanel form = form(new Object[] {
                "Full name:", full, "Shell:", shell, "Home:", home });
        if (JOptionPane.showConfirmDialog(root, form, "Edit " + u.getName(),
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        report(UserService.modifyUser(u.getName(), full.getText().trim(),
                shell.getText().trim(), home.getText().trim()),
                "User '" + u.getName() + "' updated.");
    }

    private void setPassword() {
        UserService.User u = userList.getSelectedValue();
        if (u == null) {
            return;
        }
        JPasswordField pw = new JPasswordField(14);
        JPasswordField pw2 = new JPasswordField(14);
        JPanel form = form(new Object[] {
                "New password:", pw, "Confirm:", pw2 });
        if (JOptionPane.showConfirmDialog(root, form, "Set Password for " + u.getName(),
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        String a = new String(pw.getPassword());
        String b = new String(pw2.getPassword());
        if (!a.equals(b)) {
            warn("The passwords do not match.");
            return;
        }
        report(UserService.setPassword(u.getName(), a), "Password updated.");
    }

    private void editGroups() {
        UserService.User u = userList.getSelectedValue();
        if (u == null) {
            return;
        }
        List<String> all = new ArrayList<>();
        for (UserService.Group g : UserService.listGroups()) {
            all.add(g.getName());
        }
        List<String> current = UserService.groupsFor(u.getName());
        JList<String> groupList = new JList<>(all.toArray(new String[0]));
        groupList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        int[] indices = new int[current.size()];
        int n = 0;
        for (int i = 0; i < all.size(); i++) {
            if (current.contains(all.get(i))) {
                indices[n++] = i;
            }
        }
        groupList.setSelectedIndices(java.util.Arrays.copyOf(indices, n));
        if (JOptionPane.showConfirmDialog(root, new JScrollPane(groupList),
                "Groups for " + u.getName(),
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        List<String> chosen = new ArrayList<>(groupList.getSelectedValuesList());
        report(UserService.setGroups(u.getName(), chosen), "Groups updated.");
    }

    private void removeUser() {
        UserService.User u = userList.getSelectedValue();
        if (u == null) {
            return;
        }
        JCheckBox removeHome = new JCheckBox("Also remove the home directory", false);
        if (JOptionPane.showConfirmDialog(root,
                new Object[] { "Remove user '" + u.getName() + "'?", removeHome },
                "Remove User", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        report(UserService.deleteUser(u.getName(), removeHome.isSelected()),
                "User '" + u.getName() + "' removed.");
    }

    // ------------------------------------------------------------------

    private JPanel form(Object[] labelFieldPairs) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        for (int i = 0; i < labelFieldPairs.length; i += 2) {
            c.gridx = 0; c.gridy = row; c.weightx = 0;
            p.add(new JLabel((String) labelFieldPairs[i]), c);
            c.gridx = 1; c.weightx = 1;
            p.add((JComponent) labelFieldPairs[i + 1], c);
            row++;
        }
        return p;
    }

    private void report(PrivilegedRunner.PrivilegedResult r, String okMessage) {
        if (r.isSuccess()) {
            noteLabel.setText(okMessage);
            reload();
        } else if (r.getStatus() == PrivilegedRunner.Status.CANCELLED) {
            noteLabel.setText("The privilege prompt was cancelled; no change was made.");
        } else if (r.getStatus() == PrivilegedRunner.Status.UNAVAILABLE) {
            noteLabel.setText("Privilege escalation is unavailable; users are read-only.");
        } else {
            noteLabel.setText("Operation failed: " + r.getMessage());
            warn("The operation failed:\n" + r.getMessage());
        }
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(root, message, "Users",
                JOptionPane.WARNING_MESSAGE);
    }
}
