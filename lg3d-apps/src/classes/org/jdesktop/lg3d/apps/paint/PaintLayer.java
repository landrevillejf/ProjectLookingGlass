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

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * A single raster layer of a {@link PaintDocument}: an ARGB
 * {@link BufferedImage} plus the presentation attributes the compositor honours
 * (name, visibility, opacity and blend mode).
 *
 * <p>Layers are transparent by default; the document's background colour shows
 * through wherever every layer is transparent. Tools draw straight into
 * {@link #getImage()}'s {@link Graphics2D}; the document composites the visible
 * layers bottom-up into the on-screen image.</p>
 */
public class PaintLayer {

    /** How a layer's pixels combine with what is already beneath it. */
    public enum BlendMode {
        NORMAL("Normal"),
        MULTIPLY("Multiply"),
        SCREEN("Screen"),
        SOFT_LIGHT("Soft light");

        private final String label;

        BlendMode(String label) {
            this.label = label;
        }

        public String toString() {
            return label;
        }
    }

    private BufferedImage image;
    private String name;
    private boolean visible = true;
    private float opacity = 1.0f;
    private BlendMode blend = BlendMode.NORMAL;

    public PaintLayer(String name, int width, int height) {
        this.name = name;
        this.image = newImage(width, height);
    }

    private PaintLayer(String name, BufferedImage image) {
        this.name = name;
        this.image = image;
    }

    /** A blank, fully transparent ARGB image of the given size. */
    public static BufferedImage newImage(int width, int height) {
        return new BufferedImage(Math.max(1, width), Math.max(1, height),
                BufferedImage.TYPE_INT_ARGB);
    }

    public BufferedImage getImage() {
        return image;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /** Layer opacity in [0,1]; 1 is fully opaque. */
    public float getOpacity() {
        return opacity;
    }

    public void setOpacity(float opacity) {
        this.opacity = Math.max(0.0f, Math.min(1.0f, opacity));
    }

    public BlendMode getBlend() {
        return blend;
    }

    public void setBlend(BlendMode blend) {
        this.blend = (blend == null) ? BlendMode.NORMAL : blend;
    }

    public int getWidth() {
        return image.getWidth();
    }

    public int getHeight() {
        return image.getHeight();
    }

    /**
     * Returns a deep copy of this layer (its own pixel buffer) so it can be
     * duplicated or snapshotted for undo without aliasing the original.
     */
    public PaintLayer copy() {
        return copy(name);
    }

    public PaintLayer copy(String newName) {
        BufferedImage dup = newImage(image.getWidth(), image.getHeight());
        Graphics2D g = dup.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        PaintLayer l = new PaintLayer(newName, dup);
        l.visible = visible;
        l.opacity = opacity;
        l.blend = blend;
        return l;
    }

    /** An independent pixel snapshot of this layer's image (for undo). */
    public BufferedImage snapshot() {
        BufferedImage snap = newImage(image.getWidth(), image.getHeight());
        Graphics2D g = snap.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return snap;
    }

    /** Replaces this layer's pixels with a previously taken snapshot. */
    public void restore(BufferedImage snapshot) {
        BufferedImage copy = newImage(snapshot.getWidth(), snapshot.getHeight());
        Graphics2D g = copy.createGraphics();
        g.drawImage(snapshot, 0, 0, null);
        g.dispose();
        this.image = copy;
    }
}
