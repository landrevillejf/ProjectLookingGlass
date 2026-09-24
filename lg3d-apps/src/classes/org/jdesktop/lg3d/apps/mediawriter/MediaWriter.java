/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.mediawriter;

import org.jdesktop.lg3d.apps.TitledSwingWindow;

/**
 * The Media Writer application: the {@link MediaWriterPanel} (burn ISO to
 * CD/DVD, write a raw image or ISO to a USB key, clone a disc/device, format a
 * removable key, or build a data disc) presented as an integrated 3D desktop
 * window via {@link TitledSwingWindow}, which hosts the Swing panel on a
 * {@code SwingNode} quad below a draggable glassy title bar.
 *
 * <p>All write operations are real and destructive; the panel enumerates
 * physical devices and drives the standard Linux media tools, gating every
 * write behind an explicit inline confirmation.</p>
 */
public class MediaWriter {

    public static void main(String[] args) {
        new MediaWriter();
    }

    public MediaWriter() {
        // Metal, not the platform Synth LAF: Synth widgets NPE when SwingNode
        // paints them offscreen. Must run before the panel is constructed.
        TitledSwingWindow.installHostedLookAndFeel();
        TitledSwingWindow.show(
                "Media Writer",
                new MediaWriterPanel(),
                MediaWriterPanel.WIDTH_PX,
                MediaWriterPanel.HEIGHT_PX);
    }
}
