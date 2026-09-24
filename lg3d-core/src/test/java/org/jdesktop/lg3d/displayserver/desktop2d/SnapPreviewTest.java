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
package org.jdesktop.lg3d.displayserver.desktop2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SnapPreview}: it starts hidden with no target, showing itself
 * only while a target is set, handing back defensive copies of that target, and
 * painting the highlight when a target is present but nothing when idle. Runs
 * headless by painting onto a {@link BufferedImage}.
 */
class SnapPreviewTest {

    @Test
    @DisplayName("a fresh preview is hidden with no target")
    void startsHidden() {
        SnapPreview preview = new SnapPreview();
        assertFalse(preview.isVisible());
        assertNull(preview.getTarget());
    }

    @Test
    @DisplayName("setting a target shows the preview; clearing it hides it")
    void targetTogglesVisibility() {
        SnapPreview preview = new SnapPreview();
        Rectangle target = new Rectangle(0, 0, 400, 600);
        preview.setTarget(target);
        assertTrue(preview.isVisible());
        assertEquals(target, preview.getTarget());
        preview.setTarget(null);
        assertFalse(preview.isVisible());
        assertNull(preview.getTarget());
    }

    @Test
    @DisplayName("the target is copied in and out, never shared")
    void targetIsDefensivelyCopied() {
        SnapPreview preview = new SnapPreview();
        Rectangle target = new Rectangle(10, 20, 300, 400);
        preview.setTarget(target);
        target.x = 999;
        assertEquals(10, preview.getTarget().x);
        Rectangle out = preview.getTarget();
        assertNotSame(out, preview.getTarget());
        out.y = 999;
        assertEquals(20, preview.getTarget().y);
    }

    @Test
    @DisplayName("painting a targeted preview draws the highlight")
    void paintDrawsTarget() {
        SnapPreview preview = new SnapPreview();
        preview.setBounds(0, 0, 800, 600);
        preview.setTarget(new Rectangle(0, 0, 400, 600));
        assertTrue(painted(preview, 800, 600));
    }

    @Test
    @DisplayName("painting an idle preview draws nothing")
    void paintIdleNothing() {
        SnapPreview preview = new SnapPreview();
        preview.setBounds(0, 0, 800, 600);
        assertFalse(painted(preview, 800, 600));
    }

    @Test
    @DisplayName("a degenerate target paints nothing")
    void paintDegenerateTarget() {
        SnapPreview preview = new SnapPreview();
        preview.setBounds(0, 0, 800, 600);
        preview.setTarget(new Rectangle(0, 0, 0, 0));
        assertTrue(preview.isVisible());
        assertFalse(painted(preview, 800, 600));
    }

    private static boolean painted(SnapPreview preview, int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            preview.paint(g);
        } finally {
            g.dispose();
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if ((image.getRGB(x, y) & 0xFF000000) != 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
