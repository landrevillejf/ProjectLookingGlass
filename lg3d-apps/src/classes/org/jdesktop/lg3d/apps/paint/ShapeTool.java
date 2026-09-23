/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
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

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

/**
 * Base for the drag-out two-point shapes (line, rectangle, ellipse). A press sets
 * the anchor and snapshots the layer; each drag updates the opposite corner and
 * repaints so {@link #preview} can rubber-band the shape over the composite; the
 * release commits the final shape into the active layer (filled and/or stroked
 * per the tool state) as one undoable edit.
 */
public abstract class ShapeTool extends AbstractTool {

    protected Point2D anchor;
    protected Point2D current;
    private BufferedImage before;
    private boolean active;

    /** Builds the shape from the current anchor/opposite-corner pair. */
    protected abstract Shape buildShape(PaintContext ctx);

    public void press(Point2D p, PaintContext ctx) {
        before = ctx.snapshotActiveLayer();
        anchor = copy(p);
        current = copy(p);
        active = true;
    }

    public void drag(Point2D p, PaintContext ctx) {
        if (!active) {
            return;
        }
        current = copy(p);
        ctx.repaint();
    }

    public void release(Point2D p, PaintContext ctx) {
        if (!active) {
            return;
        }
        current = copy(p);
        active = false;
        Shape shape = buildShape(ctx);
        Graphics2D g = ctx.activeLayerGraphics();
        if (g != null) {
            paintShape(g, shape, ctx.getState());
            g.dispose();
        }
        anchor = null;
        current = null;
        commitLayerEdit(ctx, before, getName());
        before = null;
        ctx.repaint();
    }

    public void preview(Graphics2D g, PaintContext ctx) {
        if (!active || anchor == null || current == null) {
            return;
        }
        Shape shape = buildShape(ctx);
        if (shape == null) {
            return;
        }
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
        g.setColor(ctx.getState().getForeground());
        if (ctx.getState().isFillShape()) {
            g.fill(shape);
        }
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(ctx.getState().getForeground());
        g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 1.0f, new float[] { 4.0f, 3.0f }, 0.0f));
        g.draw(shape);
    }

    protected static Point2D copy(Point2D p) {
        return new Point2D.Double(p.getX(), p.getY());
    }

    /** The normalised drag rectangle between anchor and current. */
    protected java.awt.Rectangle dragRect() {
        int x = (int) Math.round(Math.min(anchor.getX(), current.getX()));
        int y = (int) Math.round(Math.min(anchor.getY(), current.getY()));
        int w = (int) Math.round(Math.abs(current.getX() - anchor.getX()));
        int h = (int) Math.round(Math.abs(current.getY() - anchor.getY()));
        return new java.awt.Rectangle(x, y, w, h);
    }
}
