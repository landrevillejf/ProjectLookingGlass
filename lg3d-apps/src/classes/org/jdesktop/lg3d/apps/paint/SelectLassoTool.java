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

import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;

/**
 * Freeform (lasso) selection. Dragging traces an outline that is closed on
 * release and rasterised into a masked {@link Selection}, so only the enclosed
 * pixels are selected. The live outline is rubber-banded through {@link #preview}.
 */
public class SelectLassoTool extends AbstractTool {

    private Path2D path;
    private boolean active;

    public String getName() {
        return "Lasso";
    }

    public String getHint() {
        return "Drag to trace a freeform selection";
    }

    public Cursor getCursor() {
        return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
    }

    public void press(Point2D p, PaintContext ctx) {
        path = new Path2D.Double();
        path.moveTo(p.getX(), p.getY());
        active = true;
    }

    public void drag(Point2D p, PaintContext ctx) {
        if (!active) {
            return;
        }
        path.lineTo(p.getX(), p.getY());
        ctx.repaint();
    }

    public void release(Point2D p, PaintContext ctx) {
        if (!active) {
            return;
        }
        active = false;
        path.lineTo(p.getX(), p.getY());
        path.closePath();
        Path2D done = path;
        path = null;
        if (done.getBounds().width <= 1 && done.getBounds().height <= 1) {
            ctx.setSelection(null);
        } else {
            ctx.setSelection(Selection.fromShape(done));
        }
        ctx.repaint();
    }

    public void preview(Graphics2D g, PaintContext ctx) {
        if (!active || path == null) {
            return;
        }
        g.setColor(java.awt.Color.BLACK);
        g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 1.0f, new float[] { 3.0f, 3.0f }, 0.0f));
        g.draw(path);
    }

    public void deactivated(PaintContext ctx) {
        active = false;
        path = null;
    }
}
