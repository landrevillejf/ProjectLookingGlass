package org.jdesktop.lg3d.apps.presentoire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the 2D face of Presentoire: the headless {@link SlideRenderer} (a slide
 * rasterises to a white page carrying the deck's own dark ink, a null slide and a
 * missing image degrade to a blank page, and the side is clamped to a sane
 * minimum) and {@link PresentoirePanel} (it builds headlessly on the demo deck,
 * navigates and zooms within their clamps, reports the demo on its status line,
 * and rejects a null or non-deck file without losing the open document). Every
 * case runs with {@code java.awt.headless=true} - a {@code BufferedImage}, a
 * {@code JPanel} and a {@code JFileChooser}'s model all need no display.
 */
class PresentoirePanelTest {

    // ------------------------------------------------------------------
    // SlideRenderer
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a slide rasterises to a white page carrying dark ink")
    void rendersSlideToPage() {
        try (PresentoireModel model = PresentoireModel.demo()) {
            int side = 400;
            BufferedImage image = SlideRenderer.render(model.getSlide(0), model, side);
            assertNotNull(image);
            assertEquals(side, image.getWidth());
            assertEquals(side, image.getHeight());

            int white = 0;
            int dark = 0;
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    int argb = image.getRGB(x, y);
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    if (r > 240 && g > 240 && b > 240) {
                        white++;
                    } else if (r < 120 && g < 120 && b < 120) {
                        dark++;
                    }
                }
            }
            // The page is overwhelmingly white, and the demo's dark text drew ink.
            assertTrue(white > side * side / 2, "the page must be mostly white");
            assertTrue(dark > 0, "the demo slide's dark text must rasterise");
        }
    }

    @Test
    @DisplayName("a null slide and a missing image both degrade to a blank page")
    void blankPageDegradesGracefully() {
        try (PresentoireModel model = PresentoireModel.demo()) {
            BufferedImage blank = SlideRenderer.render(null, model, 128);
            assertNotNull(blank);
            assertEquals(0xFFFFFFFF, blank.getRGB(64, 64), "an empty page is white");

            // The demo has no archive, so an <img> resolves to nothing and the
            // page stays white rather than throwing.
            BufferedImage noImage = SlideRenderer.render(model.getSlide(0), model, 128);
            assertNotNull(noImage);
        }
    }

    @Test
    @DisplayName("the render side is clamped to a sane minimum")
    void sideIsClamped() {
        try (PresentoireModel model = PresentoireModel.demo()) {
            BufferedImage tiny = SlideRenderer.render(model.getSlide(0), model, 0);
            assertNotNull(tiny);
            assertTrue(tiny.getWidth() >= 16, "a sub-minimum side is clamped up");
        }
    }

    // ------------------------------------------------------------------
    // PresentoirePanel construction
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the panel builds headlessly on the built-in demo deck")
    void constructionSmokeTest() {
        PresentoirePanel panel = new PresentoirePanel();
        assertEquals(3, panel.slideCount(), "the demo deck has three slides");
        assertEquals(0, panel.currentSlide());
        assertEquals(1.0f, panel.zoom(), 1e-6f);
        assertNotNull(panel.getPreferredSize());
        assertEquals(PresentoirePanel.WIDTH_PX, panel.getPreferredSize().width);
        assertEquals(PresentoirePanel.HEIGHT_PX, panel.getPreferredSize().height);
        // The first slide is rasterised into the view label's icon.
        assertNotNull(panel.slideIcon());
        // The status line names the demo deck (it holds no archive).
        assertFalse(panel.model().hasArchive());
        assertTrue(panel.statusText().contains("built-in demo deck"), panel.statusText());
        assertTrue(panel.statusText().contains("Slide 1 of 3"), panel.statusText());
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("showSlide moves within 1..N and clamps at both ends")
    void navigationClamps() {
        PresentoirePanel panel = new PresentoirePanel();
        panel.showSlide(2);
        assertEquals(1, panel.currentSlide());
        panel.showSlide(3);
        assertEquals(2, panel.currentSlide());

        // Past the last slide clamps to the last; below the first clamps to 0.
        panel.showSlide(99);
        assertEquals(2, panel.currentSlide());
        panel.showSlide(-5);
        assertEquals(0, panel.currentSlide());
        assertTrue(panel.statusText().contains("Slide 1 of 3"), panel.statusText());
    }

    // ------------------------------------------------------------------
    // Zoom
    // ------------------------------------------------------------------

    @Test
    @DisplayName("setZoom clamps to the min/max range and updates the readout")
    void zoomClamps() {
        PresentoirePanel panel = new PresentoirePanel();
        panel.setZoom(2.0f);
        assertEquals(2.0f, panel.zoom(), 1e-6f);
        assertTrue(panel.statusText().contains("200%"), panel.statusText());

        panel.setZoom(1000f);
        assertEquals(4.0f, panel.zoom(), 1e-6f, "clamped to MAX_ZOOM");
        panel.setZoom(-1f);
        assertEquals(0.20f, panel.zoom(), 1e-6f, "clamped to MIN_ZOOM");
    }

    // ------------------------------------------------------------------
    // Opening a document
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a null or non-deck file is rejected and the open deck survives")
    void openRejectsBadInput(@TempDir File dir) throws IOException {
        PresentoirePanel panel = new PresentoirePanel();
        int before = panel.slideCount();

        assertFalse(panel.openFile(null));
        assertEquals("Could not open the file", panel.statusText());
        assertEquals(before, panel.slideCount(), "the demo deck stays open");

        File notADeck = new File(dir, "not-a-deck.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(notADeck.toPath()))) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("no content.xml here".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertFalse(panel.openFile(notADeck));
        assertEquals("Could not open not-a-deck.zip", panel.statusText());
        assertEquals(before, panel.slideCount(), "the demo deck still stays open");
    }

    @Test
    @DisplayName("opening a real deck replaces the demo and resets to slide one")
    void openReplacesDocument(@TempDir File dir) throws IOException {
        File deck = new File(dir, "deck.zip");
        String content = """
                <document>
                  <slide id="a"><text width="0.1" height="0.02" x="-0.05" y="0" color="#000000">A</text></slide>
                  <slide id="b"><text width="0.1" height="0.02" x="-0.05" y="0" color="#000000">B</text></slide>
                </document>
                """;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(deck.toPath()))) {
            zip.putNextEntry(new ZipEntry("content.xml"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        PresentoirePanel panel = new PresentoirePanel();
        panel.showSlide(3);           // move off slide one first
        assertTrue(panel.openFile(deck));
        assertEquals(2, panel.slideCount());
        assertEquals(0, panel.currentSlide(), "a new deck resets to the first slide");
        assertEquals(1.0f, panel.zoom(), 1e-6f, "and resets the zoom");
        assertTrue(panel.model().hasArchive());
        // A real archive drops the "built-in demo deck" suffix.
        assertFalse(panel.statusText().contains("built-in demo deck"), panel.statusText());
        assertTrue(panel.statusText().contains("Slide 1 of 2"), panel.statusText());
        panel.model().close();
    }

    // ------------------------------------------------------------------
    // Empty deck handling
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a deck with no slides disables navigation and says so")
    void emptyDeckIsHandled(@TempDir File dir) throws IOException {
        File empty = new File(dir, "empty.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(empty.toPath()))) {
            zip.putNextEntry(new ZipEntry("content.xml"));
            zip.write("<document></document>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        PresentoirePanel panel = new PresentoirePanel();
        assertTrue(panel.openFile(empty));
        assertEquals(0, panel.slideCount());
        assertNull(panel.slideIcon(), "an empty deck renders no slide icon");
        assertTrue(panel.statusText().contains("no slides"), panel.statusText());
        panel.model().close();
    }
}
