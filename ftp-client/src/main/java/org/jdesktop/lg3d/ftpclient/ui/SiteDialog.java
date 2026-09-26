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
package org.jdesktop.lg3d.ftpclient.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
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
import org.jdesktop.lg3d.ftpclient.model.AppSettings;
import org.jdesktop.lg3d.ftpclient.model.Protocol;
import org.jdesktop.lg3d.ftpclient.model.SiteProfile;
import org.jdesktop.lg3d.ftpclient.model.TransferMode;

/**
 * A modal form for creating or editing a {@link SiteProfile}: pick the protocol
 * (FTP / FTPS / SFTP), host, port, credentials and options.
 *
 * <p>Security is surfaced in the form itself: a plaintext FTP site shows a
 * "not encrypted" warning, and the "save password" box is disabled unless the
 * application-wide {@link AppSettings#isAllowSavePasswords()} switch is on, with
 * a reminder that a saved password is obfuscated rather than encrypted.</p>
 *
 * <p>Created on demand (never during panel construction), so the headless unit
 * tests never instantiate a {@code JDialog}.</p>
 */
public final class SiteDialog extends JDialog {

    private final AppSettings settings;
    private final JTextField nameField = new JTextField(24);
    private final JComboBox<Protocol> protocolCombo = new JComboBox<>(Protocol.values());
    private final JTextField hostField = new JTextField(24);
    private final JTextField portField = new JTextField(6);
    private final JTextField userField = new JTextField(16);
    private final JPasswordField passwordField = new JPasswordField(16);
    private final JCheckBox savePassword = new JCheckBox("Save password");
    private final JTextField remoteDirField = new JTextField(20);
    private final JComboBox<TransferMode> modeCombo = new JComboBox<>(TransferMode.values());
    private final JTextField encodingField = new JTextField(10);
    private final JLabel securityNote = new JLabel(" ");

    private final SiteProfile editing;
    private SiteProfile result;

    /**
     * Builds the dialog.
     *
     * @param owner    the owning frame (may be {@code null})
     * @param settings the app settings (gates password saving)
     * @param existing the profile to edit, or {@code null} to create a new one
     */
    public SiteDialog(Frame owner, AppSettings settings, SiteProfile existing) {
        super(owner, (existing == null) ? "New Site" : "Edit Site", true);
        this.settings = (settings != null) ? settings : new AppSettings();
        this.editing = existing;

        savePassword.setEnabled(this.settings.isAllowSavePasswords());
        if (!this.settings.isAllowSavePasswords()) {
            savePassword.setToolTipText("Enable \"Allow saving passwords\" in Settings first");
        }
        protocolCombo.addActionListener(e -> updateForProtocol());

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));
        content.add(buildForm(), BorderLayout.CENTER);
        content.add(buildButtons(), BorderLayout.SOUTH);
        setContentPane(content);

        if (existing != null) {
            load(existing);
        } else {
            protocolCombo.setSelectedItem(Protocol.FTPS);
            updateForProtocol();
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
        addRow(form, c, row++, "Protocol", protocolCombo);
        addRow(form, c, row++, "Host", hostField);
        addRow(form, c, row++, "Port (blank = default)", portField);
        addRow(form, c, row++, "User", userField);
        addRow(form, c, row++, "Password", passwordField);

        c.gridwidth = 2;
        c.gridx = 0;
        c.gridy = row++;
        form.add(savePassword, c);

        c.gridwidth = 2;
        c.gridy = row++;
        securityNote.setForeground(new Color(0xB0, 0x00, 0x00));
        form.add(securityNote, c);

        c.gridwidth = 1;
        addRow(form, c, row++, "Initial directory", remoteDirField);
        addRow(form, c, row++, "FTP transfer mode", modeCombo);
        addRow(form, c, row, "Encoding", encodingField);
        return form;
    }

    private void addRow(JPanel form, GridBagConstraints c, int row, String label,
                        java.awt.Component field) {
        c.gridwidth = 1;
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
        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> doOk());
        cancel.addActionListener(e -> {
            result = null;
            dispose();
        });
        bar.add(ok);
        bar.add(cancel);
        return bar;
    }

    private void updateForProtocol() {
        Protocol p = (Protocol) protocolCombo.getSelectedItem();
        if (p == null) {
            return;
        }
        if (portField.getText().isBlank()) {
            portField.setToolTipText("Default: " + p.getDefaultPort());
        }
        // Transfer mode only matters for FTP/FTPS; SFTP has no passive/active.
        modeCombo.setEnabled(p != Protocol.SFTP);
        if (p.isSecure()) {
            securityNote.setText("This protocol encrypts the session.");
        } else {
            securityNote.setText("Warning: plain FTP sends credentials and data unencrypted.");
        }
    }

    private void load(SiteProfile p) {
        nameField.setText(p.getName());
        protocolCombo.setSelectedItem(p.getProtocol());
        hostField.setText(p.getHost());
        portField.setText(p.getPort() > 0 ? String.valueOf(p.getPort()) : "");
        userField.setText(p.getUser());
        passwordField.setText(p.getPassword());
        savePassword.setSelected(p.isSavePassword());
        remoteDirField.setText(p.getRemoteDir());
        modeCombo.setSelectedItem(p.getTransferMode());
        encodingField.setText(p.getEncoding());
        updateForProtocol();
    }

    private SiteProfile buildProfile() {
        SiteProfile p = (editing != null) ? editing.copy() : new SiteProfile();
        p.setName(nameField.getText().trim());
        p.setProtocol((Protocol) protocolCombo.getSelectedItem());
        p.setHost(hostField.getText().trim());
        p.setPort(parsePort());
        p.setUser(userField.getText());
        p.setPassword(new String(passwordField.getPassword()));
        p.setSavePassword(savePassword.isSelected() && settings.isAllowSavePasswords());
        p.setRemoteDir(remoteDirField.getText().trim());
        p.setTransferMode((TransferMode) modeCombo.getSelectedItem());
        p.setEncoding(encodingField.getText().trim());
        return p;
    }

    private int parsePort() {
        try {
            int v = Integer.parseInt(portField.getText().trim());
            return (v > 0 && v <= 65535) ? v : 0;
        } catch (NumberFormatException e) {
            return 0; // blank or invalid -> protocol default
        }
    }

    private void doOk() {
        SiteProfile p = buildProfile();
        if (p.getName().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please give the site a name.",
                    "Missing name", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!p.isConnectable()) {
            JOptionPane.showMessageDialog(this, "Please enter a host.",
                    "Missing host", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (p.isSavePassword()) {
            JOptionPane.showMessageDialog(this,
                    "Saved passwords are obfuscated, NOT encrypted.\n"
                    + "Anyone with access to your home folder can recover them.",
                    "Security notice", JOptionPane.WARNING_MESSAGE);
        }
        result = p;
        dispose();
    }

    /**
     * Shows the dialog modally.
     *
     * @return the saved profile, or {@code null} when cancelled
     */
    public SiteProfile showDialog() {
        setVisible(true);
        return result;
    }
}
