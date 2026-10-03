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
package org.jdesktop.lg3d.apps.mail;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

/**
 * Edits (or creates) one {@link MailAccount}: identity, the IMAP and SMTP
 * endpoints with their transport security, the credential mode, an optional
 * password, the outgoing signature, and the default-account flag. A
 * <em>Test connection</em> button probes the server through
 * {@link MailSessionManager#testConnection} off the EDT and reports the folder
 * count or the failure reason.
 *
 * <p>Per the project UI convention (SwingNode offscreen rendering on the 3D
 * desktop), every enumerated choice is a {@link JRadioButton} group rather than a
 * combo box. The dialog edits a private copy of the account, so <em>Cancel</em>
 * leaves the caller's object untouched; <em>Save</em> returns {@code true} from
 * {@link #isSaved()} and the caller persists it (and, for a SAVED account with a
 * freshly typed password, seals it through the {@link MailAccountStore}).</p>
 */
final class MailAccountDialog extends JDialog {

    private final JTextField nameField = new JTextField(20);
    private final JTextField emailField = new JTextField(20);
    private final JTextField userField = new JTextField(20);

    private final JTextField imapHost = new JTextField(20);
    private final JTextField imapPort = new JTextField(5);
    private final Map<MailAccount.Security, JRadioButton> imapSec =
            new EnumMap<MailAccount.Security, JRadioButton>(MailAccount.Security.class);

    private final JTextField smtpHost = new JTextField(20);
    private final JTextField smtpPort = new JTextField(5);
    private final Map<MailAccount.Security, JRadioButton> smtpSec =
            new EnumMap<MailAccount.Security, JRadioButton>(MailAccount.Security.class);

    private final JRadioButton credAsk = new JRadioButton("Ask each session (kept in memory only)");
    private final JRadioButton credSaved = new JRadioButton("Save obfuscated on this machine");
    private final JPasswordField passwordField = new JPasswordField(20);

    private final JTextArea signature = new JTextArea(4, 20);
    private final JCheckBox defaultBox = new JCheckBox("Use as the default account");

    private final MailAccount working;
    private final MailSessionManager manager;
    private boolean saved;

    MailAccountDialog(Frame owner, MailAccount account,
            MailSessionManager manager) {
        super(owner, account == null ? "New mail account" : "Edit mail account",
                true);
        this.manager = manager;
        this.working = copy(account);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        int row = 0;

        row = addLabeled(form, c, row, "Display name:", nameField);
        row = addLabeled(form, c, row, "E-mail address:", emailField);
        row = addLabeled(form, c, row, "Username:", userField);

        row = addSection(form, c, row, "Incoming (IMAP)");
        row = addLabeled(form, c, row, "IMAP host:", imapHost);
        row = addLabeled(form, c, row, "IMAP port:", imapPort);
        row = addRadioRow(form, c, row, "IMAP security:", imapSec);

        row = addSection(form, c, row, "Outgoing (SMTP)");
        row = addLabeled(form, c, row, "SMTP host:", smtpHost);
        row = addLabeled(form, c, row, "SMTP port:", smtpPort);
        row = addRadioRow(form, c, row, "SMTP security:", smtpSec);

        row = addSection(form, c, row, "Credentials");
        ButtonGroup credGroup = new ButtonGroup();
        credGroup.add(credAsk);
        credGroup.add(credSaved);
        JPanel credPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        credPanel.add(credAsk);
        credPanel.add(credSaved);
        c.gridx = 0;
        c.gridwidth = 2;
        c.gridy = row++;
        form.add(credPanel, c);
        c.gridwidth = 1;
        row = addLabeled(form, c, row, "Password:", passwordField);

        row = addSection(form, c, row, "Signature & defaults");
        c.gridx = 0;
        c.gridwidth = 2;
        c.gridy = row++;
        form.add(new JScrollPane(signature), c);
        c.gridwidth = 1;
        c.gridx = 0;
        c.gridwidth = 2;
        c.gridy = row++;
        form.add(defaultBox, c);
        c.gridwidth = 1;

        getContentPane().add(new JScrollPane(form), BorderLayout.CENTER);
        getContentPane().add(buildButtons(), BorderLayout.SOUTH);

        loadFrom(working);
        pack();
        setLocationRelativeTo(owner);
    }

    private JPanel buildButtons() {
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
        JButton test = new JButton("Test connection");
        test.addActionListener(e -> testConnection());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JButton save = new JButton("Save");
        save.addActionListener(e -> {
            saved = applyToWorking();
            dispose();
        });
        south.add(test);
        south.add(cancel);
        south.add(save);
        getRootPane().setDefaultButton(save);
        return south;
    }

    // ------------------------------------------------------------------
    // Form construction helpers
    // ------------------------------------------------------------------

    private static int addLabeled(JPanel p, GridBagConstraints c, int row,
            String label, Component field) {
        c.gridx = 0;
        c.gridy = row;
        p.add(new JLabel(label), c);
        c.gridx = 1;
        p.add(field, c);
        return row + 1;
    }

    private static int addSection(JPanel p, GridBagConstraints c, int row,
            String title) {
        JLabel l = new JLabel(title);
        l.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
        l.setFont(l.getFont().deriveFont(java.awt.Font.BOLD));
        c.gridx = 0;
        c.gridwidth = 2;
        c.gridy = row++;
        p.add(l, c);
        c.gridwidth = 1;
        return row;
    }

    private int addRadioRow(JPanel p, GridBagConstraints c, int row,
            String label, Map<MailAccount.Security, JRadioButton> map) {
        ButtonGroup group = new ButtonGroup();
        JPanel radios = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        for (MailAccount.Security s : MailAccount.Security.values()) {
            JRadioButton rb = new JRadioButton(securityLabel(s));
            group.add(rb);
            map.put(s, rb);
            radios.add(rb);
        }
        c.gridx = 0;
        c.gridy = row;
        p.add(new JLabel(label), c);
        c.gridx = 1;
        p.add(radios, c);
        return row + 1;
    }

    private static String securityLabel(MailAccount.Security s) {
        switch (s) {
            case SSL: return "SSL/TLS";
            case STARTTLS: return "STARTTLS";
            case NONE: return "None";
            default: return s.name();
        }
    }

    /** Deep-copies an account (or builds a blank one) so Cancel is side-effect free. */
    private static MailAccount copy(MailAccount src) {
        MailAccount a = new MailAccount(src == null ? null : src.getId());
        if (src != null) {
            a.setDisplayName(src.getDisplayName());
            a.setEmailAddress(src.getEmailAddress());
            a.setUsername(src.getUsername());
            a.setImapHost(src.getImapHost());
            a.setImapPort(src.getImapPort());
            a.setImapSecurity(src.getImapSecurity());
            a.setSmtpHost(src.getSmtpHost());
            a.setSmtpPort(src.getSmtpPort());
            a.setSmtpSecurity(src.getSmtpSecurity());
            a.setCredentialMode(src.getCredentialMode());
            a.setSignature(src.getSignature());
            a.setDefaultAccount(src.isDefaultAccount());
        }
        return a;
    }

    // ------------------------------------------------------------------
    // Model <-> widgets
    // ------------------------------------------------------------------

    private void loadFrom(MailAccount a) {
        nameField.setText(a.getDisplayName());
        emailField.setText(a.getEmailAddress());
        userField.setText(a.getUsername());
        imapHost.setText(a.getImapHost());
        imapPort.setText(Integer.toString(a.getImapPort()));
        smtpHost.setText(a.getSmtpHost());
        smtpPort.setText(Integer.toString(a.getSmtpPort()));
        imapSec.get(a.getImapSecurity()).setSelected(true);
        smtpSec.get(a.getSmtpSecurity()).setSelected(true);
        (a.getCredentialMode() == MailAccount.CredentialMode.SAVED
                ? credSaved : credAsk).setSelected(true);
        signature.setText(a.getSignature());
        defaultBox.setSelected(a.isDefaultAccount());
    }

    /** Copies the widgets back into {@link #working}; false on invalid input. */
    private boolean applyToWorking() {
        working.setDisplayName(nameField.getText());
        working.setEmailAddress(emailField.getText());
        working.setUsername(userField.getText());
        working.setImapHost(imapHost.getText());
        working.setSmtpHost(smtpHost.getText());
        working.setImapPort(parsePort(imapPort.getText(), 993));
        working.setSmtpPort(parsePort(smtpPort.getText(), 465));
        working.setImapSecurity(selected(imapSec));
        working.setSmtpSecurity(selected(smtpSec));
        working.setCredentialMode(credSaved.isSelected()
                ? MailAccount.CredentialMode.SAVED
                : MailAccount.CredentialMode.ASK);
        working.setSignature(signature.getText());
        working.setDefaultAccount(defaultBox.isSelected());
        if (!working.isComplete()) {
            JOptionPane.showMessageDialog(this,
                    "An e-mail address, an IMAP host and an SMTP host are required.",
                    "Incomplete account", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        return true;
    }

    private static int parsePort(String text, int fallback) {
        try {
            int p = Integer.parseInt(text.trim());
            return (p > 0 && p <= 65535) ? p : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static MailAccount.Security selected(
            Map<MailAccount.Security, JRadioButton> map) {
        for (Map.Entry<MailAccount.Security, JRadioButton> e : map.entrySet()) {
            if (e.getValue().isSelected()) {
                return e.getKey();
            }
        }
        return MailAccount.Security.SSL;
    }

    /** The typed password, or {@code null} when the field is blank. */
    String typedPassword() {
        char[] pw = passwordField.getPassword();
        return pw.length == 0 ? null : new String(pw);
    }

    boolean isSaved() {
        return saved;
    }

    MailAccount account() {
        return working;
    }

    // ------------------------------------------------------------------
    // Test connection
    // ------------------------------------------------------------------

    private void testConnection() {
        applyToWorking();
        final String password = typedPassword();
        final MailAccount probe = working;
        setCursor(java.awt.Cursor.getPredefinedCursor(
                java.awt.Cursor.WAIT_CURSOR));
        new SwingWorker<Integer, Void>() {
            private String error;

            @Override
            protected Integer doInBackground() {
                try {
                    return manager.testConnection(probe,
                            password == null ? "" : password);
                } catch (MailBackendException e) {
                    error = e.getMessage();
                    return -1;
                }
            }

            @Override
            protected void done() {
                setCursor(java.awt.Cursor.getDefaultCursor());
                try {
                    int folders = get();
                    if (folders >= 0) {
                        JOptionPane.showMessageDialog(MailAccountDialog.this,
                                "Connected. Found " + folders + " folder(s).",
                                "Test connection", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(MailAccountDialog.this,
                                error, "Test connection failed",
                                JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(MailAccountDialog.this,
                            "Test failed: " + e.getMessage(),
                            "Test connection failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /** Opens the dialog for {@code account} and returns the saved account or null. */
    static MailAccount show(Frame owner, MailAccount account,
            MailSessionManager manager) {
        MailAccountDialog d = new MailAccountDialog(owner, account, manager);
        d.setVisible(true);
        return d.isSaved() ? d.account() : null;
    }
}
