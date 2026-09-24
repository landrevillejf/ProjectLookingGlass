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
package org.jdesktop.lg3d.apps.paint;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

/**
 * Eyedropper (colour picker). A press or drag samples the composited image under
 * the pointer and makes it the foreground colour, so the user can pick up any
 * colour already on the canvas.
 */
public class EyedropperTool extends AbstractTool {

    public String getName() {
        return "Eyedropper";
    }

    public String getHint() {
        return "Click or drag to sample a colour into the foreground";
    }

    public Cursor getCursor() {
        return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
    }

    public void press(Point2D p, PaintContext ctx) {
        sample(p, ctx);
    }

    public void drag(Point2D p, PaintContext ctx) {
        sample(p, ctx);
    }

    private void sample(Point2D p, PaintContext ctx) {
        BufferedImage composite = ctx.getDocument().getComposite();
        int x = (int) Math.round(p.getX());
        int y = (int) Math.round(p.getY());
        if (x < 0 || y < 0 || x >= composite.getWidth()
                || y >= composite.getHeight()) {
            return;
        }
        int argb = composite.getRGB(x, y);
        ctx.getState().setForeground(new Color(argb, false));
        ctx.fireStateChanged();
    }
}
