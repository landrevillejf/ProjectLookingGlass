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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless coverage of {@link SwingCompositedWindowSink} — the 2D sink that
 * paints a redirected client window's damage regions into a Swing canvas so a
 * native X11 application can be hosted <em>inside</em> the 2D desktop.
 *
 * <p>A {@code JPanel} constructs and a {@code Graphics2D} blit runs under
 * {@code java.awt.headless=true}, so the canvas accumulation, offset placement,
 * resize-preserve, dispose and degenerate-geometry behaviour are all exercised
 * with no live {@code gnu.x11.Display} and no Java 3D.</p>
 */
class SwingCompositedWindowSinkTest {

    /** A width&times;height image filled with a single ARGB colour. */
    private static BufferedImage solid(int w, int h, int argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, argb);
            }
        }
        return img;
    }

    @Test
    @DisplayName("the sink exposes a non-null Swing component for hosting")
    void exposesComponent() {
        SwingCompositedWindowSink sink = new SwingCompositedWindowSink(8, 6);
        assertNotNull(sink.getComponent());
        assertEquals(8, sink.getComponent().getPreferredSize().width);
        assertEquals(6, sink.getComponent().getPreferredSize().height);
    }

    @Test
    @DisplayName("a presented region is blitted at its window offset")
    void blitsRegionAtOffset() {
        SwingCompositedWindowSink sink = new SwingCompositedWindowSink(4, 4);
        sink.present(solid(2, 2, 0xff112233), 1, 1, 2, 2);

        BufferedImage canvas = sink.canvasSnapshot();
        assertNotNull(canvas);
        assertEquals(4, canvas.getWidth());
        assertEquals(4, canvas.getHeight());
        // Untouched pixels stay black; the 2x2 region lands at (1,1)..(2,2).
        assertEquals(0xff000000, canvas.getRGB(0, 0));
        assertEquals(0xff112233, canvas.getRGB(1, 1));
        assertEquals(0xff112233, canvas.getRGB(2, 2));
        assertEquals(0xff000000, canvas.getRGB(3, 3));
    }

    @Test
    @DisplayName("successive presents accumulate into the same canvas")
    void accumulatesPresents() {
        SwingCompositedWindowSink sink = new SwingCompositedWindowSink(4, 1);
        sink.present(solid(1, 1, 0xffAA0000), 0, 0, 1, 1);
        sink.present(solid(1, 1, 0xff00BB00), 2, 0, 1, 1);

        BufferedImage canvas = sink.canvasSnapshot();
        assertEquals(0xffAA0000, canvas.getRGB(0, 0));
        assertEquals(0xff000000, canvas.getRGB(1, 0));
        assertEquals(0xff00BB00, canvas.getRGB(2, 0));
    }

    @Test
    @DisplayName("resize reallocates the canvas, preserving the top-left overlap")
    void resizedPreservesOverlap() {
        SwingCompositedWindowSink sink = new SwingCompositedWindowSink(2, 2);
        sink.present(solid(2, 2, 0xff123456), 0, 0, 2, 2);

        sink.resized(4, 3);

        BufferedImage canvas = sink.canvasSnapshot();
        assertEquals(4, canvas.getWidth());
        assertEquals(3, canvas.getHeight());
        // The old 2x2 top-left content survives; the new area is black.
        assertEquals(0xff123456, canvas.getRGB(0, 0));
        assertEquals(0xff123456, canvas.getRGB(1, 1));
        assertEquals(0xff000000, canvas.getRGB(3, 2));
        assertEquals(4, sink.getComponent().getPreferredSize().width);
    }

    @Test
    @DisplayName("after dispose the canvas is gone and presents are ignored")
    void disposeClearsCanvas() {
        SwingCompositedWindowSink sink = new SwingCompositedWindowSink(2, 2);
        sink.present(solid(2, 2, 0xffFFFFFF), 0, 0, 2, 2);
        assertNotNull(sink.canvasSnapshot());

        sink.dispose();
        assertNull(sink.canvasSnapshot());

        // A present after dispose must not resurrect the canvas or throw.
        sink.present(solid(1, 1, 0xff00FF00), 0, 0, 1, 1);
        assertNull(sink.canvasSnapshot());
    }

    @Test
    @DisplayName("null/empty presents and non-positive resizes are no-ops")
    void ignoresDegenerateInput() {
        SwingCompositedWindowSink sink = new SwingCompositedWindowSink(2, 2);

        sink.present(null, 0, 0, 1, 1);
        sink.present(solid(1, 1, 0xff00FF00), 0, 0, 0, 1);
        sink.present(solid(1, 1, 0xff00FF00), 0, 0, 1, -1);
        sink.resized(0, 0);
        sink.resized(-4, 2);

        BufferedImage canvas = sink.canvasSnapshot();
        assertNotNull(canvas);
        assertEquals(2, canvas.getWidth()); // unchanged by the no-op resizes
        assertEquals(2, canvas.getHeight());
        assertEquals(0xff000000, canvas.getRGB(0, 0)); // nothing blitted
    }

    @Test
    @DisplayName("degenerate construction sizes are clamped to a 1x1 canvas")
    void clampsDegenerateConstruction() {
        SwingCompositedWindowSink sink = new SwingCompositedWindowSink(0, -5);
        assertNotNull(sink.getComponent());
        assertEquals(1, sink.getComponent().getPreferredSize().width);
        assertEquals(1, sink.getComponent().getPreferredSize().height);
        // A present into the clamped canvas must not throw.
        sink.present(solid(1, 1, 0xff010203), 0, 0, 1, 1);
        assertEquals(0xff010203, sink.canvasSnapshot().getRGB(0, 0));
    }

    @Test
    @DisplayName("paintComponent draws the canvas into a supplied Graphics2D")
    void paintsCanvasIntoGraphics() {
        SwingCompositedWindowSink sink = new SwingCompositedWindowSink(2, 2);
        sink.present(solid(2, 2, 0xff224466), 0, 0, 2, 2);

        BufferedImage target = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = target.createGraphics();
        try {
            // Give the panel real bounds (it has no peer headlessly) and drive
            // its paint path into the supplied Graphics2D.
            sink.getComponent().setSize(2, 2);
            sink.getComponent().paint(g);
        } finally {
            g.dispose();
        }
        assertEquals(0xff224466, target.getRGB(0, 0));
        assertEquals(0xff224466, target.getRGB(1, 1));
    }
}
