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

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;

/**
 * A hard-edged, single-pixel freehand pencil. Antialiasing is forced off and the
 * stroke snapped to whole pixels so the result reads as a crisp pencil line
 * regardless of the brush width setting.
 */
public class PencilTool extends FreehandTool {

    public String getName() {
        return "Pencil";
    }

    public String getHint() {
        return "Freehand drawing with a hard-edged 1px pencil";
    }

    protected void decorate(Graphics2D g, PaintContext ctx) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_SQUARE,
                BasicStroke.JOIN_MITER));
    }

    protected void drawSegment(Graphics2D g, Point2D from, Point2D to,
            PaintContext ctx) {
        g.draw(new Line2D.Double(
                Math.round(from.getX()), Math.round(from.getY()),
                Math.round(to.getX()), Math.round(to.getY())));
    }
}
