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

import org.jdesktop.lg3d.apps.TitledSwingWindow;
import org.jdesktop.lg3d.wg.Frame3D;

/**
 * The VPN client application: the {@link VpnPanel} Swing UI presented as an
 * integrated 3D desktop window (title bar plus minimize / maximize / close) via
 * {@link TitledSwingWindow}.
 *
 * <p>The desktop ships no tunnel stack, so the panel delegates the connection to
 * an installed tool through {@link VpnBackend} ({@code nmcli} preferred, or
 * {@code openvpn} / {@code wg-quick} for an imported config). The very same panel
 * is hosted as an MDI internal frame in the 2D/Swing desktop via
 * {@code Desktop2DAppRegistry.PANEL_APPS}.</p>
 */
public class Vpn {

    private static final int PANEL_W = VpnPanel.WIDTH_PX;
    private static final int PANEL_H = VpnPanel.HEIGHT_PX;

    public static void main(String[] args) {
        new Vpn();
    }

    public Vpn() {
        TitledSwingWindow.installHostedLookAndFeel();
        final VpnPanel panel = new VpnPanel();
        final Frame3D frame =
                TitledSwingWindow.show("VPN", panel, PANEL_W, PANEL_H);
        panel.setOnClose(() -> {
            panel.stopTunnel();
            frame.changeEnabled(false);
        });
    }
}
