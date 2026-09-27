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
package org.jdesktop.lg3d.apps.pdfviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link PdfDocument}, the Apache PDFBox adapter behind the
 * PDF Viewer. They prove real rasterisation with no X display: pages of an
 * in-memory A4 document ({@link TestPdfs}) are decoded and rendered to
 * {@link BufferedImage}s at the expected pixel size for a given zoom, and the
 * 1-based paging clamps to the document range.
 */
class PdfDocumentTest {

    @Test
    @DisplayName("a fresh document reports zero pages and renders nothing")
    void emptyDocument() {
        PdfDocument doc = new PdfDocument();
        assertEquals(0, doc.getPageCount());
        assertFalse(doc.isOpen());
        assertEquals(1, doc.getCurrentPage());
        assertNull(doc.renderPage(1, 1f));
        assertNull(doc.renderCurrentPage(1f));
        assertEquals(0f, doc.getPageWidthPoints());
        assertNull(doc.getCurrentFile());
    }

    @Test
    @DisplayName("open(bytes) loads the sample and reports its page count")
    void openBytes() {
        PdfDocument doc = new PdfDocument();
        assertTrue(doc.open(TestPdfs.sample(3)));
        assertTrue(doc.isOpen());
        assertEquals(3, doc.getPageCount());
        assertEquals(1, doc.getCurrentPage());
        // A4 natural size, in points.
        assertTrue(doc.getPageWidthPoints() > 500f);
        assertTrue(doc.getPageHeightPoints() > 700f);
    }

    @Test
    @DisplayName("renderPage rasterises at 72 DPI times the zoom")
    void renderScalesWithZoom() {
        PdfDocument doc = new PdfDocument();
        doc.open(TestPdfs.sample(2));

        BufferedImage at100 = doc.renderPage(1, 1f);
        assertNotNull(at100);
        assertTrue(Math.abs(at100.getWidth() - TestPdfs.A4_W) <= 2,
                "100% must render ~A4 width (got " + at100.getWidth() + ")");
        assertTrue(Math.abs(at100.getHeight() - TestPdfs.A4_H) <= 2,
                "100% must render ~A4 height (got " + at100.getHeight() + ")");

        BufferedImage at200 = doc.renderPage(1, 2f);
        assertNotNull(at200);
        assertTrue(Math.abs(at200.getWidth() - TestPdfs.A4_W * 2) <= 3,
                "200% must double the raster width (got " + at200.getWidth() + ")");
    }

    @Test
    @DisplayName("out-of-range pages render nothing")
    void renderOutOfRange() {
        PdfDocument doc = new PdfDocument();
        doc.open(TestPdfs.sample(2));
        assertNull(doc.renderPage(0, 1f));
        assertNull(doc.renderPage(-1, 1f));
        assertNull(doc.renderPage(3, 1f));
    }

    @Test
    @DisplayName("1-based paging clamps to the document range")
    void pagingClamps() {
        PdfDocument doc = new PdfDocument();
        doc.open(TestPdfs.sample(3));

        assertFalse(doc.setPage(0), "page 0 is out of range");
        assertFalse(doc.setPage(4), "page 4 is out of range");
        assertEquals(1, doc.getCurrentPage());

        assertTrue(doc.setPage(2));
        assertEquals(2, doc.getCurrentPage());

        assertTrue(doc.next());
        assertEquals(3, doc.getCurrentPage());
        assertFalse(doc.next(), "next() must stop on the last page");
        assertEquals(3, doc.getCurrentPage());

        assertTrue(doc.prev());
        assertEquals(2, doc.getCurrentPage());
        doc.setPage(1);
        assertFalse(doc.prev(), "prev() must stop on page 1");
        assertEquals(1, doc.getCurrentPage());
    }

    @Test
    @DisplayName("open(File) reads the same PDF from disk and records the path")
    void openFile(@TempDir Path dir) throws IOException {
        Path pdf = dir.resolve("sample.pdf");
        Files.write(pdf, TestPdfs.sample(2));

        PdfDocument doc = new PdfDocument();
        assertTrue(doc.open(pdf.toFile()));
        assertEquals(2, doc.getPageCount());
        assertEquals(pdf.toString(), doc.getCurrentFile());
        assertNotNull(doc.renderCurrentPage(1f));
    }

    @Test
    @DisplayName("garbage bytes and a missing resource do not open")
    void openFailures() {
        PdfDocument doc = new PdfDocument();
        assertFalse(doc.open("not a pdf".getBytes(StandardCharsets.UTF_8)));
        assertFalse(doc.isOpen());
        assertFalse(doc.openResource("no/such/document.pdf"));
        assertFalse(doc.isOpen());
    }

    @Test
    @DisplayName("close releases the document and resets paging")
    void closeResets() {
        PdfDocument doc = new PdfDocument();
        doc.open(TestPdfs.sample(2));
        doc.setPage(2);

        doc.close();
        assertFalse(doc.isOpen());
        assertEquals(0, doc.getPageCount());
        assertEquals(1, doc.getCurrentPage());
        assertNull(doc.renderCurrentPage(1f));
    }
}
