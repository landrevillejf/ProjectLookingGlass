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

import java.awt.Color;
import java.awt.Font;

/**
 * The mutable tool settings shared by every {@link Tool}: the active tool,
 * foreground/background colours, stroke geometry, tool opacity, font, shape
 * fill/stroke flags, flood-fill tolerance, zoom and the antialiasing switch.
 *
 * <p>One instance lives in {@link PaintFrame} and is handed to tools through
 * the {@link PaintContext}; changing a setting here takes effect on the next
 * stroke and (for zoom) on the next repaint.</p>
 */
public class PaintState {

    private Tool tool;
    private Color foreground = Color.BLACK;
    private Color background = Color.WHITE;
    private float strokeWidth = 3.0f;
    private float[] dash;                 // null => solid
    private float opacity = 1.0f;         // tool opacity, [0,1]
    private Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 24);
    private boolean fillShape = false;
    private boolean strokeShape = true;
    private int tolerance = 32;           // flood-fill colour tolerance 0..255
    private double zoom = 1.0;
    private boolean antialias = true;

    public Tool getTool() {
        return tool;
    }

    public void setTool(Tool tool) {
        this.tool = tool;
    }

    public Color getForeground() {
        return foreground;
    }

    public void setForeground(Color c) {
        if (c != null) {
            this.foreground = c;
        }
    }

    public Color getBackground() {
        return background;
    }

    public void setBackground(Color c) {
        if (c != null) {
            this.background = c;
        }
    }

    public void swapColors() {
        Color t = foreground;
        foreground = background;
        background = t;
    }

    public float getStrokeWidth() {
        return strokeWidth;
    }

    public void setStrokeWidth(float w) {
        this.strokeWidth = Math.max(0.5f, w);
    }

    public float[] getDash() {
        return dash;
    }

    public void setDash(float[] dash) {
        this.dash = dash;
    }

    public float getOpacity() {
        return opacity;
    }

    public void setOpacity(float opacity) {
        this.opacity = Math.max(0.0f, Math.min(1.0f, opacity));
    }

    public Font getFont() {
        return font;
    }

    public void setFont(Font font) {
        if (font != null) {
            this.font = font;
        }
    }

    public boolean isFillShape() {
        return fillShape;
    }

    public void setFillShape(boolean fillShape) {
        this.fillShape = fillShape;
    }

    public boolean isStrokeShape() {
        return strokeShape;
    }

    public void setStrokeShape(boolean strokeShape) {
        this.strokeShape = strokeShape;
    }

    public int getTolerance() {
        return tolerance;
    }

    public void setTolerance(int tolerance) {
        this.tolerance = Math.max(0, Math.min(255, tolerance));
    }

    public double getZoom() {
        return zoom;
    }

    public void setZoom(double zoom) {
        this.zoom = Math.max(0.05, Math.min(32.0, zoom));
    }

    public boolean isAntialias() {
        return antialias;
    }

    public void setAntialias(boolean antialias) {
        this.antialias = antialias;
    }
}
