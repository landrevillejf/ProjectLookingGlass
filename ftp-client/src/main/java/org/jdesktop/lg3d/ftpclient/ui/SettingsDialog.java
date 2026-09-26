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
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.jdesktop.lg3d.ftpclient.model.AppSettings;

/**
 * A modal form for the application-wide {@link AppSettings}: timeouts, retry
 * policy, buffer size, and the security-sensitive "allow saving passwords"
 * switch (with an explicit warning that stored passwords are obfuscated, not
 * encrypted).
 *
 * <p>Created on demand so the headless unit tests never instantiate a
 * {@code JDialog}.</p>
 */
public final class SettingsDialog extends JDialog {

    private final JTextField connectTimeout = new JTextField(8);
    private final JTextField dataTimeout = new JTextField(8);
    private final JTextField retryCount = new JTextField(8);
    private final JTextField retryBackoff = new JTextField(8);
    private final JTextField bufferSize = new JTextField(8);
    private final JCheckBox passiveDefault = new JCheckBox("Default new FTP sites to passive mode");
    private final JCheckBox confirmOverwrite = new JCheckBox("Confirm before overwriting an existing file");
    private final JCheckBox showHiddenFiles = new JCheckBox("Show hidden (dot) files");
    private final JCheckBox allowSavePasswords =
            new JCheckBox("Allow saving passwords (obfuscated, not encrypted)");

    private AppSettings result;

    /**
     * Builds the dialog pre-filled from the given settings.
     *
     * @param owner    the owning frame (may be {@code null})
     * @param settings the settings to edit (copied; the original is untouched on cancel)
     */
    public SettingsDialog(Frame owner, AppSettings settings) {
        super(owner, "FTP Client Settings", true);
        AppSettings s = (settings != null) ? settings : new AppSettings();
        connectTimeout.setText(String.valueOf(s.getConnectTimeoutSeconds()));
        dataTimeout.setText(String.valueOf(s.getDataTimeoutSeconds()));
        retryCount.setText(String.valueOf(s.getRetryCount()));
        retryBackoff.setText(String.valueOf(s.getRetryBackoffMillis()));
        bufferSize.setText(String.valueOf(s.getBufferSize()));
        passiveDefault.setSelected(s.isPassiveDefault());
        confirmOverwrite.setSelected(s.isConfirmOverwrite());
        showHiddenFiles.setSelected(s.isShowHiddenFiles());
        allowSavePasswords.setSelected(s.isAllowSavePasswords());

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));
        content.add(buildForm(), BorderLayout.CENTER);
        content.add(buildButtons(), BorderLayout.SOUTH);
        setContentPane(content);
        pack();
        setLocationRelativeTo(owner);
    }

    private JPanel buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        addRow(form, c, row++, "Connect timeout (s)", connectTimeout);
        addRow(form, c, row++, "Data timeout (s, 0 = none)", dataTimeout);
        addRow(form, c, row++, "Retries per transfer", retryCount);
        addRow(form, c, row++, "Retry backoff (ms)", retryBackoff);
        addRow(form, c, row++, "Buffer size (bytes)", bufferSize);

        c.gridx = 0;
        c.gridwidth = 2;
        c.gridy = row++;
        form.add(passiveDefault, c);
        c.gridy = row++;
        form.add(confirmOverwrite, c);
        c.gridy = row++;
        form.add(showHiddenFiles, c);
        c.gridy = row;
        form.add(allowSavePasswords, c);
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

    private void doOk() {
        AppSettings s = new AppSettings();
        try {
            s.setConnectTimeoutSeconds(parseInt(connectTimeout.getText(), 15));
            s.setDataTimeoutSeconds(parseInt(dataTimeout.getText(), 30));
            s.setRetryCount(parseInt(retryCount.getText(), 3));
            s.setRetryBackoffMillis(parseLong(retryBackoff.getText(), 500L));
            s.setBufferSize(parseInt(bufferSize.getText(), 64 * 1024));
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Numeric fields must contain whole numbers.",
                    "Invalid value", JOptionPane.WARNING_MESSAGE);
            return;
        }
        s.setPassiveDefault(passiveDefault.isSelected());
        s.setConfirmOverwrite(confirmOverwrite.isSelected());
        s.setShowHiddenFiles(showHiddenFiles.isSelected());
        s.setAllowSavePasswords(allowSavePasswords.isSelected());
        if (allowSavePasswords.isSelected()) {
            JOptionPane.showMessageDialog(this,
                    "Saved passwords are obfuscated, NOT encrypted.\n"
                    + "Anyone with access to your home folder can recover them.",
                    "Security notice", JOptionPane.WARNING_MESSAGE);
        }
        result = s;
        dispose();
    }

    private static int parseInt(String text, int fallback) {
        String t = text.trim();
        return t.isEmpty() ? fallback : Integer.parseInt(t);
    }

    private static long parseLong(String text, long fallback) {
        String t = text.trim();
        return t.isEmpty() ? fallback : Long.parseLong(t);
    }

    /**
     * Shows the dialog modally.
     *
     * @return the edited settings, or {@code null} when cancelled
     */
    public AppSettings showDialog() {
        setVisible(true);
        return result;
    }
}
