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
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Point2D;

/**
 * Rectangular marquee selection. Dragging on empty canvas defines a new
 * rectangular {@link Selection}; dragging from inside the current selection moves
 * it instead. A click that does not drag clears the selection.
 */
public class SelectRectTool extends AbstractTool {

    private Point2D anchor;
    private Point2D current;
    private boolean draggingNew;
    private boolean moving;
    private Point2D moveStart;
    private int origX;
    private int origY;

    public String getName() {
        return "Select";
    }

    public String getHint() {
        return "Drag to select a rectangle; drag inside it to move";
    }

    public Cursor getCursor() {
        return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
    }

    public void press(Point2D p, PaintContext ctx) {
        Selection sel = ctx.getSelection();
        if (sel != null && !sel.isEmpty()
                && sel.contains((int) Math.round(p.getX()),
                        (int) Math.round(p.getY()))) {
            moving = true;
            moveStart = copy(p);
            Rectangle b = sel.getBounds();
            origX = b.x;
            origY = b.y;
            return;
        }
        draggingNew = true;
        anchor = copy(p);
        current = copy(p);
    }

    public void drag(Point2D p, PaintContext ctx) {
        if (moving) {
            Selection sel = ctx.getSelection();
            int dx = (int) Math.round(p.getX() - moveStart.getX());
            int dy = (int) Math.round(p.getY() - moveStart.getY());
            sel.setLocation(origX + dx, origY + dy);
            ctx.repaint();
            return;
        }
        if (draggingNew) {
            current = copy(p);
            ctx.repaint();
        }
    }

    public void release(Point2D p, PaintContext ctx) {
        if (moving) {
            moving = false;
            ctx.selectionChanged();
            return;
        }
        if (!draggingNew) {
            return;
        }
        draggingNew = false;
        current = copy(p);
        Rectangle r = dragRect();
        anchor = null;
        current = null;
        if (r.width <= 1 && r.height <= 1) {
            ctx.setSelection(null);   // a bare click clears the selection
        } else {
            ctx.setSelection(Selection.rectangle(r));
        }
        ctx.repaint();
    }

    public void preview(Graphics2D g, PaintContext ctx) {
        if (!draggingNew || anchor == null || current == null) {
            return;
        }
        Rectangle r = dragRect();
        g.setColor(java.awt.Color.WHITE);
        g.setStroke(new BasicStroke(1.0f));
        g.drawRect(r.x, r.y, r.width, r.height);
        g.setColor(java.awt.Color.BLACK);
        g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 1.0f, new float[] { 3.0f, 3.0f }, 0.0f));
        g.drawRect(r.x, r.y, r.width, r.height);
    }

    public void deactivated(PaintContext ctx) {
        draggingNew = false;
        moving = false;
        anchor = null;
        current = null;
    }

    private Rectangle dragRect() {
        int x = (int) Math.round(Math.min(anchor.getX(), current.getX()));
        int y = (int) Math.round(Math.min(anchor.getY(), current.getY()));
        int w = (int) Math.round(Math.abs(current.getX() - anchor.getX()));
        int h = (int) Math.round(Math.abs(current.getY() - anchor.getY()));
        return new Rectangle(x, y, w, h);
    }

    private static Point2D copy(Point2D p) {
        return new Point2D.Double(p.getX(), p.getY());
    }
}
