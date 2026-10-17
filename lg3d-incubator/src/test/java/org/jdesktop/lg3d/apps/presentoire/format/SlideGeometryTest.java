package org.jdesktop.lg3d.apps.presentoire.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SlideGeometry}, the fit-inside, aspect-preserving, centred
 * transform between an office page and Presentoire's square {@code -0.1 .. 0.1}
 * slide. The export methods are the exact inverse of the import methods, so an
 * import-then-export round trip returns the original page geometry; a wide page
 * letterboxes top and bottom and a tall page pillarboxes left and right, never a
 * stretch. Pure float math, so trivially headless.
 */
class SlideGeometryTest {

    private static final double EPS = 1e-2;

    @Test
    @DisplayName("the span and half constants match the slide the renderers use")
    void constants() {
        assertEquals(0.2f, SlideGeometry.SPAN, 1e-6f);
        assertEquals(0.1f, SlideGeometry.HALF, 1e-6f);
        assertEquals(720d, SlideGeometry.PPT_PAGE_WIDTH_PT);
        assertEquals(540d, SlideGeometry.PPT_PAGE_HEIGHT_PT);
        assertEquals(28.0d, SlideGeometry.ODP_PAGE_WIDTH_CM);
        assertEquals(21.0d, SlideGeometry.ODP_PAGE_HEIGHT_CM);
    }

    @Test
    @DisplayName("export undoes import exactly, for both text and image boxes")
    void importExportAreInverses() {
        SlideGeometry geo = new SlideGeometry(720, 540);

        SlideGeometry.Box textBox = geo.importTextBox(72, 54, 576, 80);
        SlideGeometry.Box backText = geo.exportTextBox(
                textBox.x(), textBox.y(), textBox.width(), textBox.height());
        assertEquals(72, backText.x(), EPS);
        assertEquals(54, backText.y(), EPS);
        assertEquals(576, backText.width(), EPS);
        assertEquals(80, backText.height(), EPS);

        SlideGeometry.Box imageBox = geo.importImageBox(100, 400, 120, 90);
        SlideGeometry.Box backImage = geo.exportImageBox(
                imageBox.x(), imageBox.y(), imageBox.width(), imageBox.height());
        assertEquals(100, backImage.x(), EPS);
        assertEquals(400, backImage.y(), EPS);
        assertEquals(120, backImage.width(), EPS);
        assertEquals(90, backImage.height(), EPS);
    }

    @Test
    @DisplayName("a wide page fills the slide horizontally and centres vertically")
    void widePageLetterboxes() {
        SlideGeometry geo = new SlideGeometry(720, 540);
        assertEquals(0.2 / 720, geo.scale(), 1e-9);

        SlideGeometry.Box full = geo.importTextBox(0, 0, 720, 540);
        assertEquals(-0.1f, full.x(), 1e-3f, "spans the full width");
        assertEquals(0.1f, full.x() + full.width(), 1e-3f);
        // Letter-boxed: the shorter axis is centred, so it stays inside the slide.
        assertTrue(full.y() > -0.1f, "centred vertically, not touching the bottom");
        assertTrue(full.y() + full.height() < 0.1f, "nor the top");
    }

    @Test
    @DisplayName("a tall page fills the slide vertically and centres horizontally")
    void tallPagePillarboxes() {
        SlideGeometry geo = new SlideGeometry(540, 720);
        SlideGeometry.Box full = geo.importTextBox(0, 0, 540, 720);
        assertEquals(-0.1f, full.y(), 1e-3f, "spans the full height");
        assertEquals(0.1f, full.y() + full.height(), 1e-3f);
        assertTrue(full.x() > -0.1f, "pillar-boxed: centred horizontally");
        assertTrue(full.x() + full.width() < 0.1f);
    }

    @Test
    @DisplayName("a square page maps onto the whole slide with no offset")
    void squarePageFillsExactly() {
        SlideGeometry geo = new SlideGeometry(100, 100);
        assertEquals(0.2 / 100, geo.scale(), 1e-9);
        SlideGeometry.Box full = geo.importTextBox(0, 0, 100, 100);
        assertEquals(-0.1f, full.x(), 1e-3f);
        assertEquals(-0.1f, full.y(), 1e-3f);
        assertEquals(0.1f, full.x() + full.width(), 1e-3f);
        assertEquals(0.1f, full.y() + full.height(), 1e-3f);
    }

    @Test
    @DisplayName("a degenerate zero-size page does not divide by zero")
    void degeneratePageIsSafe() {
        SlideGeometry geo = new SlideGeometry(0, 0);
        assertTrue(geo.scale() > 0, "the scale falls back to a sane value");
        SlideGeometry.Box box = geo.importTextBox(0, 0, 10, 10);
        assertTrue(Float.isFinite(box.x()) && Float.isFinite(box.y()));
        assertTrue(Float.isFinite(box.width()) && Float.isFinite(box.height()));
    }

    @Test
    @DisplayName("any in-page rect maps inside the slide, for the ODP page too")
    void odpPageStaysWithinSlide() {
        SlideGeometry geo = new SlideGeometry(
                SlideGeometry.ODP_PAGE_WIDTH_CM, SlideGeometry.ODP_PAGE_HEIGHT_CM);
        SlideGeometry.Box box = geo.importTextBox(2, 2, 24, 17);
        DeckFixtures.assertTextWithinSlide(new org.jdesktop.lg3d.apps.presentoire.model.SlideElement.Text(
                box.x(), box.y(), 0.01f, box.width(), box.height(), 0, "x"));
    }
}
