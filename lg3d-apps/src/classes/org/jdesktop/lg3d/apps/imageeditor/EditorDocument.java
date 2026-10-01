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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * The image editor's document model: a fixed-size canvas and an ordered stack of
 * {@link ImageLayer}s (index 0 is the bottom). It composites the visible layers
 * to a single image for display and export, and manages the active layer and
 * layer reordering. The class is pure AWT (no Swing, no Java 3D), so it builds
 * and is asserted on headless.
 */
public class EditorDocument {

    private final int width;
    private final int height;
    private final List<ImageLayer> layers = new ArrayList<>();
    private int activeIndex = -1;
    private Color background = Color.WHITE;

    /**
     * Builds a document with a single blank white layer.
     *
     * @param width  canvas width in pixels (at least 1)
     * @param height canvas height in pixels (at least 1)
     */
    public EditorDocument(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        BufferedImage base = new BufferedImage(this.width, this.height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = base.createGraphics();
        g.setColor(background);
        g.fillRect(0, 0, this.width, this.height);
        g.dispose();
        layers.add(new ImageLayer("Background", base));
        activeIndex = 0;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public Color getBackground() {
        return background;
    }

    public void setBackground(Color background) {
        this.background = (background == null) ? Color.WHITE : background;
    }

    // ------------------------------------------------------------------
    // Layers
    // ------------------------------------------------------------------

    /** The number of layers. */
    public int layerCount() {
        return layers.size();
    }

    /** The layer at {@code i}, or null when out of range. */
    public ImageLayer getLayer(int i) {
        return (i < 0 || i >= layers.size()) ? null : layers.get(i);
    }

    /** An unmodifiable view of the layers, bottom to top. */
    public List<ImageLayer> layers() {
        return List.copyOf(layers);
    }

    /** Adds a layer on top and makes it active. */
    public void addLayer(ImageLayer layer) {
        if (layer != null) {
            layers.add(layer);
            activeIndex = layers.size() - 1;
        }
    }

    /** Adds a new blank transparent layer of the canvas size on top. */
    public ImageLayer addLayer(String name) {
        ImageLayer layer = new ImageLayer(name, width, height);
        addLayer(layer);
        return layer;
    }

    /**
     * Removes the layer at {@code i} (never the last one), keeping the active
     * index sane.
     *
     * @return true if a layer was removed
     */
    public boolean removeLayer(int i) {
        if (i < 0 || i >= layers.size() || layers.size() <= 1) {
            return false;
        }
        layers.remove(i);
        if (activeIndex >= layers.size()) {
            activeIndex = layers.size() - 1;
        } else if (activeIndex > i) {
            activeIndex--;
        }
        return true;
    }

    /** The active layer index, or -1 when there are none. */
    public int getActiveIndex() {
        return activeIndex;
    }

    /** Selects the active layer (clamped), returning it. */
    public ImageLayer setActiveIndex(int i) {
        if (layers.isEmpty()) {
            activeIndex = -1;
            return null;
        }
        activeIndex = Math.max(0, Math.min(i, layers.size() - 1));
        return layers.get(activeIndex);
    }

    /** The active layer, or null when there are none. */
    public ImageLayer getActiveLayer() {
        return getLayer(activeIndex);
    }

    /**
     * Swaps the layer at {@code i} with the one above it.
     *
     * @return true if the layer moved
     */
    public boolean moveUp(int i) {
        if (i < 0 || i >= layers.size() - 1) {
            return false;
        }
        swap(i, i + 1);
        if (activeIndex == i) {
            activeIndex = i + 1;
        } else if (activeIndex == i + 1) {
            activeIndex = i;
        }
        return true;
    }

    /**
     * Swaps the layer at {@code i} with the one below it.
     *
     * @return true if the layer moved
     */
    public boolean moveDown(int i) {
        if (i <= 0 || i >= layers.size()) {
            return false;
        }
        swap(i, i - 1);
        if (activeIndex == i) {
            activeIndex = i - 1;
        } else if (activeIndex == i - 1) {
            activeIndex = i;
        }
        return true;
    }

    private void swap(int a, int b) {
        ImageLayer tmp = layers.get(a);
        layers.set(a, layers.get(b));
        layers.set(b, tmp);
    }

    // ------------------------------------------------------------------
    // Compositing
    // ------------------------------------------------------------------

    /**
     * Composites every visible layer, bottom to top, honouring each layer's
     * opacity, over the background colour.
     *
     * @return a new RGB image of the canvas size
     */
    public BufferedImage composite() {
        BufferedImage out = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setColor(background);
        g.fillRect(0, 0, width, height);
        for (ImageLayer layer : layers) {
            if (!layer.isVisible() || layer.getOpacity() <= 0f) {
                continue;
            }
            g.setComposite(AlphaComposite.getInstance(
                    AlphaComposite.SRC_OVER, Math.min(1f, layer.getOpacity())));
            g.drawImage(layer.getImage(), 0, 0, null);
        }
        g.setComposite(AlphaComposite.SrcOver);
        g.dispose();
        return out;
    }

    /**
     * A deep copy of this document (all layers cloned), used for undo snapshots.
     *
     * @return an independent document with the same content
     */
    public EditorDocument copy() {
        EditorDocument doc = new EditorDocument(width, height);
        doc.layers.clear();
        for (ImageLayer layer : layers) {
            doc.layers.add(layer.copy());
        }
        doc.activeIndex = activeIndex;
        doc.background = background;
        return doc;
    }
}
