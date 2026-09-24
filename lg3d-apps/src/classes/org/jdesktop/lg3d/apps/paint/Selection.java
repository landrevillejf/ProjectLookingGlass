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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.image.BufferedImage;

/**
 * A document-space selection: an axis-aligned bounding {@link Rectangle} plus
 * an optional per-pixel mask. A rectangular marquee leaves the mask null (every
 * pixel inside the bounds is selected); a lasso rasterises its polygon into a
 * mask so only the enclosed pixels are selected.
 *
 * <p>The mask is stored row-major, {@code w*h} booleans, relative to the bounds
 * origin. Selection itself holds no pixels; {@link #extract} pulls the selected
 * region out of a layer (transparent elsewhere) for cut/copy/paste, and
 * {@link #fill} paints a colour into the selected region in place. Moving and
 * cropping are handled by translating the bounds / calling
 * {@link PaintDocument#crop}.</p>
 */
public class Selection {

    private Rectangle bounds;
    private boolean[] mask;   // null => the whole bounds rectangle is selected

    private Selection(Rectangle bounds, boolean[] mask) {
        this.bounds = bounds;
        this.mask = mask;
    }

    /** A rectangular marquee covering {@code r}. */
    public static Selection rectangle(Rectangle r) {
        Rectangle b = normalise(r);
        return new Selection(b, null);
    }

    /**
     * A freeform selection: {@code shape} is rasterised into a mask over its own
     * bounds, so only the pixels it covers are selected.
     */
    public static Selection fromShape(Shape shape) {
        Rectangle b = normalise(shape.getBounds());
        if (b.width <= 0 || b.height <= 0) {
            return null;
        }
        BufferedImage m = new BufferedImage(b.width, b.height,
                BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = m.createGraphics();
        g.setColor(Color.WHITE);
        g.translate(-b.x, -b.y);
        g.fill(shape);
        g.dispose();
        boolean[] mask = new boolean[b.width * b.height];
        for (int y = 0; y < b.height; y++) {
            for (int x = 0; x < b.width; x++) {
                mask[y * b.width + x] = (m.getRGB(x, y) & 0xFFFFFF) != 0;
            }
        }
        return new Selection(b, mask);
    }

    private static Rectangle normalise(Rectangle r) {
        return new Rectangle(r.x, r.y, Math.max(0, r.width),
                Math.max(0, r.height));
    }

    public Rectangle getBounds() {
        return new Rectangle(bounds);
    }

    public boolean isEmpty() {
        return bounds.width <= 0 || bounds.height <= 0;
    }

    /** True when the selection uses a freeform mask rather than a plain rect. */
    public boolean isMasked() {
        return mask != null;
    }

    /** Translates the selection (and keeps the mask) by a pixel delta. */
    public void translate(int dx, int dy) {
        bounds.x += dx;
        bounds.y += dy;
    }

    /** Sets the origin, keeping size and mask. */
    public void setLocation(int x, int y) {
        bounds.x = x;
        bounds.y = y;
    }

    /** True when document point ({@code x},{@code y}) lies inside the mask. */
    public boolean contains(int x, int y) {
        if (!bounds.contains(x, y)) {
            return false;
        }
        if (mask == null) {
            return true;
        }
        int lx = x - bounds.x;
        int ly = y - bounds.y;
        if (lx < 0 || ly < 0 || lx >= bounds.width || ly >= bounds.height) {
            return false;
        }
        return mask[ly * bounds.width + lx];
    }

    /**
     * Returns the selected pixels of {@code layer} as a standalone ARGB image the
     * size of the bounds; everything outside the mask is fully transparent. Used
     * for copy and for building a "floating" paste layer.
     */
    public BufferedImage extract(BufferedImage layer) {
        int w = bounds.width;
        int h = bounds.height;
        if (w <= 0 || h <= 0) {
            return PaintLayer.newImage(1, 1);
        }
        BufferedImage out = PaintLayer.newImage(w, h);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            int sy = bounds.y + y;
            if (sy < 0 || sy >= layer.getHeight()) {
                continue;
            }
            for (int x = 0; x < w; x++) {
                int sx = bounds.x + x;
                if (sx < 0 || sx >= layer.getWidth()) {
                    continue;
                }
                if (mask != null && !mask[y * w + x]) {
                    continue;
                }
                row[x] = layer.getRGB(sx, sy);
            }
            out.setRGB(0, y, w, 1, row, 0, w);
            java.util.Arrays.fill(row, 0);
        }
        return out;
    }

    /**
     * Fills the selected region of {@code layer} with {@code color} (honouring
     * the colour's own alpha and the tool opacity baked into it). Pixels outside
     * the mask are untouched. Returns the pre-change snapshot so the caller can
     * push an undo edit.
     */
    public BufferedImage fill(BufferedImage layer, Color color) {
        BufferedImage before = snapshotRegion(layer);
        int w = bounds.width;
        int h = bounds.height;
        int argb = color.getRGB();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            int sy = bounds.y + y;
            if (sy < 0 || sy >= layer.getHeight()) {
                continue;
            }
            for (int x = 0; x < w; x++) {
                int sx = bounds.x + x;
                if (sx < 0 || sx >= layer.getWidth()) {
                    continue;
                }
                if (mask != null && !mask[y * w + x]) {
                    continue;
                }
                row[x] = argb;
            }
            compositeRow(layer, sy, row, w);
        }
        return before;
    }

    private void compositeRow(BufferedImage layer, int sy, int[] row, int w) {
        Graphics2D g = layer.createGraphics();
        BufferedImage strip = PaintLayer.newImage(w, 1);
        strip.setRGB(0, 0, w, 1, row, 0, w);
        g.drawImage(strip, bounds.x, sy, null);
        g.dispose();
    }

    private BufferedImage snapshotRegion(BufferedImage layer) {
        return ImageOps.crop(layer, bounds);
    }

    /** Clears the selected region of {@code layer} to fully transparent. */
    public BufferedImage clear(BufferedImage layer) {
        BufferedImage before = snapshotRegion(layer);
        int w = bounds.width;
        int h = bounds.height;
        for (int y = 0; y < h; y++) {
            int sy = bounds.y + y;
            if (sy < 0 || sy >= layer.getHeight()) {
                continue;
            }
            for (int x = 0; x < w; x++) {
                int sx = bounds.x + x;
                if (sx < 0 || sx >= layer.getWidth()) {
                    continue;
                }
                if (mask != null && !mask[y * w + x]) {
                    continue;
                }
                layer.setRGB(sx, sy, 0);
            }
        }
        return before;
    }

    /** An independent copy (mask included). */
    public Selection copy() {
        boolean[] m = (mask == null) ? null : mask.clone();
        return new Selection(new Rectangle(bounds), m);
    }
}
