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

import java.awt.Graphics2D;
import java.awt.geom.Point2D;
import java.util.Random;

/**
 * An airbrush: each gesture event scatters a burst of single-pixel dots at random
 * within a circular spray whose radius follows the tool's stroke width. Holding
 * the pointer still builds density, so longer dwell means darker coverage.
 */
public class SprayTool extends FreehandTool {

    private final Random random = new Random();

    public String getName() {
        return "Spray";
    }

    public String getHint() {
        return "Airbrush: spray a scatter of dots";
    }

    protected void drawSegment(Graphics2D g, Point2D from, Point2D to,
            PaintContext ctx) {
        spray(g, to, ctx);
    }

    protected void drawDot(Graphics2D g, Point2D at, PaintContext ctx) {
        spray(g, at, ctx);
    }

    private void spray(Graphics2D g, Point2D c, PaintContext ctx) {
        double radius = Math.max(2.0, ctx.getState().getStrokeWidth() * 2.0);
        int count = (int) Math.max(6, radius * 1.5);
        for (int i = 0; i < count; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double dist = Math.sqrt(random.nextDouble()) * radius;
            int x = (int) Math.round(c.getX() + Math.cos(angle) * dist);
            int y = (int) Math.round(c.getY() + Math.sin(angle) * dist);
            g.fillRect(x, y, 1, 1);
        }
    }

    protected String getUndoName() {
        return "Spray";
    }
}
