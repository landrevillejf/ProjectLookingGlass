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
package org.jdesktop.lg3d.apps.imageeditor;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * One editable layer of an {@link EditorDocument}: a name, an RGBA image the
 * tools paint into, a visibility flag and an opacity (0..1). Layers are ordered
 * bottom-to-top in the document and composited by {@link EditorDocument}.
 *
 * <p>The class holds no Swing or Java 3D types, so it constructs and is asserted
 * on headless; a {@link BufferedImage} and its {@link Graphics2D} work fine
 * without a display.</p>
 */
public class ImageLayer {

    private String name;
    private final BufferedImage image;
    private boolean visible = true;
    private float opacity = 1f;

    /**
     * Builds a layer over an existing image (adopted, not copied).
     *
     * @param name  the layer name (blank falls back to "Layer")
     * @param image the RGBA image this layer paints into
     */
    public ImageLayer(String name, BufferedImage image) {
        if (image == null) {
            throw new IllegalArgumentException("image must not be null");
        }
        this.name = (name == null || name.isBlank()) ? "Layer" : name;
        this.image = image;
    }

    /**
     * Builds a blank, fully transparent layer of the given size.
     *
     * @param name   the layer name
     * @param width  pixel width (at least 1)
     * @param height pixel height (at least 1)
     */
    public ImageLayer(String name, int width, int height) {
        this(name, new BufferedImage(Math.max(1, width), Math.max(1, height),
                BufferedImage.TYPE_INT_ARGB));
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = (name == null || name.isBlank()) ? "Layer" : name;
    }

    public BufferedImage getImage() {
        return image;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    /** @return the opacity, 0..1. */
    public float getOpacity() {
        return opacity;
    }

    public void setOpacity(float opacity) {
        this.opacity = Math.max(0f, Math.min(1f, opacity));
    }

    public int getWidth() {
        return image.getWidth();
    }

    public int getHeight() {
        return image.getHeight();
    }

    /**
     * A deep copy: a new layer with a cloned image, so an undo snapshot is
     * unaffected by later painting.
     *
     * @return an independent copy of this layer
     */
    public ImageLayer copy() {
        BufferedImage clone = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = clone.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        ImageLayer layer = new ImageLayer(name, clone);
        layer.visible = visible;
        layer.opacity = opacity;
        return layer;
    }

    @Override
    public String toString() {
        return name;
    }
}
