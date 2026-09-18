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
package org.jdesktop.lg3d.apps.filemanager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.jdesktop.lg3d.apps.TitledSwingWindow;
import org.jdesktop.lg3d.wg.Frame3D;

/**
 * The file manager application: the {@link FileManagerPanel} Swing UI presented
 * as an integrated 3D desktop window (title bar plus minimize / maximize /
 * close) via {@link TitledSwingWindow}.
 *
 * <p>{@code main} accepts an optional initial directory argument (used by the
 * Documents/Downloads dock stacks' "Open folder" action). When launched from
 * the start menu there is no argument, so it opens at the user's home
 * directory. The argument may arrive with a leading space (the in-JVM
 * {@code java <class> <args>} launcher passes the remainder of the command
 * line), so it is trimmed before use.</p>
 */
public class FileManager {

    private static final int PANEL_W = 760;
    private static final int PANEL_H = 500;

    public static void main(String[] args) {
        new FileManager(parseInitialDir(args));
    }

    /** Resolves the starting directory from the command-line args. */
    private static Path parseInitialDir(String[] args) {
        if (args != null) {
            for (String a : args) {
                if (a == null) {
                    continue;
                }
                String t = a.trim();
                if (t.isEmpty()) {
                    continue;
                }
                Path p = Paths.get(t);
                if (Files.isDirectory(p)) {
                    return p;
                }
                // A file argument opens in its containing directory.
                Path parent = p.getParent();
                if (parent != null && Files.isDirectory(parent)) {
                    return parent;
                }
                break;
            }
        }
        return Paths.get(System.getProperty("user.home"));
    }

    public FileManager(Path initial) {
        TitledSwingWindow.installNativeLookAndFeel();
        final FileManagerPanel panel = new FileManagerPanel(initial);
        final Frame3D frame =
                TitledSwingWindow.show("File Manager", panel, PANEL_W, PANEL_H);
        panel.setOnClose(() -> frame.changeEnabled(false));
    }
}
