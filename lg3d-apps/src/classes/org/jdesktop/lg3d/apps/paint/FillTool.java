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

import java.awt.Cursor;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Flood fill (paint bucket). A click seed-fills the contiguous region of the
 * active layer whose pixels match the seed colour within the tool's tolerance,
 * replacing them with the foreground colour. Implemented as an explicit-stack
 * scanline flood over the layer's ARGB pixels, so it never recurses and cannot
 * blow the stack on large areas.
 */
public class FillTool extends AbstractTool {

    public String getName() {
        return "Fill";
    }

    public String getHint() {
        return "Click to flood-fill a contiguous area with the foreground colour";
    }

    public Cursor getCursor() {
        return Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
    }

    public void press(Point2D p, PaintContext ctx) {
        PaintLayer layer = ctx.getActiveLayer();
        if (layer == null) {
            return;
        }
        BufferedImage img = layer.getImage();
        int w = img.getWidth();
        int h = img.getHeight();
        int sx = (int) Math.round(p.getX());
        int sy = (int) Math.round(p.getY());
        if (sx < 0 || sy < 0 || sx >= w || sy >= h) {
            return;
        }
        BufferedImage before = layer.snapshot();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        int target = px[sy * w + sx];
        int fill = ctx.getState().getForeground().getRGB();
        int tol = ctx.getState().getTolerance();
        if (matches(fill, target, tol)) {
            // Nothing to do: seed already within tolerance of the fill colour.
            return;
        }
        boolean[] visited = new boolean[w * h];
        Deque<Integer> stack = new ArrayDeque<Integer>();
        stack.push(sy * w + sx);
        while (!stack.isEmpty()) {
            int idx = stack.pop();
            if (visited[idx] || !matches(px[idx], target, tol)) {
                continue;
            }
            visited[idx] = true;
            px[idx] = fill;
            int cx = idx % w;
            int cy = idx / w;
            if (cx > 0) {
                stack.push(idx - 1);
            }
            if (cx < w - 1) {
                stack.push(idx + 1);
            }
            if (cy > 0) {
                stack.push(idx - w);
            }
            if (cy < h - 1) {
                stack.push(idx + w);
            }
        }
        img.setRGB(0, 0, w, h, px, 0, w);
        ctx.getDocument().invalidate();
        commitLayerEdit(ctx, before, "Fill");
        ctx.repaint();
    }

    /** True when every ARGB channel of the two colours is within {@code tol}. */
    private static boolean matches(int c1, int c2, int tol) {
        return Math.abs(((c1 >>> 24) & 0xFF) - ((c2 >>> 24) & 0xFF)) <= tol
                && Math.abs(((c1 >> 16) & 0xFF) - ((c2 >> 16) & 0xFF)) <= tol
                && Math.abs(((c1 >> 8) & 0xFF) - ((c2 >> 8) & 0xFF)) <= tol
                && Math.abs((c1 & 0xFF) - (c2 & 0xFF)) <= tol;
    }
}
