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
package org.jdesktop.lg3d.dbmanager.ui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import org.jdesktop.lg3d.dbmanager.jdbc.ConnectionProvider;
import org.jdesktop.lg3d.dbmanager.model.AppSettings;
import org.jdesktop.lg3d.dbmanager.model.ConnectionProfile;
import org.jdesktop.lg3d.dbmanager.model.DbDriver;
import org.jdesktop.lg3d.dbmanager.model.DriverRegistry;

/**
 * A modal form for creating or editing a {@link ConnectionProfile}: choose a
 * driver (which pre-fills the JDBC URL template and driver class), enter the
 * URL, credentials and per-connection options, and optionally test the
 * connection before saving.
 *
 * <p>Created on demand (never during panel construction), so the headless unit
 * tests never instantiate a {@code JDialog}.</p>
 */
public final class ConnectionDialog extends JDialog {

    private final DriverRegistry drivers;
    private final AppSettings settings;
    private final JComboBox<DbDriver> driverCombo = new JComboBox<>();
    private final JTextField nameField = new JTextField(24);
    private final JTextField urlField = new JTextField(30);
    private final JTextField classField = new JTextField(24);
    private final JTextField userField = new JTextField(16);
    private final JPasswordField passwordField = new JPasswordField(16);
    private final JCheckBox savePassword = new JCheckBox("Save password");
    private final JCheckBox autoCommit = new JCheckBox("Auto-commit", true);
    private final JTextField rowLimitField = new JTextField(
            String.valueOf(ConnectionProfile.DEFAULT_ROW_LIMIT), 6);
    private final JLabel statusLabel = new JLabel(" ");

    private ConnectionProfile editing;
    private ConnectionProfile result;

    /**
     * Builds the dialog.
     *
     * @param owner    the owning frame (may be {@code null})
     * @param drivers  the driver registry for the driver combo
     * @param settings the app settings (gates password saving)
     * @param existing the profile to edit, or {@code null} to create a new one
     */
    public ConnectionDialog(Frame owner, DriverRegistry drivers, AppSettings settings,
                            ConnectionProfile existing) {
        super(owner, (existing == null) ? "New Connection" : "Edit Connection", true);
        this.drivers = drivers;
        this.settings = (settings != null) ? settings : new AppSettings();
        this.editing = existing;

        List<DbDriver> list = drivers.getDrivers();
        for (DbDriver d : list) {
            driverCombo.addItem(d);
        }
        driverCombo.addActionListener(e -> onDriverSelected());

        savePassword.setEnabled(this.settings.isAllowSavePasswords());
        if (!this.settings.isAllowSavePasswords()) {
            savePassword.setToolTipText("Enable \"Allow saving passwords\" in Settings first");
        }

        JPanel form = buildForm();
        JPanel buttons = buildButtons();

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));
        content.add(form, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);

        if (existing != null) {
            loadProfile(existing);
        } else {
            onDriverSelected();
        }
        pack();
        setLocationRelativeTo(owner);
    }

    private JPanel buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        addRow(form, c, row++, "Name", nameField);
        addRow(form, c, row++, "Driver", driverCombo);
        addRow(form, c, row++, "JDBC URL", urlField);
        addRow(form, c, row++, "Driver class", classField);
        addRow(form, c, row++, "User", userField);
        addRow(form, c, row++, "Password", passwordField);

        c.gridx = 1;
        c.gridy = row++;
        form.add(savePassword, c);

        JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        opts.add(autoCommit);
        opts.add(new JLabel("Row limit:"));
        opts.add(rowLimitField);
        c.gridx = 1;
        c.gridy = row;
        form.add(opts, c);
        return form;
    }

    private void addRow(JPanel form, GridBagConstraints c, int row, String label,
                        java.awt.Component field) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(field, c);
    }

    private JPanel buildButtons() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        JButton test = new JButton("Test Connection");
        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        test.addActionListener(e -> doTest());
        ok.addActionListener(e -> doOk());
        cancel.addActionListener(e -> {
            result = null;
            dispose();
        });
        bar.add(statusLabel);
        bar.add(test);
        bar.add(ok);
        bar.add(cancel);
        return bar;
    }

    private void onDriverSelected() {
        DbDriver d = (DbDriver) driverCombo.getSelectedItem();
        if (d == null) {
            return;
        }
        // Only overwrite the URL/class when the user has not typed a custom one,
        // or when the field still holds another driver's template.
        if (urlField.getText().isBlank() || isKnownTemplate(urlField.getText())) {
            urlField.setText(d.getUrlTemplate());
        }
        if (classField.getText().isBlank() || isKnownClass(classField.getText())) {
            classField.setText(d.getDriverClass());
        }
    }

    private boolean isKnownTemplate(String value) {
        return drivers.getDrivers().stream().anyMatch(d -> d.getUrlTemplate().equals(value));
    }

    private boolean isKnownClass(String value) {
        return drivers.getDrivers().stream().anyMatch(d -> d.getDriverClass().equals(value));
    }

    private void loadProfile(ConnectionProfile p) {
        nameField.setText(p.getName());
        drivers.findById(p.getDriverId()).ifPresent(d -> driverCombo.setSelectedItem(d));
        urlField.setText(p.getJdbcUrl());
        classField.setText(p.getDriverClass());
        userField.setText(p.getUser());
        passwordField.setText(p.getPassword());
        savePassword.setSelected(p.isSavePassword());
        autoCommit.setSelected(p.isAutoCommit());
        rowLimitField.setText(String.valueOf(p.getRowLimit()));
    }

    private ConnectionProfile buildProfile() {
        ConnectionProfile p = (editing != null) ? editing.copy() : new ConnectionProfile();
        p.setName(nameField.getText().trim());
        DbDriver d = (DbDriver) driverCombo.getSelectedItem();
        if (d != null) {
            p.setDriverId(d.getId());
        }
        p.setJdbcUrl(urlField.getText().trim());
        p.setDriverClass(classField.getText().trim());
        p.setUser(userField.getText());
        char[] pw = passwordField.getPassword();
        p.setPassword(new String(pw));
        p.setSavePassword(savePassword.isSelected() && settings.isAllowSavePasswords());
        p.setAutoCommit(autoCommit.isSelected());
        p.setRowLimit(parseRowLimit());
        return p;
    }

    private int parseRowLimit() {
        try {
            int v = Integer.parseInt(rowLimitField.getText().trim());
            return Math.max(0, v);
        } catch (NumberFormatException e) {
            return ConnectionProfile.DEFAULT_ROW_LIMIT;
        }
    }

    private void doTest() {
        ConnectionProfile p = buildProfile();
        statusLabel.setText("Testing\u2026");
        ConnectionProvider.TestResult tr = new ConnectionProvider(drivers).test(p, settings);
        statusLabel.setText(tr.message());
        if (!tr.success()) {
            JOptionPane.showMessageDialog(this, tr.message(), "Connection failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void doOk() {
        ConnectionProfile p = buildProfile();
        if (p.getName().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please give the connection a name.",
                    "Missing name", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (p.getJdbcUrl().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a JDBC URL.",
                    "Missing URL", JOptionPane.WARNING_MESSAGE);
            return;
        }
        result = p;
        dispose();
    }

    /**
     * Shows the dialog modally.
     *
     * @return the saved profile, or {@code null} when cancelled
     */
    public ConnectionProfile showDialog() {
        setVisible(true);
        return result;
    }
}
