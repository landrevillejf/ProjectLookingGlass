/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.displayserver;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;

/**
 * Crops a {@link RenderedImage} &mdash; typically the raster
 * {@link ScreenCaptureBehavior} reads back from a {@code Canvas3D} &mdash; to a
 * screen-space region, so the 3D desktop can honour the Screen Snapshot app's
 * "selection" area without a second capture path.
 *
 * <p>Pure image arithmetic: it constructs no scene-graph object and needs no
 * display, so it is unit-tested headless against a {@link BufferedImage}. The
 * region is clamped to the image bounds; a region that does not overlap the
 * image (or a null argument) yields {@code null}, which the caller treats as
 * "keep the whole frame".</p>
 */
final class RasterCrop {

    private RasterCrop() {
    }

    /**
     * Returns a new {@link BufferedImage} holding just {@code region} of
     * {@code source}, clamped to the source bounds.
     *
     * @param source the image to crop (may be any {@link RenderedImage})
     * @param region the region to keep, in image coordinates
     * @return the cropped image, or {@code null} when there is no overlap
     */
    static BufferedImage crop(RenderedImage source, Rectangle region) {
        if (source == null || region == null) {
            return null;
        }
        Rectangle bounds = new Rectangle(0, 0, source.getWidth(),
                source.getHeight());
        Rectangle keep = bounds.intersection(region);
        if (keep.isEmpty()) {
            return null;
        }
        BufferedImage src = toBuffered(source);
        BufferedImage out = new BufferedImage(keep.width, keep.height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, keep.width, keep.height,
                    keep.x, keep.y, keep.x + keep.width, keep.y + keep.height,
                    null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage toBuffered(RenderedImage image) {
        if (image instanceof BufferedImage) {
            return (BufferedImage) image;
        }
        BufferedImage copy = new BufferedImage(image.getWidth(),
                image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawRenderedImage(image, new AffineTransform());
        } finally {
            g.dispose();
        }
        return copy;
    }
}
