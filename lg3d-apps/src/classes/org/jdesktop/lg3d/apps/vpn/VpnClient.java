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
package org.jdesktop.lg3d.apps.vpn;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Standalone JFrame wrapper for the VPN panel, for running it outside the desktop
 * ({@code java org.jdesktop.lg3d.apps.vpn.VpnClient}).
 *
 * <p>Uses {@code DISPOSE_ON_CLOSE} (not {@code EXIT_ON_CLOSE}) so that if it is
 * ever launched inside the shared lg3d desktop JVM, closing the window disposes
 * only this frame and never terminates the desktop. Any tracked standalone tunnel
 * process is stopped when the window closes, so a tunnel never outlives the
 * client.</p>
 */
public class VpnClient extends JFrame {

    private static final long serialVersionUID = 1L;

    private final VpnPanel panel;

    public VpnClient() {
        super("VPN");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(VpnPanel.WIDTH_PX + 20, VpnPanel.HEIGHT_PX + 40);
        setLocationRelativeTo(null);

        panel = new VpnPanel();
        panel.setOnClose(this::dispose);
        setContentPane(panel);

        // The panel's Close button stops the tunnel itself; this covers the case
        // where the window is dismissed through its own close affordance.
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                panel.stopTunnel();
            }
        });
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Fall back to Metal.
        }
        SwingUtilities.invokeLater(() -> {
            VpnClient client = new VpnClient();
            client.setVisible(true);
        });
    }
}
