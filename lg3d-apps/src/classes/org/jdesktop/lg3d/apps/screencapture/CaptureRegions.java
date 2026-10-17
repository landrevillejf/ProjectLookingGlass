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
package org.jdesktop.lg3d.apps.screencapture;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Collection;

/**
 * Pure rectangle arithmetic for the screen-capture engine: turning a
 * mouse-drag into a positive {@link Rectangle}, folding a set of window bounds
 * into the region to composite, clamping a selection back inside the screen,
 * and cropping an image to a region.
 *
 * <p>Everything here is display-free (it never creates a peer), so it runs and
 * is asserted under {@code java.awt.headless}; only the callers that paint live
 * windows need a display.</p>
 */
final class CaptureRegions {

    private CaptureRegions() {
    }

    /**
     * Normalises a drag from {@code (x1,y1)} to {@code (x2,y2)} into a
     * {@link Rectangle} with a non-negative width and height, whichever corner
     * the user started from.
     *
     * @return the drag rectangle (empty when the two points coincide)
     */
    static Rectangle normalise(int x1, int y1, int x2, int y2) {
        return new Rectangle(Math.min(x1, x2), Math.min(y1, y2),
                Math.abs(x2 - x1), Math.abs(y2 - y1));
    }

    /**
     * The tightest rectangle containing every non-empty rectangle in
     * {@code rects}. Null entries and empty (non-positive) rectangles are
     * ignored.
     *
     * @return the union, or an empty rectangle when there is nothing to bound
     */
    static Rectangle union(Collection<Rectangle> rects) {
        Rectangle union = null;
        if (rects != null) {
            for (Rectangle r : rects) {
                if (r == null || r.width <= 0 || r.height <= 0) {
                    continue;
                }
                union = (union == null) ? new Rectangle(r) : union.union(r);
            }
        }
        return (union == null) ? new Rectangle() : union;
    }

    /**
     * Clamps {@code region} into {@code bounds}, returning their overlap. A
     * region that does not touch the bounds, or a null argument, yields an
     * empty rectangle rather than a partly-outside one.
     *
     * @return the overlap of {@code region} and {@code bounds}
     */
    static Rectangle clamp(Rectangle region, Rectangle bounds) {
        if (region == null || bounds == null) {
            return new Rectangle();
        }
        Rectangle overlap = bounds.intersection(region);
        return overlap.isEmpty() ? new Rectangle() : overlap;
    }

    /**
     * Crops {@code image} to {@code region} (clamped to the image bounds),
     * copying the pixels into a fresh, standalone image. Returns {@code null}
     * when there is nothing to crop &mdash; a null image, or a region that does
     * not overlap it.
     *
     * @param image  the source image
     * @param region the region to keep, in image coordinates
     * @return a new image holding just that region, or {@code null}
     */
    static BufferedImage crop(BufferedImage image, Rectangle region) {
        if (image == null) {
            return null;
        }
        Rectangle keep = clamp(region,
                new Rectangle(0, 0, image.getWidth(), image.getHeight()));
        if (keep.width <= 0 || keep.height <= 0) {
            return null;
        }
        int type = image.getType() == 0 ? BufferedImage.TYPE_INT_ARGB
                : image.getType();
        BufferedImage out = new BufferedImage(keep.width, keep.height, type);
        java.awt.Graphics2D g = out.createGraphics();
        try {
            g.drawImage(image, 0, 0, keep.width, keep.height,
                    keep.x, keep.y, keep.x + keep.width, keep.y + keep.height,
                    null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
