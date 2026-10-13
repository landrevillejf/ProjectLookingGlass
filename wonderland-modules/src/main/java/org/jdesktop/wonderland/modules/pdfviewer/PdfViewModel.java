/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7b (pdfviewer): the headless document model.
 *
 * Tracks the state a PDF-viewer cell holds about the document it shows —
 * source URI, load lifecycle, page count, the 1-based current page and the
 * zoom scale — with no PDFBox / AWT type at all. This is the class the 2006
 * module spread across its jpedal Player and Swing toolbar; here the paging /
 * zoom / status *decisions* are one small machine that CI proves end-to-end,
 * while the pixel work sits behind the PageRasterizer seam (the live PDFBox
 * half is the lg3d-apps PdfBoxRasterizer). The same split as the M4
 * imageviewer's ImageModel, widened with the navigation the 2006 module's
 * toolbar drove.
 */
package org.jdesktop.wonderland.modules.pdfviewer;

/** The state a pdfviewer cell holds about the document it shows. */
public final class PdfViewModel {

    /** The load lifecycle the viewer walks through. */
    public enum Status {
        /** No document source set. */
        EMPTY,
        /** A source is set and its pages are being resolved. */
        LOADING,
        /** The document resolved and is ready to page / render. */
        LOADED,
        /** The source failed to resolve or parse. */
        ERROR
    }

    /** The smallest and largest zoom the viewer accepts (1.0 == 100 %). */
    public static final float MIN_ZOOM = 0.1f;
    /** See {@link #MIN_ZOOM}. */
    public static final float MAX_ZOOM = 8.0f;

    /** The zoom step a single scroll / toolbar click moves by. */
    private static final float ZOOM_STEP = 0.25f;

    private String uri;
    private Status status = Status.EMPTY;
    private int pageCount;
    private int currentPage = 1;
    private float zoom = 1.0f;

    /**
     * Set the document source, moving the model to LOADING and resetting the
     * page and zoom. A blank source is refused — the model keeps its previous
     * state so a typo cannot silently blank an open document.
     *
     * @param documentUri non-blank source path or URL
     * @return true when the source was accepted
     */
    public boolean setSource(String documentUri) {
        if (documentUri == null || documentUri.trim().isEmpty()) {
            return false;
        }
        this.uri = documentUri;
        this.status = Status.LOADING;
        this.pageCount = 0;
        this.currentPage = 1;
        this.zoom = 1.0f;
        return true;
    }

    /**
     * Report the resolved page count. A document with no pages is an error,
     * not a loaded one — there is nothing to show.
     *
     * @param pages the resolved page count
     * @return true when the model reached LOADED
     */
    public boolean markLoaded(int pages) {
        if (status != Status.LOADING) {
            return false; // a late callback for a replaced/closed document
        }
        if (pages <= 0) {
            this.status = Status.ERROR;
            return false;
        }
        this.pageCount = pages;
        this.status = Status.LOADED;
        return true;
    }

    /** Mark the pending source failed to resolve. */
    public void markError() {
        if (status == Status.LOADING) {
            this.status = Status.ERROR;
        }
    }

    /** Forget the document entirely, returning to EMPTY. */
    public void clear() {
        this.uri = null;
        this.status = Status.EMPTY;
        this.pageCount = 0;
        this.currentPage = 1;
        this.zoom = 1.0f;
    }

    /**
     * Move to a 1-based page, clamped into the document range.
     *
     * @param pageNumber the desired page (may be out of range; it clamps)
     * @return true when the current page actually changed
     */
    public boolean gotoPage(int pageNumber) {
        if (!isReady()) {
            return false;
        }
        int clamped = Math.max(1, Math.min(pageNumber, pageCount));
        if (clamped == currentPage) {
            return false;
        }
        currentPage = clamped;
        return true;
    }

    /**
     * Advance one page, staying put on the last page.
     *
     * @return true when the page advanced
     */
    public boolean nextPage() {
        return gotoPage(currentPage + 1);
    }

    /**
     * Go back one page, staying put on the first page.
     *
     * @return true when the page went back
     */
    public boolean previousPage() {
        return gotoPage(currentPage - 1);
    }

    /**
     * Set the zoom scale, clamped to {@code [MIN_ZOOM, MAX_ZOOM]}. A NaN or
     * non-positive value resets to 100 % rather than propagating nonsense.
     *
     * @param value the desired scale (1.0 == 100 %)
     * @return true when the zoom actually changed
     */
    public boolean setZoom(float value) {
        float clamped;
        if (Float.isNaN(value) || value <= 0f) {
            clamped = 1.0f;
        } else {
            clamped = Math.max(MIN_ZOOM, Math.min(value, MAX_ZOOM));
        }
        if (clamped == zoom) {
            return false;
        }
        zoom = clamped;
        return true;
    }

    /** Step the zoom up by one increment, clamped at {@link #MAX_ZOOM}. */
    public void zoomIn() {
        setZoom(zoom + ZOOM_STEP);
    }

    /**
     * Step the zoom down by one increment, clamped at {@link #MIN_ZOOM}.
     * The clamp happens HERE rather than relying on {@link #setZoom}: a step
     * that would land on 0 must fall to the minimum zoom, not to the
     * nonsense-reset-to-100 % path.
     */
    public void zoomOut() {
        setZoom(Math.max(zoom - ZOOM_STEP, MIN_ZOOM));
    }

    public String getUri() {
        return uri;
    }

    public Status getStatus() {
        return status;
    }

    public int getPageCount() {
        return pageCount;
    }

    /** The 1-based current page (1 when nothing is loaded). */
    public int getCurrentPage() {
        return currentPage;
    }

    public float getZoom() {
        return zoom;
    }

    /** Whether the document is ready to page and render. */
    public boolean isReady() {
        return status == Status.LOADED && pageCount > 0;
    }
}
