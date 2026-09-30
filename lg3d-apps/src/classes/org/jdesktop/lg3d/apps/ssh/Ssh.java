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
package org.jdesktop.lg3d.apps.ssh;

import org.jdesktop.lg3d.apps.TitledSwingWindow;
import org.jdesktop.lg3d.apps.firewall.FirewallPanel;
import org.jdesktop.lg3d.wg.Frame3D;

/**
 * The firewall application: the {@link FirewallPanel} Swing UI presented
 * as an integrated 3D desktop window (title bar plus minimize / maximize /
 * close) via {@link TitledSwingWindow}.
 *
 * <p>The panel provides firewall status monitoring and rule management for
 * Linux firewalld/iptables systems.</p>
 */
public class Ssh {

    private static final int PANEL_W = 700;
    private static final int PANEL_H = 500;

    public static void main(String[] args) {
        new Ssh();
    }

    public Ssh() {
        TitledSwingWindow.installHostedLookAndFeel();
        final SshPanel panel = new SshPanel();
        final Frame3D frame =
                TitledSwingWindow.show("Ssh Client", panel, PANEL_W, PANEL_H);
        panel.setOnClose(() -> frame.changeEnabled(false));
    }
}
