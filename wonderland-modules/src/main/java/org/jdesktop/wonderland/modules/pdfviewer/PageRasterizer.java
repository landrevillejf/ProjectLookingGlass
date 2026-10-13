/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7b (pdfviewer): the rasterisation seam the headless core
 * reaches through, so the paging / zoom / load state machine is provable in CI
 * while the real page-rendering lives in the desktop JVM on Apache PDFBox.
 *
 * The 2006 pdfviewer module drove jpedal (jpedalSTD) to turn a PDF page into an
 * image — a dead library with no maintained JDK 21 build. The modern, function-
 * preserving replacement is Apache PDFBox (Apache-2.0), already in-tree behind
 * the lg3d-apps PDF Viewer. PDFBox rasterises to a java.awt.image.BufferedImage,
 * which this module must not touch (its floor is a hard headless one with no
 * toolkit types), so the seam speaks only neutral tokens: it hands back encoded
 * image BYTES (the live adapter PNG-encodes the BufferedImage) and an integer
 * page count. The concrete PDFBox rasteriser is the lg3d-apps PdfBoxRasterizer,
 * wired through the PdfViewerPlugin rasteriser-factory slot — exactly the M7a
 * MediaSurface / JfxMediaSurface split. Every model decision is therefore
 * testable against a recording stub.
 */
package org.jdesktop.wonderland.modules.pdfviewer;

/**
 * Resolves and rasterises the pages a {@link PdfViewModel} has asked for.
 * Implementations wrap a real PDF engine (Apache PDFBox); the model says
 * nothing about HOW a page becomes pixels — only WHICH page and at WHAT zoom.
 */
public interface PageRasterizer {

    /**
     * Open the document and count its pages.
     *
     * @param documentUri a file path or URL to the PDF
     * @return the page count (must be {@code > 0} for a usable document), or
     *         {@code -1} when the document cannot be resolved or parsed
     */
    int pageCount(String documentUri);

    /**
     * Rasterise one page to encoded image bytes (PNG), so the seam stays free
     * of any AWT / toolkit type.
     *
     * @param documentUri the already-resolved document
     * @param pageNumber  the 1-based page, always within
     *                    {@code [1, model.getPageCount()] }
     * @param zoom        the already-clamped scale (1.0 == 100 %)
     * @return the encoded page image, or {@code null} when it cannot render
     */
    byte[] renderPage(String documentUri, int pageNumber, float zoom);

    /** Release every resource (open document, caches) held by this rasteriser. */
    void release();
}
