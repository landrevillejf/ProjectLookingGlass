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

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * The PDF backend of the {@link PdfViewerPanel}: an Apache PDFBox
 * (Apache-2.0) adapter that rasterises one page at a time to a
 * {@link BufferedImage}.
 *
 * <p>The class is deliberately AWT-free apart from {@code BufferedImage} so it
 * is the natural headless unit-test seam: opening, paging and rendering are
 * exercised without an X display. Page numbers exposed here are <b>1-based</b>
 * (the convention a reader expects); PDFBox itself is 0-based, so the
 * conversion happens inside this adapter.</p>
 *
 * <p>A zoom of {@code 1.0} means "100&nbsp;%" and maps to a 72&nbsp;DPI render,
 * because PDF user space is defined at 72 units per inch; a zoom {@code z}
 * therefore renders at {@code 72 * z} DPI.</p>
 */
public class PdfDocument {

    /** PDF user space is 72 units per inch, so 100&nbsp;% == 72&nbsp;DPI. */
    private static final float BASE_DPI = 72.0f;

    private PDDocument document;
    private PDFRenderer renderer;
    private String currentFile;
    private int currentPage = 1;

    /** Natural (unscaled) size of the first page, in PDF points. */
    private float pageWidthPoints;
    private float pageHeightPoints;

    /** Creates an empty document holder (no pages until {@link #open}). */
    public PdfDocument() {
    }

    /**
     * Opens a PDF from the filesystem, replacing any open document.
     *
     * @return true when the file parsed and has at least one page
     */
    public boolean open(File file) {
        try {
            return adopt(Loader.loadPDF(file), file.getPath());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Opens a PDF from raw bytes, replacing any open document.
     *
     * @return true when the bytes parsed and have at least one page
     */
    public boolean open(byte[] bytes) {
        try {
            return adopt(Loader.loadPDF(bytes), currentFile);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Opens a PDF from a classpath resource, replacing any open document.
     *
     * @return true when the resource exists, parsed and has at least one page
     */
    public boolean openResource(String name) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                return false;
            }
            return adopt(Loader.loadPDF(in.readAllBytes()), name);
        } catch (IOException e) {
            return false;
        }
    }

    /** Releases the open document, if any. */
    public void close() {
        if (document != null) {
            try {
                document.close();
            } catch (IOException e) {
                // Nothing useful to do when releasing the previous document.
            }
        }
        document = null;
        renderer = null;
        currentPage = 1;
        pageWidthPoints = 0f;
        pageHeightPoints = 0f;
    }

    /** @return true when a document with at least one page is open. */
    public boolean isOpen() {
        return getPageCount() > 0;
    }

    /** @return the number of pages, or 0 when nothing is open. */
    public int getPageCount() {
        return document == null ? 0 : document.getNumberOfPages();
    }

    /** @return the 1-based current page (1 when nothing is open). */
    public int getCurrentPage() {
        return currentPage;
    }

    /**
     * Moves to a 1-based page, clamped to the document range.
     *
     * @return true when the current page changed
     */
    public boolean setPage(int number) {
        if (number < 1 || number > getPageCount() || number == currentPage) {
            return false;
        }
        currentPage = number;
        return true;
    }

    /** @return true when the page advanced (not already on the last page). */
    public boolean next() {
        return setPage(currentPage + 1);
    }

    /** @return true when the page went back (not already on page 1). */
    public boolean prev() {
        return setPage(currentPage - 1);
    }

    /**
     * Rasterises a 1-based page at the given zoom (1.0 == 100&nbsp;%).
     *
     * @return the rendered page, or null when out of range / nothing open
     */
    public BufferedImage renderPage(int number, float zoom) {
        if (renderer == null || number < 1 || number > getPageCount()) {
            return null;
        }
        float safeZoom = zoom > 0f ? zoom : 1f;
        try {
            // PDFBox pages are 0-based; this adapter counts from 1.
            return renderer.renderImageWithDPI(
                    number - 1, BASE_DPI * safeZoom, ImageType.RGB);
        } catch (IOException e) {
            return null;
        }
    }

    /** Rasterises the current page at the given zoom. */
    public BufferedImage renderCurrentPage(float zoom) {
        return renderPage(currentPage, zoom);
    }

    /** @return the first page's width in PDF points (0 when nothing open). */
    public float getPageWidthPoints() {
        return pageWidthPoints;
    }

    /** @return the first page's height in PDF points (0 when nothing open). */
    public float getPageHeightPoints() {
        return pageHeightPoints;
    }

    /** @return the path / resource of the open document, or null. */
    public String getCurrentFile() {
        return currentFile;
    }

    /** Adopts a freshly loaded document and derives its natural page size. */
    private boolean adopt(PDDocument loaded, String label) {
        close();
        document = loaded;
        renderer = new PDFRenderer(document);
        currentPage = 1;
        currentFile = label;

        if (getPageCount() > 0) {
            PDRectangle cropBox = document.getPage(0).getCropBox();
            pageWidthPoints = cropBox.getWidth();
            pageHeightPoints = cropBox.getHeight();
        }
        return isOpen();
    }
}
