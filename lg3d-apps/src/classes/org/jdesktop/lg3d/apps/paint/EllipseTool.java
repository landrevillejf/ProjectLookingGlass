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

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;

/** Ellipse inscribed in the dragged rectangle; filled and/or stroked. */
public class EllipseTool extends ShapeTool {

    public String getName() {
        return "Ellipse";
    }

    public String getHint() {
        return "Drag to draw an ellipse";
    }

    protected Shape buildShape(PaintContext ctx) {
        Rectangle r = dragRect();
        return new Ellipse2D.Double(r.x, r.y, Math.max(1, r.width),
                Math.max(1, r.height));
    }
}
