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
package org.jdesktop.lg3d.apps.about;

import org.jdesktop.lg3d.apps.TitledSwingWindow;

/**
 * The About application: the {@link AboutPanel} presented as an integrated 3D
 * desktop window (title bar plus minimize / maximize / close) via
 * {@link TitledSwingWindow}, which hosts the Swing panel on a {@code SwingNode}
 * quad below a draggable glassy title bar.
 *
 * <p>This is the 3D-desktop entry point (Start Menu &rarr; Utilities &rarr;
 * About). In the 2D/Swing desktop the same {@link AboutPanel} is hosted as an
 * MDI internal frame by {@code Desktop2DAppRegistry}, so this wrapper is never
 * loaded there &mdash; only the panel is.</p>
 */
public class About {

    public static void main(String[] args) {
        new About();
    }

    public About() {
        // Metal, not the platform Synth LAF: Synth widgets NPE when SwingNode
        // paints them offscreen. Must run before the panel is constructed.
        TitledSwingWindow.installHostedLookAndFeel();
        TitledSwingWindow.show(
                "About " + AboutInfo.PRODUCT_NAME,
                new AboutPanel(),
                AboutPanel.WIDTH_PX,
                AboutPanel.HEIGHT_PX);
    }
}
