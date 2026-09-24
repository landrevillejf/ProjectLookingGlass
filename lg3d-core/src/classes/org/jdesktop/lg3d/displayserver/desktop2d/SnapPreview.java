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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.swing.JComponent;

/**
 * The translucent highlight the 2D desktop paints over the region a dragged
 * window will snap to, so the user sees the left-half / right-half / maximised
 * target before releasing the mouse.
 *
 * <p>It spans the whole desktop pane but paints only the current
 * {@linkplain #setTarget(Rectangle) target} rectangle, and paints nothing while
 * hidden or when no target is set. Purely decorative and Java 3D-free; the snap
 * geometry comes from {@link WindowSnap}.</p>
 */
final class SnapPreview extends JComponent {

    private static final Color FILL = new Color(80, 140, 230);
    private static final Color BORDER = new Color(150, 195, 255);
    private static final float FILL_ALPHA = 0.30f;
    private static final int ARC_PX = 14;
    private static final int INSET_PX = 4;

    private Rectangle target;

    SnapPreview() {
        setOpaque(false);
        setVisible(false);
    }

    /**
     * Sets the region to highlight and shows the preview, or hides it when
     * {@code target} is null. Repaints either way.
     */
    void setTarget(Rectangle target) {
        this.target = (target == null) ? null : new Rectangle(target);
        setVisible(this.target != null);
        repaint();
    }

    /** The currently highlighted region, or null when idle. */
    Rectangle getTarget() {
        return (target == null) ? null : new Rectangle(target);
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (!isVisible() || target == null
                || target.width <= 0 || target.height <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int x = target.x + INSET_PX;
            int y = target.y + INSET_PX;
            int w = Math.max(0, target.width - INSET_PX * 2);
            int h = Math.max(0, target.height - INSET_PX * 2);
            Composite saved = g2.getComposite();
            g2.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, FILL_ALPHA));
            g2.setColor(FILL);
            g2.fillRoundRect(x, y, w, h, ARC_PX, ARC_PX);
            g2.setComposite(saved);
            g2.setColor(BORDER);
            g2.drawRoundRect(x, y, w, h, ARC_PX, ARC_PX);
        } finally {
            g2.dispose();
        }
    }
}
