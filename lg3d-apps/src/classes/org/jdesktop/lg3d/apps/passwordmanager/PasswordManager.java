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
package org.jdesktop.lg3d.apps.passwordmanager;

import org.jdesktop.lg3d.apps.TitledSwingWindow;
import org.jdesktop.lg3d.wg.Frame3D;

/**
 * The Password Manager application: the {@link PasswordManagerPanel} Swing UI
 * presented as an integrated 3D desktop window (title bar plus minimize /
 * maximize / close) via {@link TitledSwingWindow}.
 *
 * <p>The panel keeps its credentials in a master-password-sealed vault (PBKDF2 +
 * AES-GCM, via {@link VaultCrypto}). The very same panel is hosted as an MDI
 * internal frame in the 2D/Swing desktop via
 * {@code Desktop2DAppRegistry.PANEL_APPS}.</p>
 */
public class PasswordManager {

    private static final int PANEL_W = PasswordManagerPanel.WIDTH_PX;
    private static final int PANEL_H = PasswordManagerPanel.HEIGHT_PX;

    public static void main(String[] args) {
        new PasswordManager();
    }

    public PasswordManager() {
        TitledSwingWindow.installHostedLookAndFeel();
        final PasswordManagerPanel panel = new PasswordManagerPanel();
        final Frame3D frame =
                TitledSwingWindow.show("Password Manager", panel, PANEL_W, PANEL_H);
        panel.setOnClose(() -> {
            panel.lock();
            frame.changeEnabled(false);
        });
    }
}
