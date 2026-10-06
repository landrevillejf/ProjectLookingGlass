/**
 * Project Looking Glass
 *
 * Copyright (c) 2026 Project Looking Glass contributors.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

/**
 * Coalesces the damage rectangles reported for one composited window into a
 * single bounding region, so the compositor reads back and re-uploads only the
 * union of what actually changed instead of the whole window on every repaint
 * (Phase E: damage-region readback + texture-upload batching).
 *
 * <p>Pure geometry with no X, no clock and no Java 3D state, so the coalescing
 * is unit-testable headlessly. A live frame loop calls {@link #add} for every
 * {@code DamageNotify} that arrives between frames, then {@link #drain} once per
 * presented frame to get the single region to read; {@link CompositedWindowPipeline}
 * does exactly this.</p>
 *
 * <p>Coalescing to a bounding box (rather than tracking each rectangle) is the
 * deliberate trade-off the compositor needs: one readback and one texture upload
 * per frame, at the cost of re-reading the pixels between two far-apart damage
 * rects. For the common case — a cursor blink, a scrolling list, a video frame —
 * the damage is already contiguous, so the bounding box is tight.</p>
 *
 * @see CompositedWindowPipeline
 */
public final class DamageAccumulator {

    private boolean empty = true;
    private int minX;
    private int minY;
    private int maxX;
    private int maxY;
    private int rectCount;

    /** Creates an empty accumulator. */
    public DamageAccumulator() {
        // no state beyond the fields above
    }

    /**
     * Adds a damage rectangle, clamping a negative origin to zero (matching
     * {@link CompositedWindowPipeline} and the tile loader). Empty or
     * non-positive-area rectangles are ignored.
     *
     * @param x      region left, in window pixels
     * @param y      region top, in window pixels
     * @param width  region width
     * @param height region height
     */
    public void add(int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = x0 + width - 1;  // inclusive right edge
        int y1 = y0 + height - 1; // inclusive bottom edge
        if (empty) {
            minX = x0;
            minY = y0;
            maxX = x1;
            maxY = y1;
            empty = false;
        } else {
            minX = Math.min(minX, x0);
            minY = Math.min(minY, y0);
            maxX = Math.max(maxX, x1);
            maxY = Math.max(maxY, y1);
        }
        rectCount++;
    }

    /** True if no damage has been accumulated since the last reset/drain. */
    public boolean isEmpty() {
        return empty;
    }

    /** The number of rectangles coalesced since the last reset/drain. */
    public int rectCount() {
        return rectCount;
    }

    /** The bounding region's left edge, or 0 when empty. */
    public int x() {
        return minX;
    }

    /** The bounding region's top edge, or 0 when empty. */
    public int y() {
        return minY;
    }

    /** The bounding region's width, or 0 when empty. */
    public int width() {
        return empty ? 0 : maxX - minX + 1;
    }

    /** The bounding region's height, or 0 when empty. */
    public int height() {
        return empty ? 0 : maxY - minY + 1;
    }

    /** The bounding region's area in pixels, or 0 when empty. */
    public long area() {
        return empty ? 0L : (long) width() * height();
    }

    /** Clears the accumulated region and the rectangle count. */
    public void reset() {
        empty = true;
        rectCount = 0;
        minX = 0;
        minY = 0;
        maxX = 0;
        maxY = 0;
    }

    /**
     * Returns the coalesced region as an immutable {@link Region} and clears the
     * accumulator, or null if nothing was accumulated.
     *
     * @return the drained region, or null when empty
     */
    public Region drain() {
        if (empty) {
            return null;
        }
        Region r = new Region(minX, minY, width(), height(), rectCount);
        reset();
        return r;
    }

    /** An immutable coalesced damage region. */
    public static final class Region {

        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final int rectCount;

        Region(int x, int y, int width, int height, int rectCount) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.rectCount = rectCount;
        }

        /** The region's left edge, in window pixels. */
        public int x() {
            return x;
        }

        /** The region's top edge, in window pixels. */
        public int y() {
            return y;
        }

        /** The region's width. */
        public int width() {
            return width;
        }

        /** The region's height. */
        public int height() {
            return height;
        }

        /** How many damage rectangles were coalesced into this region. */
        public int rectCount() {
            return rectCount;
        }

        /** The region's area in pixels. */
        public long area() {
            return (long) width * height;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Region)) {
                return false;
            }
            Region r = (Region) o;
            return x == r.x && y == r.y && width == r.width
                && height == r.height && rectCount == r.rectCount;
        }

        @Override
        public int hashCode() {
            int h = x;
            h = 31 * h + y;
            h = 31 * h + width;
            h = 31 * h + height;
            h = 31 * h + rectCount;
            return h;
        }

        @Override
        public String toString() {
            return "Region[x=" + x + ",y=" + y + ",w=" + width + ",h=" + height
                + ",rects=" + rectCount + "]";
        }
    }
}
