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
package org.jdesktop.lg3d.apps.ssh;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Standalone JFrame wrapper for the SSH client panel.
 *
 * <p>Uses {@code DISPOSE_ON_CLOSE} (not {@code EXIT_ON_CLOSE}) so that when
 * this class is launched inside the shared lg3d desktop JVM (via the
 * {@code Desktop2DAppRegistry} SWING_FRAME path or the start-menu descriptor),
 * closing the window does not terminate the entire desktop.</p>
 */
public class SshSwingClient extends JFrame {

    private static final long serialVersionUID = 1L;

    private final SshPanel sshPanel;

    public SshSwingClient() {
        super("SSH Client");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(960, 640);
        setLocationRelativeTo(null);

        sshPanel = new SshPanel();
        setContentPane(sshPanel);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                sshPanel.shutdown();
            }
        });
    }

    public static void main(String[] args) {
        // Use the system look and feel for native appearance
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Fall back to Metal
        }

        SwingUtilities.invokeLater(() -> {
            SshSwingClient client = new SshSwingClient();
            client.setVisible(true);
        });
    }
}
