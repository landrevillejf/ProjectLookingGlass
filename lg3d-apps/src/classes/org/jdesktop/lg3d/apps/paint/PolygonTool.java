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
package org.jdesktop.lg3d.apps.paint;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Click-to-place polygon. Each click adds a vertex; clicking near the first
 * vertex (or calling {@link #finish}) closes the polygon and commits it into the
 * active layer, filled and/or stroked per the tool state. {@link #cancel} drops
 * an in-progress polygon. The canvas forwards Enter/Escape (or a double-click)
 * to {@link #finish}/{@link #cancel}.
 */
public class PolygonTool extends AbstractTool {

    private static final double CLOSE_THRESHOLD = 8.0;

    private final List<Point2D> points = new ArrayList<Point2D>();
    private Point2D hover;
    private BufferedImage before;

    public String getName() {
        return "Polygon";
    }

    public String getHint() {
        return "Click to add vertices; click the first point (or Enter) to close";
    }

    public void press(Point2D p, PaintContext ctx) {
        Point2D q = copy(p);
        if (points.isEmpty()) {
            before = ctx.snapshotActiveLayer();
            points.add(q);
        } else if (points.size() >= 2
                && q.distance(points.get(0)) <= CLOSE_THRESHOLD) {
            finish(ctx);
            return;
        } else {
            points.add(q);
        }
        hover = q;
        ctx.repaint();
    }

    public void drag(Point2D p, PaintContext ctx) {
        if (points.isEmpty()) {
            return;
        }
        hover = copy(p);
        ctx.repaint();
    }

    public void preview(Graphics2D g, PaintContext ctx) {
        if (points.isEmpty()) {
            return;
        }
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(ctx.getState().getForeground());
        g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 1.0f, new float[] { 4.0f, 3.0f }, 0.0f));
        Point2D prev = points.get(0);
        for (int i = 1; i < points.size(); i++) {
            Point2D cur = points.get(i);
            g.draw(new Line2D.Double(prev, cur));
            prev = cur;
        }
        if (hover != null) {
            g.draw(new Line2D.Double(prev, hover));
        }
        // Mark the closing vertex.
        Point2D first = points.get(0);
        g.draw(new Line2D.Double(first.getX() - 3, first.getY(),
                first.getX() + 3, first.getY()));
        g.draw(new Line2D.Double(first.getX(), first.getY() - 3,
                first.getX(), first.getY() + 3));
    }

    /** Closes and commits the polygon; a no-op with fewer than two vertices. */
    public void finish(PaintContext ctx) {
        if (points.size() < 2) {
            cancel(ctx);
            return;
        }
        Path2D path = new Path2D.Double();
        path.moveTo(points.get(0).getX(), points.get(0).getY());
        for (int i = 1; i < points.size(); i++) {
            path.lineTo(points.get(i).getX(), points.get(i).getY());
        }
        path.closePath();
        Graphics2D g = ctx.activeLayerGraphics();
        if (g != null) {
            paintShape(g, path, ctx.getState());
            g.dispose();
        }
        reset();
        commitLayerEdit(ctx, before, getName());
        before = null;
        ctx.repaint();
    }

    /** Abandons an in-progress polygon without committing. */
    public void cancel(PaintContext ctx) {
        reset();
        before = null;
        if (ctx != null) {
            ctx.repaint();
        }
    }

    public void deactivated(PaintContext ctx) {
        if (!points.isEmpty()) {
            cancel(ctx);
        }
    }

    private void reset() {
        points.clear();
        hover = null;
    }

    private static Point2D copy(Point2D p) {
        return new Point2D.Double(p.getX(), p.getY());
    }
}
