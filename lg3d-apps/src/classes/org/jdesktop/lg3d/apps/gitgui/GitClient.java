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
package org.jdesktop.lg3d.apps.gitgui;

import java.awt.BorderLayout;
import java.io.File;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * A standalone launcher for the Git GUI outside the lg3d desktop: it shows the
 * very same {@link GitGuiPanel} in an ordinary {@link JFrame}.
 *
 * <p> Handy for development and for running the client on a plain desktop. The
 * frame uses {@link WindowConstants#DISPOSE_ON_CLOSE} - never
 * {@code EXIT_ON_CLOSE} - so that when this class happens to be launched inside
 * the desktop JVM (rather than as its own process) closing the window cannot
 * tear down the whole desktop.</p>
 *
 * <p>An optional first argument is opened as the initial repository folder.</p>
 */
public final class GitClient {

    private GitClient() {
        // no instances
    }

    public static void main(String[] args) {
        final File initial = (args.length > 0 && !args[0].isBlank())
                ? new File(args[0]) : null;
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Git GUI");
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            GitGuiPanel panel = new GitGuiPanel();
            frame.getContentPane().add(panel, BorderLayout.CENTER);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            if (initial != null) {
                panel.openRepository(initial);
            }
        });
    }
}
