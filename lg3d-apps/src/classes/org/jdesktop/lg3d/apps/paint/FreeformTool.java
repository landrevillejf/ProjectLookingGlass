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
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

/**
 * Freeform (lasso-draw) shape. A press starts a path, each drag appends a
 * segment and rubber-bands it through {@link #preview}, and the release commits
 * it: closed and filled when the tool state asks for a fill, otherwise stroked
 * as an open curve.
 */
public class FreeformTool extends AbstractTool {

    private Path2D path;
    private BufferedImage before;

    public String getName() {
        return "Freeform";
    }

    public String getHint() {
        return "Drag to draw a freeform outline; released to fill or stroke it";
    }

    public void press(Point2D p, PaintContext ctx) {
        before = ctx.snapshotActiveLayer();
        path = new Path2D.Double();
        path.moveTo(p.getX(), p.getY());
    }

    public void drag(Point2D p, PaintContext ctx) {
        if (path == null) {
            return;
        }
        path.lineTo(p.getX(), p.getY());
        ctx.repaint();
    }

    public void release(Point2D p, PaintContext ctx) {
        if (path == null) {
            return;
        }
        path.lineTo(p.getX(), p.getY());
        Path2D commit = path;
        path = null;
        Graphics2D g = ctx.activeLayerGraphics();
        if (g != null) {
            if (ctx.getState().isFillShape()) {
                commit.closePath();
            }
            paintShape(g, commit, ctx.getState());
            g.dispose();
        }
        commitLayerEdit(ctx, before, getName());
        before = null;
        ctx.repaint();
    }

    public void preview(Graphics2D g, PaintContext ctx) {
        if (path == null) {
            return;
        }
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(ctx.getState().getForeground());
        g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 1.0f, new float[] { 4.0f, 3.0f }, 0.0f));
        g.draw(path);
    }

    public void deactivated(PaintContext ctx) {
        path = null;
        before = null;
    }
}
