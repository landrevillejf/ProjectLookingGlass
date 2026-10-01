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
package org.jdesktop.lg3d.apps.videoconference;

import org.jdesktop.lg3d.apps.TitledSwingWindow;
import org.jdesktop.lg3d.wg.Frame3D;

/**
 * The Video Conference application: the {@link VideoConferencePanel} Jitsi Meet
 * client presented as an integrated 3D desktop window (title bar plus minimize /
 * maximize / close) via {@link TitledSwingWindow}, which hosts the panel on a
 * {@code SwingNode} quad below a draggable glassy title bar.
 *
 * <p>This is the 3D-desktop entry point (Start Menu &rarr; Internet &rarr; Video
 * Conference). In the 2D/Swing desktop the very same {@link VideoConferencePanel}
 * is hosted as an MDI internal frame by {@code Desktop2DAppRegistry}, so this
 * wrapper is never loaded there &mdash; only the panel is.</p>
 *
 * <p>The panel builds a Jitsi Meet deep link and hands the real audio/video
 * session to the system browser (or an external meeting command); no Java&nbsp;3D
 * and no native media stack are involved, which is why the one Swing panel serves
 * both desktops.</p>
 */
public class VideoConference {

    public static void main(String[] args) {
        new VideoConference();
    }

    public VideoConference() {
        // Metal, not the platform Synth LAF: Synth widgets NPE when SwingNode
        // paints them offscreen. Must run before the panel is constructed.
        TitledSwingWindow.installHostedLookAndFeel();
        final VideoConferencePanel panel = new VideoConferencePanel();
        final Frame3D frame = TitledSwingWindow.show(
                "Video Conference",
                panel,
                VideoConferencePanel.WIDTH_PX,
                VideoConferencePanel.HEIGHT_PX);
        panel.setOnClose(() -> frame.changeEnabled(false));
    }
}
