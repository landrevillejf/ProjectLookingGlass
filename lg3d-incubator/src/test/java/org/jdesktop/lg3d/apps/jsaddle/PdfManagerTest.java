package org.jdesktop.lg3d.apps.jsaddle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link PdfManager}, the adapter that backs the jsaddle PDF
 * viewer. They prove the JPedal to Apache PDFBox rewrite renders real pages with
 * no X display: the bundled {@code jsaddle_usage_en.pdf} is decoded and its pages
 * are rasterised to {@link BufferedImage}s, exactly the contract the 3D viewer
 * ({@code JSaddleManager} / {@code ViewerContainer}) consumes. Page numbers are
 * 1-based, matching the original implementation.
 */
class PdfManagerTest {

    private static final String SAMPLE =
        "org/jdesktop/lg3d/apps/jsaddle/resources/jsaddle_usage_en.pdf";

    @Test
    @DisplayName("a fresh manager with no document reports zero pages and null images")
    void emptyManager() {
        PdfManager manager = new PdfManager(800, 600);
        assertEquals(0, manager.getLastPageNumber());
        assertNull(manager.getImage());
        assertNull(manager.getImage(1));
        assertNull(manager.getThumbnailImage(1));
        assertEquals(1, manager.getCurrentPageNumber());
    }

    @Test
    @DisplayName("decodeResourceURL loads the bundled PDF and reports its page count")
    void decodeResource() {
        PdfManager manager = new PdfManager(1024, 768);
        manager.decodeResourceURL(SAMPLE);
        assertTrue(manager.getLastPageNumber() > 0,
            "the bundled sample PDF must have at least one page");
    }

    @Test
    @DisplayName("getImage rasterises the current page to a non-empty image")
    void renderPage() {
        PdfManager manager = new PdfManager(1024, 768);
        manager.decodeResourceURL(SAMPLE);

        BufferedImage image = manager.getImage(1);
        assertNotNull(image, "page 1 must render");
        assertTrue(image.getWidth() > 0);
        assertTrue(image.getHeight() > 0);
    }

    @Test
    @DisplayName("getThumbnailImage renders a smaller page image")
    void renderThumbnail() {
        PdfManager manager = new PdfManager(1024, 768);
        manager.decodeResourceURL(SAMPLE);

        BufferedImage thumb = manager.getThumbnailImage(1, 256, 256);
        assertNotNull(thumb, "thumbnail 1 must render");
        // PDFBox sizes the raster from the float crop box, so allow a 1px rounding
        // slack around the requested 256x256 fit box.
        assertTrue(thumb.getWidth() <= 257 && thumb.getHeight() <= 257,
            "the thumbnail must fit the requested 256x256 box (got "
                + thumb.getWidth() + "x" + thumb.getHeight() + ")");
    }

    @Test
    @DisplayName("out-of-range page numbers render nothing")
    void outOfRange() {
        PdfManager manager = new PdfManager(1024, 768);
        manager.decodeResourceURL(SAMPLE);
        int last = manager.getLastPageNumber();

        assertNull(manager.getImage(0));
        assertNull(manager.getImage(-1));
        assertNull(manager.getImage(last + 1));
    }

    @Test
    @DisplayName("page navigation clamps to the document range")
    void navigation() {
        PdfManager manager = new PdfManager(1024, 768);
        manager.decodeResourceURL(SAMPLE);
        int last = manager.getLastPageNumber();

        manager.setPage(1);
        assertEquals(1, manager.getCurrentPageNumber());

        manager.next();
        if (last > 1) {
            assertEquals(2, manager.getCurrentPageNumber());
        } else {
            assertEquals(1, manager.getCurrentPageNumber(),
                "next() must not advance past the last page");
        }

        manager.prev();
        assertEquals(1, manager.getCurrentPageNumber());

        manager.prev();
        assertEquals(1, manager.getCurrentPageNumber(),
            "prev() must not go before page 1");

        manager.setPage(last + 5);
        assertTrue(manager.getCurrentPageNumber() <= last,
            "setPage() must ignore an out-of-range page");
    }

    @Test
    @DisplayName("decodeFile reads the same PDF from a filesystem path")
    void decodeFile() throws Exception {
        Path temp = Files.createTempFile("jsaddle-sample", ".pdf");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SAMPLE)) {
            assertNotNull(in, "the bundled sample PDF must be on the test classpath");
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }

        PdfManager manager = new PdfManager(1024, 768);
        manager.decodeFile(temp.toString());

        assertTrue(manager.getLastPageNumber() > 0);
        assertNotNull(manager.getImage(1));
        assertEquals(temp.toString(), manager.getCurrentFile());

        Files.deleteIfExists(temp);
    }
}
