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
import java.awt.GridLayout;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

/**
 * A modal password prompt for accounts in {@link MailAccount.CredentialMode#ASK}
 * mode (and for SAVED accounts that have no sealed password yet).
 *
 * <p>The session manager resolves credentials from a background worker, so
 * {@link #prompt(Component, MailAccount, MailAccountStore)} marshals the dialog onto
 * the EDT with {@link SwingUtilities#invokeAndWait} and blocks the caller until the
 * user answers. The typed password is returned in memory only; the optional
 * "Remember" box flips the account to {@code SAVED} and seals the password through
 * the {@link MailAccountStore} (see {@link CredentialVault} for the honest limits of
 * that obfuscation).</p>
 */
final class PasswordPromptDialog extends JDialog {

    private final JPasswordField passwordField = new JPasswordField(20);
    private final JCheckBox remember = new JCheckBox("Remember this password");
    private boolean ok;

    private PasswordPromptDialog(Frame owner, MailAccount account) {
        super(owner, "Mail account password", true);
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel top = new JPanel(new GridLayout(2, 1, 0, 4));
        top.add(new JLabel("Enter the password for:"));
        top.add(new JLabel(account == null ? "" : account.getDisplayLabel()));
        content.add(top, BorderLayout.NORTH);
        content.add(passwordField, BorderLayout.CENTER);
        content.add(remember, BorderLayout.SOUTH);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton okBtn = new JButton("Connect");
        okBtn.addActionListener(e -> {
            ok = true;
            dispose();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        south.add(cancel);
        south.add(okBtn);

        getContentPane().add(content, BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(okBtn);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Prompts on the EDT and returns the password, or {@code null} if cancelled.
     * When "Remember" is ticked the password is sealed into the account store and
     * the account switched to SAVED mode.
     */
    static String prompt(Component parent, MailAccount account,
            MailAccountStore store) {
        final AtomicReference<String> result = new AtomicReference<String>();
        Runnable show = () -> {
            Frame owner = (parent instanceof Frame) ? (Frame) parent
                    : (Frame) SwingUtilities.getAncestorOfClass(Frame.class,
                            parent == null ? new JPanel() : parent);
            PasswordPromptDialog d = new PasswordPromptDialog(owner, account);
            d.setVisible(true);
            if (d.ok) {
                String pw = new String(d.passwordField.getPassword());
                result.set(pw);
                if (d.remember.isSelected() && account != null && store != null) {
                    account.setCredentialMode(MailAccount.CredentialMode.SAVED);
                    store.save(account);
                    store.setPassword(account.getId(), pw);
                }
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                show.run();
            } else {
                SwingUtilities.invokeAndWait(show);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (InvocationTargetException e) {
            return null;
        }
        return result.get();
    }
}
