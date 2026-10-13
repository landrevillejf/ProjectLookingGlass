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
package org.jdesktop.lg3d.apps.wonderland;

import org.jdesktop.lg3d.apps.TitledSwingWindow;

/**
 * The Wonderland World application: a browser over a running
 * {@code wonderland-server} world, presented as an integrated 3D desktop window
 * (title bar plus minimize / maximize / close) via {@link TitledSwingWindow},
 * which hosts the {@link WonderlandWorldPanel} on a {@code SwingNode} quad below
 * a draggable glassy title bar.
 *
 * <p>This is the 3D-desktop entry point (Start Menu &rarr; Internet &rarr;
 * Wonderland World). In the 2D/Swing desktop the same
 * {@link WonderlandWorldPanel} is hosted as an MDI internal frame by
 * {@code Desktop2DAppRegistry}, so this wrapper is never loaded there &mdash;
 * only the panel is.</p>
 *
 * <p>The 3D <em>world</em> itself is not this window: the scene-graph embedding
 * of the live cells is {@code wonderland-desktop}'s job (its
 * {@code WonderlandWorldContainer} mounts each cell under the desktop's own
 * Java 3D graph). This app is the shell's read-only view of what that world
 * currently holds.</p>
 */
public class WonderlandWorld {

    public static void main(String[] args) {
        new WonderlandWorld();
    }

    public WonderlandWorld() {
        // Metal, not the platform Synth LAF: Synth widgets NPE when SwingNode
        // paints them offscreen. Must run before the panel is constructed.
        TitledSwingWindow.installHostedLookAndFeel();
        TitledSwingWindow.show(
                "Wonderland World",
                new WonderlandWorldPanel(),
                WonderlandWorldPanel.WIDTH_PX,
                WonderlandWorldPanel.HEIGHT_PX);
    }
}
