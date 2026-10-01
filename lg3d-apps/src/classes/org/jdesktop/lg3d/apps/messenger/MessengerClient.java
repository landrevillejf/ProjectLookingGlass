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
package org.jdesktop.lg3d.apps.messenger;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Standalone JFrame wrapper for the messenger panel, for running the client
 * outside the desktop (e.g. {@code java
 * org.jdesktop.lg3d.apps.messenger.MessengerClient}).
 *
 * <p>Uses {@code DISPOSE_ON_CLOSE} (not {@code EXIT_ON_CLOSE}) so that if it is
 * ever launched inside the shared lg3d desktop JVM, closing the window disposes
 * only this frame and never terminates the desktop. The window-close listener
 * calls {@link MessengerPanel#shutdown()} so every backend socket is closed and
 * the transcript is flushed.</p>
 */
public class MessengerClient extends JFrame {

    private static final long serialVersionUID = 1L;

    private final MessengerPanel panel;

    public MessengerClient() {
        super("Instant Messenger");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(MessengerPanel.WIDTH_PX + 20, MessengerPanel.HEIGHT_PX + 40);
        setLocationRelativeTo(null);

        panel = new MessengerPanel();
        setContentPane(panel);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                panel.shutdown();
            }
        });
    }

    /** The hosted panel (for tests and embedders). */
    public MessengerPanel getPanel() {
        return panel;
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Fall back to Metal.
        }
        SwingUtilities.invokeLater(() -> {
            MessengerClient client = new MessengerClient();
            client.setVisible(true);
            client.panel.autoConnect();
        });
    }
}
