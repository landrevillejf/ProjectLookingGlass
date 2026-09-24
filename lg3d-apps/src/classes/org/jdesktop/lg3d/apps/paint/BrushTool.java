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

import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;

/**
 * A soft, antialiased freehand brush that honours the tool's stroke width,
 * opacity and foreground colour. A round line cap makes a bare click paint a
 * filled dot of the brush diameter.
 */
public class BrushTool extends FreehandTool {

    public String getName() {
        return "Brush";
    }

    public String getHint() {
        return "Freehand painting with a soft, antialiased brush";
    }

    protected void drawSegment(Graphics2D g, Point2D from, Point2D to,
            PaintContext ctx) {
        g.draw(new Line2D.Double(from.getX(), from.getY(), to.getX(), to.getY()));
    }
}
