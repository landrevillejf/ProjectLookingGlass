/*
 * wonderland-modules M4 (whiteboard): the shared drawing surface model.
 *
 * The ordered stroke list a whiteboard cell syncs across participants, with the
 * add / undo / clear / bounds operations the drawing tool drives. Rendering the
 * strokes to a texture is deferred to M6; the surface state itself is fully
 * specified and tested here.
 */
package org.jdesktop.wonderland.modules.whiteboard;

import java.util.ArrayList;
import java.util.List;

/** An ordered list of strokes with undo, clear and bounds. */
public final class WhiteboardModel {

    private final List<Stroke> strokes = new ArrayList<>();

    /**
     * Append a stroke.
     *
     * @param stroke the stroke to add
     * @throws IllegalArgumentException when {@code stroke} is null
     */
    public void addStroke(Stroke stroke) {
        if (stroke == null) {
            throw new IllegalArgumentException("stroke must not be null");
        }
        strokes.add(stroke);
    }

    /**
     * Remove the most recently added stroke (the drawing tool's undo).
     *
     * @return the removed stroke, or {@code null} when the surface was empty
     */
    public Stroke undoLast() {
        return strokes.isEmpty() ? null : strokes.remove(strokes.size() - 1);
    }

    /** Remove every stroke (the whiteboard's clear). */
    public void clear() {
        strokes.clear();
    }

    /** The strokes, drawing order, as a snapshot. */
    public List<Stroke> strokes() {
        return List.copyOf(strokes);
    }

    /** The number of strokes currently on the surface. */
    public int strokeCount() {
        return strokes.size();
    }

    /**
     * The bounding box of every point of every stroke.
     *
     * @return {@code {minX, minY, maxX, maxY}}, or {@code null} when empty
     */
    public double[] bounds() {
        if (strokes.isEmpty()) {
            return null;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Stroke stroke : strokes) {
            for (Stroke.Point point : stroke.getPoints()) {
                minX = Math.min(minX, point.getX());
                minY = Math.min(minY, point.getY());
                maxX = Math.max(maxX, point.getX());
                maxY = Math.max(maxY, point.getY());
            }
        }
        return new double[] {minX, minY, maxX, maxY};
    }
}
