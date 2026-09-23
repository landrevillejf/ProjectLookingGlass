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
import java.awt.Graphics2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;

/**
 * Freehand eraser. It paints with {@link AlphaComposite#CLEAR} so it removes the
 * active layer's pixels (alpha to zero) along the stroke width, revealing the
 * layers beneath rather than laying down the background colour.
 */
public class EraserTool extends FreehandTool {

    public String getName() {
        return "Eraser";
    }

    public String getHint() {
        return "Erase pixels from the active layer";
    }

    protected void decorate(Graphics2D g, PaintContext ctx) {
        g.setComposite(AlphaComposite.Clear);
    }

    protected void drawSegment(Graphics2D g, Point2D from, Point2D to,
            PaintContext ctx) {
        g.draw(new Line2D.Double(from.getX(), from.getY(), to.getX(), to.getY()));
    }

    protected String getUndoName() {
        return "Erase";
    }
}
