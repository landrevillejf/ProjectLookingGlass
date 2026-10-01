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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link ImageLayer} bean: name fallback, opacity clamping, the
 * blank-layer factory and the deep-copy independence. {@link BufferedImage}
 * works headless, so this runs in CI.
 */
class ImageLayerTest {

    @Test
    @DisplayName("a blank name falls back to 'Layer'")
    void nameFallback() {
        assertEquals("Layer", new ImageLayer("  ", 4, 4).getName());
        assertEquals("Layer", new ImageLayer(null, 4, 4).getName());
        assertEquals("Ink", new ImageLayer("Ink", 4, 4).getName());
    }

    @Test
    @DisplayName("opacity is clamped to 0..1")
    void opacityClamped() {
        ImageLayer layer = new ImageLayer("x", 4, 4);
        layer.setOpacity(5f);
        assertEquals(1f, layer.getOpacity());
        layer.setOpacity(-3f);
        assertEquals(0f, layer.getOpacity());
    }

    @Test
    @DisplayName("a blank layer starts visible with the requested size")
    void blankLayer() {
        ImageLayer layer = new ImageLayer("bg", 7, 9);
        assertTrue(layer.isVisible());
        assertEquals(7, layer.getWidth());
        assertEquals(9, layer.getHeight());
        assertEquals(0, layer.getImage().getRGB(0, 0), "starts fully transparent");
    }

    @Test
    @DisplayName("copy is deep: a distinct image with the same content")
    void copyIsDeep() {
        ImageLayer layer = new ImageLayer("ink", 4, 4);
        ToolEngine.fill(layer.getImage(), Color.RED);
        layer.setOpacity(0.5f);
        layer.setVisible(false);
        ImageLayer clone = layer.copy();
        assertNotSame(layer.getImage(), clone.getImage());
        assertEquals(0.5f, clone.getOpacity());
        assertFalse(clone.isVisible());
        ToolEngine.fill(clone.getImage(), Color.BLUE);
        assertEquals(Color.RED.getRGB(), layer.getImage().getRGB(0, 0),
                "the original is untouched by the clone's edit");
    }

    @Test
    @DisplayName("a null image is rejected")
    void nullImageRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ImageLayer("x", (BufferedImage) null));
    }
}
