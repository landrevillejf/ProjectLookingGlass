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
package org.jdesktop.lg3d.apps.archive;

import org.jdesktop.lg3d.apps.TitledSwingWindow;
import org.jdesktop.lg3d.wg.Frame3D;

/**
 * The Archive application: the {@link ArchivePanel} Swing UI (browse, list,
 * extract and create ZIP / TAR archives) presented as an integrated 3D desktop
 * window (title bar plus minimize / maximize / close) via
 * {@link TitledSwingWindow}, which hosts the panel on a {@code SwingNode} quad
 * below a draggable glassy title bar. The very same panel is reused as an MDI
 * internal frame in the 2D/Swing desktop.
 *
 * <p>{@code main} accepts an optional archive-file argument: when present, the
 * panel opens on that archive and lists its entries; when launched from the
 * start menu there is no argument, so it opens empty. The argument may arrive
 * with a leading space (the in-JVM {@code java <class> <args>} launcher passes
 * the remainder of the command line), so it is trimmed before use.</p>
 */
public class Archive {

    public static void main(String[] args) {
        new Archive(parseInitialArchive(args));
    }

    /** Resolves the starting archive file from the command-line args, or null. */
    private static java.io.File parseInitialArchive(String[] args) {
        if (args != null) {
            for (String a : args) {
                if (a == null) {
                    continue;
                }
                String t = a.trim();
                if (t.isEmpty()) {
                    continue;
                }
                java.io.File f = new java.io.File(t);
                if (f.isFile()) {
                    return f;
                }
                break;
            }
        }
        return null;
    }

    public Archive(java.io.File initial) {
        // Metal, not the platform Synth LAF: Synth widgets NPE when SwingNode
        // paints them offscreen. Must run before the panel is constructed.
        TitledSwingWindow.installHostedLookAndFeel();
        final ArchivePanel panel = new ArchivePanel();
        final Frame3D frame = TitledSwingWindow.show(
                "Archive", panel, ArchivePanel.WIDTH_PX, ArchivePanel.HEIGHT_PX);
        panel.setOnClose(() -> frame.changeEnabled(false));
        if (initial != null) {
            panel.showArchive(initial);
        }
    }
}
