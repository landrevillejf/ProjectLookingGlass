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
package org.jdesktop.lg3d.apps.recorder;

import org.jdesktop.lg3d.apps.TitledSwingWindow;
import org.jdesktop.lg3d.wg.Frame3D;

/**
 * The audio/video recorder application: the {@link RecorderPanel} Swing UI
 * presented as an integrated 3D desktop window (title bar plus minimize /
 * maximize / close) via {@link TitledSwingWindow}.
 *
 * <p>The panel records the microphone natively to WAV and hands screen capture
 * to an external {@code ffmpeg}. The very same panel is hosted as an MDI internal
 * frame in the 2D/Swing desktop via {@code Desktop2DAppRegistry.PANEL_APPS}.</p>
 */
public class Recorder {

    private static final int PANEL_W = RecorderPanel.WIDTH_PX;
    private static final int PANEL_H = RecorderPanel.HEIGHT_PX;

    public static void main(String[] args) {
        new Recorder();
    }

    public Recorder() {
        TitledSwingWindow.installHostedLookAndFeel();
        final RecorderPanel panel = new RecorderPanel();
        final Frame3D frame =
                TitledSwingWindow.show("Recorder", panel, PANEL_W, PANEL_H);
        panel.setOnClose(() -> {
            panel.stopRecording();
            frame.changeEnabled(false);
        });
    }
}
