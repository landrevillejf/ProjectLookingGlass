/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * lg3d-apps Wonderland M7b: headless tests proving the live PDFBox seam for
 * the wonderland pdfviewer module against REAL generated PDFs — PDFBox runs
 * with java.awt.headless=true, so unlike the M7a JavaFX sink the engine
 * itself is asserted in CI, not deferred to the desktop probe.
 */
package org.jdesktop.lg3d.apps.wonderland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfBoxRasterizerTest {

    private PdfBoxRasterizer rasterizer;

    @BeforeEach
    void setUp() {
        rasterizer = new PdfBoxRasterizer();
    }

    /** A4 in PDF points; a 100 % render is about this many pixels. */
    private static final int A4_W = 595;
    private static final int A4_H = 842;

    /** The same throwaway in-memory A4 PDFs the standing PdfDocumentTest builds. */
    private static byte[] samplePdf(int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage(PDRectangle.A4));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void resolvesARealDocumentOverFs(@TempDir Path dir) throws IOException {
        Path pdf = dir.resolve("three.pdf");
        Files.write(pdf, samplePdf(3));

        assertEquals(3, rasterizer.pageCount(pdf.toString()));
    }

    @Test
    void rendersAPageBackToDecodeablePngBytes(@TempDir Path dir) throws IOException {
        Path pdf = dir.resolve("one.pdf");
        Files.write(pdf, samplePdf(1));

        byte[] png = rasterizer.renderPage(pdf.toString(), 1, 1.0f);
        assertNotNull(png);
        assertTrue(png.length > 0);
        // the bytes must be a real PNG the desktop can show without a codec
        var image = ImageIO.read(new java.io.ByteArrayInputStream(png));
        assertNotNull(image);
        // a 100 % A4 render is about 595 x 842 px (PDF space is 72 units/inch)
        assertTrue(Math.abs(image.getWidth() - A4_W) <= 2,
                "100% width got " + image.getWidth());
        assertTrue(Math.abs(image.getHeight() - A4_H) <= 2,
                "100% height got " + image.getHeight());
    }

    @Test
    void zoomScalesTheRaster(@TempDir Path dir) throws IOException {
        Path pdf = dir.resolve("one.pdf");
        Files.write(pdf, samplePdf(1));

        byte[] half = rasterizer.renderPage(pdf.toString(), 1, 0.5f);
        var image = ImageIO.read(new java.io.ByteArrayInputStream(half));
        assertTrue(Math.abs(image.getWidth() - A4_W / 2) <= 2,
                "50% width got " + image.getWidth()); // ~half of A4 at 100 %
    }

    @Test
    void unknownOrUnparsableDocumentsResolveToNothing(@TempDir Path dir)
            throws IOException {
        assertEquals(-1, rasterizer.pageCount(null));
        assertEquals(-1, rasterizer.pageCount("  "));
        assertEquals(-1, rasterizer.pageCount(dir.resolve("missing.pdf").toString()));

        Path junk = dir.resolve("junk.pdf");
        Files.write(junk, "not a pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(-1, rasterizer.pageCount(junk.toString()));
        assertNull(rasterizer.renderPage(junk.toString(), 1, 1.0f));
    }

    @Test
    void outOfRangePageRendersNothing(@TempDir Path dir) throws IOException {
        Path pdf = dir.resolve("two.pdf");
        Files.write(pdf, samplePdf(2));
        assertEquals(2, rasterizer.pageCount(pdf.toString()));
        assertNull(rasterizer.renderPage(pdf.toString(), 5, 1.0f));
        assertNull(rasterizer.renderPage(pdf.toString(), 0, 1.0f));
    }

    @Test
    void pagingWithinOneDocumentReusesTheParsedFile(@TempDir Path dir)
            throws IOException {
        Path pdf = dir.resolve("two.pdf");
        Files.write(pdf, samplePdf(2));
        // first call parses; the second must hit the kept-open document
        assertEquals(2, rasterizer.pageCount(pdf.toString()));
        assertNotNull(rasterizer.renderPage(pdf.toString(), 2, 1.0f));
        // a different URI replaces it
        Path other = dir.resolve("one.pdf");
        Files.write(other, samplePdf(1));
        assertEquals(1, rasterizer.pageCount(other.toString()));
    }

    @Test
    void releaseFreesTheDocumentAndReopeningWorks(@TempDir Path dir)
            throws IOException {
        Path pdf = dir.resolve("one.pdf");
        Files.write(pdf, samplePdf(1));
        assertEquals(1, rasterizer.pageCount(pdf.toString()));
        rasterizer.release();
        // after release the same URI resolves again (lazy re-open)
        assertEquals(1, rasterizer.pageCount(pdf.toString()));
        rasterizer.release();
    }
}
