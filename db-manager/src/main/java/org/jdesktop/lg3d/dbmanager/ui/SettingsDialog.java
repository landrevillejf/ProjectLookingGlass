/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
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
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.jdesktop.lg3d.dbmanager.model.AppSettings;

/**
 * A modal form for the application-wide {@link AppSettings}: row caps, paging,
 * timeouts, null text, editor font size, and the security-sensitive
 * "allow saving passwords" switch (with an explicit warning).
 *
 * <p>Created on demand so the headless unit tests never instantiate a
 * {@code JDialog}.</p>
 */
public final class SettingsDialog extends JDialog {

    private final JTextField maxRows = new JTextField(8);
    private final JTextField pageSize = new JTextField(8);
    private final JTextField fetchSize = new JTextField(8);
    private final JTextField nullText = new JTextField(12);
    private final JTextField queryTimeout = new JTextField(8);
    private final JTextField connectTimeout = new JTextField(8);
    private final JTextField editorFontSize = new JTextField(8);
    private final JTextField historyLimit = new JTextField(8);
    private final JCheckBox autoCommitDefault = new JCheckBox("Default new connections to auto-commit");
    private final JCheckBox confirmOnWrite = new JCheckBox("Confirm before committing data changes");
    private final JCheckBox allowSavePasswords = new JCheckBox("Allow saving passwords (obfuscated, not encrypted)");

    private AppSettings result;

    /**
     * Builds the dialog pre-filled from the given settings.
     *
     * @param owner    the owning frame (may be {@code null})
     * @param settings the settings to edit (copied; the original is untouched on cancel)
     */
    public SettingsDialog(Frame owner, AppSettings settings) {
        super(owner, "Database Manager Settings", true);
        AppSettings s = (settings != null) ? settings : new AppSettings();
        maxRows.setText(String.valueOf(s.getMaxRows()));
        pageSize.setText(String.valueOf(s.getPageSize()));
        fetchSize.setText(String.valueOf(s.getFetchSize()));
        nullText.setText(s.getNullText());
        queryTimeout.setText(String.valueOf(s.getQueryTimeoutSeconds()));
        connectTimeout.setText(String.valueOf(s.getConnectTimeoutSeconds()));
        editorFontSize.setText(String.valueOf(s.getEditorFontSize()));
        historyLimit.setText(String.valueOf(s.getHistoryLimit()));
        autoCommitDefault.setSelected(s.isAutoCommitDefault());
        confirmOnWrite.setSelected(s.isConfirmOnWrite());
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
        addRow(form, c, row++, "Max rows per query", maxRows);
        addRow(form, c, row++, "Grid page size", pageSize);
        addRow(form, c, row++, "Fetch size", fetchSize);
        addRow(form, c, row++, "NULL display text", nullText);
        addRow(form, c, row++, "Query timeout (s, 0 = none)", queryTimeout);
        addRow(form, c, row++, "Connect timeout (s)", connectTimeout);
        addRow(form, c, row++, "Editor font size", editorFontSize);
        addRow(form, c, row++, "SQL history size", historyLimit);

        c.gridx = 0;
        c.gridwidth = 2;
        c.gridy = row++;
        form.add(autoCommitDefault, c);
        c.gridy = row++;
        form.add(confirmOnWrite, c);
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
            s.setMaxRows(parseInt(maxRows.getText(), 1000));
            s.setPageSize(parseInt(pageSize.getText(), 200));
            s.setFetchSize(parseInt(fetchSize.getText(), 200));
            s.setNullText(nullText.getText());
            s.setQueryTimeoutSeconds(parseInt(queryTimeout.getText(), 60));
            s.setConnectTimeoutSeconds(parseInt(connectTimeout.getText(), 15));
            s.setEditorFontSize(parseInt(editorFontSize.getText(), 13));
            s.setHistoryLimit(parseInt(historyLimit.getText(), 50));
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Numeric fields must contain whole numbers.",
                    "Invalid value", JOptionPane.WARNING_MESSAGE);
            return;
        }
        s.setAutoCommitDefault(autoCommitDefault.isSelected());
        s.setConfirmOnWrite(confirmOnWrite.isSelected());
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
        if (t.isEmpty()) {
            return fallback;
        }
        return Integer.parseInt(t);
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
