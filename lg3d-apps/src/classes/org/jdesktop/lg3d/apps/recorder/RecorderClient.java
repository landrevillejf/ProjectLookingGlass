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
package org.jdesktop.lg3d.apps.recorder;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Standalone JFrame wrapper for the recorder panel, for running it outside the
 * desktop ({@code java org.jdesktop.lg3d.apps.recorder.RecorderClient}).
 *
 * <p>Uses {@code DISPOSE_ON_CLOSE} (not {@code EXIT_ON_CLOSE}) so that if it is
 * ever launched inside the shared lg3d desktop JVM, closing the window disposes
 * only this frame and never terminates the desktop.</p>
 */
public class RecorderClient extends JFrame {

    private static final long serialVersionUID = 1L;

    private final RecorderPanel panel;

    public RecorderClient() {
        super("Recorder");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(RecorderPanel.WIDTH_PX + 20, RecorderPanel.HEIGHT_PX + 40);
        setLocationRelativeTo(null);

        panel = new RecorderPanel();
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
            RecorderClient client = new RecorderClient();
            client.setVisible(true);
        });
    }
}
