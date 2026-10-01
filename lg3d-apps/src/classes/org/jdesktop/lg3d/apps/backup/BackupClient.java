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
package org.jdesktop.lg3d.apps.backup;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Standalone JFrame wrapper for the backup panel, for running the tool outside
 * the desktop (e.g. {@code java org.jdesktop.lg3d.apps.backup.BackupClient}).
 *
 * <p>Uses {@code DISPOSE_ON_CLOSE} (not {@code EXIT_ON_CLOSE}) so that if it is
 * ever launched inside the shared lg3d desktop JVM, closing the window disposes
 * only this frame and never terminates the desktop.</p>
 */
public class BackupClient extends JFrame {

    private static final long serialVersionUID = 1L;

    private final BackupPanel panel;

    public BackupClient() {
        super("Backup & Restore");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(BackupPanel.WIDTH_PX + 20, BackupPanel.HEIGHT_PX + 40);
        setLocationRelativeTo(null);

        panel = new BackupPanel();
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
            BackupClient client = new BackupClient();
            client.setVisible(true);
        });
    }
}
