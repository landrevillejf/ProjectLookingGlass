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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link ImageEditorPanel}'s construction and headless editing paths:
 * the default document, tool switching, a filter pushing an undo snapshot, and a
 * save / load round-trip through {@code ImageIO}. No file dialog or colour
 * chooser is opened, so this runs in CI.
 */
class ImageEditorPanelTest {

    @Test
    @DisplayName("a fresh panel opens a default document, ready and idle")
    void defaultsAreIdle() {
        ImageEditorPanel panel = new ImageEditorPanel();
        assertEquals("Ready", panel.statusText());
        assertEquals(1, panel.document().layerCount());
        assertEquals(ImageEditorPanel.DEFAULT_W, panel.document().getWidth());
        assertEquals(ToolEngine.Tool.BRUSH, panel.tool());
        assertFalse(panel.undoStack().canUndo());
    }

    @Test
    @DisplayName("setTool changes the active tool and null is safe")
    void toolSwitch() {
        ImageEditorPanel panel = new ImageEditorPanel();
        panel.setTool(ToolEngine.Tool.ELLIPSE);
        assertEquals(ToolEngine.Tool.ELLIPSE, panel.tool());
        panel.setTool(null);
        assertEquals(ToolEngine.Tool.BRUSH, panel.tool());
    }

    @Test
    @DisplayName("applying a filter snapshots the document for undo")
    void filterPushesUndo() {
        ImageEditorPanel panel = new ImageEditorPanel();
        ToolEngine.fill(panel.document().getActiveLayer().getImage(), Color.RED);
        assertFalse(panel.undoStack().canUndo());
        panel.applyFilter("grayscale");
        assertTrue(panel.undoStack().canUndo());
        assertEquals("Applied grayscale", panel.statusText());
    }

    @Test
    @DisplayName("an unknown filter is ignored without leaving a stray snapshot")
    void unknownFilterIsIgnored() {
        ImageEditorPanel panel = new ImageEditorPanel();
        panel.applyFilter("no-such-filter");
        assertFalse(panel.undoStack().canUndo());
    }

    @Test
    @DisplayName("a document saves to PNG and loads back at the same size")
    void saveLoadRoundTrip(@TempDir Path dir) {
        ImageEditorPanel panel = new ImageEditorPanel();
        File out = dir.resolve("art.png").toFile();
        assertTrue(panel.saveImage(out));
        assertTrue(out.length() > 0);

        ImageEditorPanel reloaded = new ImageEditorPanel();
        reloaded.loadImage(out);
        assertEquals(ImageEditorPanel.DEFAULT_W, reloaded.document().getWidth());
        assertEquals(ImageEditorPanel.DEFAULT_H, reloaded.document().getHeight());
    }

    @Test
    @DisplayName("saving to null is a safe false, not a crash")
    void saveNullIsSafe() {
        ImageEditorPanel panel = new ImageEditorPanel();
        assertFalse(panel.saveImage(null));
    }
}
