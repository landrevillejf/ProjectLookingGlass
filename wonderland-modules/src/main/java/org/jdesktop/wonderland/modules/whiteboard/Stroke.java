/*
 * wonderland-modules M4 (whiteboard): one drawn stroke.
 *
 * An immutable polyline (two or more points) with a colour and width -- the
 * primitive the shared drawing surface syncs. Kept as plain double geometry so
 * it needs neither Java 3D nor AWT and is trivially testable; the renderer that
 * rasterises strokes onto the whiteboard texture is deferred to M6.
 */
package org.jdesktop.wonderland.modules.whiteboard;

import java.util.List;

/** An immutable polyline with a colour and width. */
public final class Stroke {

    /** A 2D point in surface coordinates. */
    public static final class Point {
        private final double x;
        private final double y;

        /**
         * @param x horizontal coordinate
         * @param y vertical coordinate
         */
        public Point(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        @Override
        public String toString() {
            return "(" + x + ", " + y + ")";
        }
    }

    private final List<Point> points;
    private final int rgb;
    private final float width;

    /**
     * @param points two or more points, copied defensively
     * @param rgb stroke colour as a packed 0xRRGGBB
     * @param width stroke width in surface units (positive)
     * @throws IllegalArgumentException when fewer than two points or a non-positive width
     */
    public Stroke(List<Point> points, int rgb, float width) {
        if (points == null || points.size() < 2) {
            throw new IllegalArgumentException("a stroke needs at least two points");
        }
        if (width <= 0f) {
            throw new IllegalArgumentException("stroke width must be positive: " + width);
        }
        this.points = List.copyOf(points);
        this.rgb = rgb;
        this.width = width;
    }

    /** The stroke's vertices, oldest first. */
    public List<Point> getPoints() {
        return points;
    }

    /** The packed 0xRRGGBB colour. */
    public int getRgb() {
        return rgb;
    }

    /** The stroke width in surface units. */
    public float getWidth() {
        return width;
    }
}
