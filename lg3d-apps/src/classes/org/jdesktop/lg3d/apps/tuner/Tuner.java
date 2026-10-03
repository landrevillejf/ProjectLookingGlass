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
package org.jdesktop.lg3d.apps.tuner;

import org.jdesktop.lg3d.apps.TitledSwingWindow;
import org.jdesktop.lg3d.wg.Frame3D;

/**
 * The guitar / bass tuner application: the {@link TunerPanel} Swing UI presented
 * as an integrated 3D desktop window (title bar plus minimize / maximize / close)
 * via {@link TitledSwingWindow}.
 *
 * <p>The panel analyses the microphone natively with the in-process YIN
 * {@link PitchDetector} - no external tool, no recording. The very same panel is
 * hosted as an MDI internal frame in the 2D/Swing desktop via
 * {@code Desktop2DAppRegistry.PANEL_APPS}.</p>
 */
public class Tuner {

    private static final int PANEL_W = TunerPanel.WIDTH_PX;
    private static final int PANEL_H = TunerPanel.HEIGHT_PX;

    public static void main(String[] args) {
        new Tuner();
    }

    public Tuner() {
        // Metal, not the platform Synth LAF: Synth widgets NPE when SwingNode
        // paints them offscreen. Must run before the panel is constructed.
        TitledSwingWindow.installHostedLookAndFeel();
        final TunerPanel panel = new TunerPanel();
        final Frame3D frame =
                TitledSwingWindow.show("Tuner", panel, PANEL_W, PANEL_H);
        panel.setOnClose(() -> frame.changeEnabled(false));
    }
}
