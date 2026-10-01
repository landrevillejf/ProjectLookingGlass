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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link EditorDocument} layer model: the initial background layer,
 * add / remove / reorder, active-layer bookkeeping and compositing. Uses
 * {@link BufferedImage} only, so it runs headless.
 */
class EditorDocumentTest {

    @Test
    @DisplayName("a new document has one white background layer")
    void startsWithBackground() {
        EditorDocument doc = new EditorDocument(20, 10);
        assertEquals(1, doc.layerCount());
        assertEquals(0, doc.getActiveIndex());
        assertEquals("Background", doc.getActiveLayer().getName());
        assertEquals(20, doc.getWidth());
        assertEquals(10, doc.getHeight());
        // The background layer is filled white.
        assertEquals(Color.WHITE.getRGB(), doc.getActiveLayer().getImage().getRGB(0, 0));
    }

    @Test
    @DisplayName("addLayer appends on top and makes it active")
    void addLayer() {
        EditorDocument doc = new EditorDocument(8, 8);
        ImageLayer top = doc.addLayer("Ink");
        assertEquals(2, doc.layerCount());
        assertEquals(1, doc.getActiveIndex());
        assertEquals(top, doc.getActiveLayer());
    }

    @Test
    @DisplayName("the last layer cannot be removed and the cursor stays sane")
    void removeLayer() {
        EditorDocument doc = new EditorDocument(8, 8);
        assertFalse(doc.removeLayer(0), "cannot remove the only layer");
        doc.addLayer("A");
        doc.addLayer("B");
        assertEquals(3, doc.layerCount());
        assertTrue(doc.removeLayer(2));
        assertEquals(2, doc.layerCount());
        assertEquals(1, doc.getActiveIndex());
    }

    @Test
    @DisplayName("moveUp / moveDown reorder and track the active layer")
    void reorder() {
        EditorDocument doc = new EditorDocument(4, 4);
        doc.addLayer("A"); // [Background, A]
        doc.addLayer("B"); // [Background, A, B]
        doc.setActiveIndex(0);
        // Move the bottom layer up one: it swaps with A and the cursor follows.
        assertTrue(doc.moveUp(0));
        assertEquals("Background", doc.getLayer(1).getName());
        assertEquals("A", doc.getLayer(0).getName());
        assertEquals(1, doc.getActiveIndex(), "the active layer followed the move");
        // Move it back down.
        assertTrue(doc.moveDown(1));
        assertEquals("Background", doc.getLayer(0).getName());
        assertEquals(0, doc.getActiveIndex());
        assertFalse(doc.moveDown(0), "already at the bottom");
        assertFalse(doc.moveUp(2), "already at the top");
    }

    @Test
    @DisplayName("composite honours visibility")
    void compositeVisibility() {
        EditorDocument doc = new EditorDocument(4, 4);
        ImageLayer ink = doc.addLayer("Ink");
        ToolEngine.fill(ink.getImage(), Color.RED);
        // Opaque red on top of the white background.
        assertEquals(0xffff0000, doc.composite().getRGB(0, 0));
        ink.setVisible(false);
        // Hidden, only the white background shows through.
        assertEquals(0xffffffff, doc.composite().getRGB(0, 0));
    }

    @Test
    @DisplayName("copy is a deep, independent clone")
    void copyIsIndependent() {
        EditorDocument doc = new EditorDocument(4, 4);
        ToolEngine.fill(doc.getActiveLayer().getImage(), Color.BLUE);
        EditorDocument clone = doc.copy();
        ToolEngine.fill(clone.getActiveLayer().getImage(), Color.GREEN);
        // The original is untouched by the clone's edit.
        assertEquals(Color.BLUE.getRGB(), doc.getActiveLayer().getImage().getRGB(0, 0));
        assertEquals(Color.GREEN.getRGB(), clone.getActiveLayer().getImage().getRGB(0, 0));
        assertNotNull(clone.composite());
    }
}
