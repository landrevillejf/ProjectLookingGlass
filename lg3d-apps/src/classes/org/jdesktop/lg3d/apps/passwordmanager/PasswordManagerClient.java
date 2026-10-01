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
package org.jdesktop.lg3d.apps.passwordmanager;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Standalone JFrame wrapper for the Password Manager panel, for running it
 * outside the desktop
 * ({@code java org.jdesktop.lg3d.apps.passwordmanager.PasswordManagerClient}).
 *
 * <p>Uses {@code DISPOSE_ON_CLOSE} (not {@code EXIT_ON_CLOSE}) so that if it is
 * ever launched inside the shared lg3d desktop JVM, closing the window disposes
 * only this frame and never terminates the desktop. The vault is locked when the
 * window closes so no decrypted secret outlives it.</p>
 */
public class PasswordManagerClient extends JFrame {

    private static final long serialVersionUID = 1L;

    private final PasswordManagerPanel panel;

    public PasswordManagerClient() {
        super("Password Manager");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(PasswordManagerPanel.WIDTH_PX + 20, PasswordManagerPanel.HEIGHT_PX + 40);
        setLocationRelativeTo(null);

        panel = new PasswordManagerPanel();
        panel.setOnClose(this::dispose);
        setContentPane(panel);
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Fall back to Metal.
        }
        SwingUtilities.invokeLater(() -> {
            PasswordManagerClient client = new PasswordManagerClient();
            client.setVisible(true);
        });
    }
}
