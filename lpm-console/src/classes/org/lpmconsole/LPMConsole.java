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
package org.lpmconsole;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

/**
 * The LPM Console application window: a thin {@link JFrame} hosting the
 * {@link LPMConsolePanel} package-manager UI.
 *
 * <p>This is the entry point referenced by the lg3d start-menu descriptor
 * ({@code swingapp org.lpmconsole.LPMConsole}) and by the standalone launcher
 * ({@code lpm-console} / {@code java -jar}). Two hosting paths share the same
 * panel:</p>
 * <ul>
 *   <li><b>3D desktop</b> - the {@code swingapp} verb runs {@link #main} inside
 *       the desktop JVM; {@code SwingNodeWindowCapture} textures this frame into
 *       the scene, exactly like the Paint app.</li>
 *   <li><b>2D/Swing desktop</b> - {@code Desktop2DAppRegistry} builds
 *       {@link LPMConsolePanel} directly and hosts it in a {@code
 *       JInternalFrame}; this frame class is not used.</li>
 * </ul>
 *
 * <p>Because the frame can be created inside the shared desktop JVM, it uses
 * {@code DISPOSE_ON_CLOSE} and never calls {@code System.exit}: closing the
 * window must dispose only this frame, not kill the whole desktop. LPM being
 * absent is handled gracefully inside the panel (a banner plus disabled
 * actions), so startup never aborts the JVM either.</p>
 */
public class LPMConsole extends JFrame {

    private final LPMConsolePanel panel;

    public LPMConsole() {
        super("LPM Console");
        panel = new LPMConsolePanel();
        panel.setOnClose(() -> dispose());
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        getContentPane().add(panel);
        setSize(900, 660);
        setLocationRelativeTo(null);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(
                        UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // Fall back to the default look and feel.
            }
            new LPMConsole().setVisible(true);
        });
    }
}
