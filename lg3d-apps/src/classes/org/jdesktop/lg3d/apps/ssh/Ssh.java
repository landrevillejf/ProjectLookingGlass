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
import org.jdesktop.lg3d.wg.Frame3D;

/**
 * The SSH client application: the {@link SshPanel} Swing UI presented as an
 * integrated 3D desktop window (title bar plus minimize / maximize / close)
 * via {@link TitledSwingWindow}.
 *
 * <p>The panel provides a full-featured SSH terminal client with tabbed
 * multi-session support, ANSI/VT100 terminal emulation, SSH key authentication,
 * trust-on-first-use host key verification, connection profiles, keepalive,
 * port forwarding, and modern cipher negotiation.</p>
 */
public class Ssh {

    private static final int PANEL_W = 900;
    private static final int PANEL_H = 600;

    public static void main(String[] args) {
        new Ssh();
    }

    public Ssh() {
        TitledSwingWindow.installHostedLookAndFeel();
        final SshPanel panel = new SshPanel();
        final Frame3D frame =
                TitledSwingWindow.show("SSH Client", panel, PANEL_W, PANEL_H);
        panel.setOnClose(() -> frame.changeEnabled(false));
    }
}
