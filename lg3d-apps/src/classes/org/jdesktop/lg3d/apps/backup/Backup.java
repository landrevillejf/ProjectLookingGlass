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

import org.jdesktop.lg3d.apps.TitledSwingWindow;
import org.jdesktop.lg3d.wg.Frame3D;

/**
 * The backup/restore application: the {@link BackupPanel} Swing UI presented as
 * an integrated 3D desktop window (title bar plus minimize / maximize / close)
 * via {@link TitledSwingWindow}.
 *
 * <p>The panel archives selected folders and files to standard ZIP backups and
 * restores them, with saved profiles, glob excludes and a Zip-Slip-hardened
 * restore. The very same panel is hosted as an MDI internal frame in the
 * 2D/Swing desktop via {@code Desktop2DAppRegistry.PANEL_APPS}.</p>
 */
public class Backup {

    private static final int PANEL_W = BackupPanel.WIDTH_PX;
    private static final int PANEL_H = BackupPanel.HEIGHT_PX;

    public static void main(String[] args) {
        new Backup();
    }

    public Backup() {
        TitledSwingWindow.installHostedLookAndFeel();
        final BackupPanel panel = new BackupPanel();
        final Frame3D frame =
                TitledSwingWindow.show("Backup", panel, PANEL_W, PANEL_H);
        panel.setOnClose(() -> frame.changeEnabled(false));
    }
}
