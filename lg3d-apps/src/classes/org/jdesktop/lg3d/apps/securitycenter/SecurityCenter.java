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
package org.jdesktop.lg3d.apps.securitycenter;

import org.jdesktop.lg3d.apps.TitledSwingWindow;
import org.jdesktop.lg3d.wg.Frame3D;

/**
 * The Antivirus / Security Center application: the {@link SecurityCenterPanel}
 * Swing UI presented as an integrated 3D desktop window (title bar plus minimize
 * / maximize / close) via {@link TitledSwingWindow}.
 *
 * <p>The panel delegates virus scanning to an installed ClamAV and aggregates the
 * host's SELinux / firewall / antivirus posture. The very same panel is hosted as
 * an MDI internal frame in the 2D/Swing desktop via
 * {@code Desktop2DAppRegistry.PANEL_APPS}.</p>
 */
public class SecurityCenter {

    private static final int PANEL_W = SecurityCenterPanel.WIDTH_PX;
    private static final int PANEL_H = SecurityCenterPanel.HEIGHT_PX;

    public static void main(String[] args) {
        new SecurityCenter();
    }

    public SecurityCenter() {
        TitledSwingWindow.installHostedLookAndFeel();
        final SecurityCenterPanel panel = new SecurityCenterPanel();
        final Frame3D frame =
                TitledSwingWindow.show("Security Center", panel, PANEL_W, PANEL_H);
        panel.setOnClose(() -> {
            panel.stopScan();
            frame.changeEnabled(false);
        });
    }
}
