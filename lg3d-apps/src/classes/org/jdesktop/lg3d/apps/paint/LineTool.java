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

import java.awt.Shape;
import java.awt.geom.Line2D;

/** Straight line dragged from an anchor to the release point. */
public class LineTool extends ShapeTool {

    public String getName() {
        return "Line";
    }

    public String getHint() {
        return "Drag to draw a straight line";
    }

    protected Shape buildShape(PaintContext ctx) {
        return new Line2D.Double(anchor.getX(), anchor.getY(),
                current.getX(), current.getY());
    }
}
