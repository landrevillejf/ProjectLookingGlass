/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7b (pdfviewer): the shipped silent rasteriser.
 *
 * A headless JVM (a test, or the Java 3D process before the 2D desktop boot
 * installs the PDFBox factory) has no PDF engine; the module still must not
 * fail. Every command lands on this no-op: no document ever resolves, nothing
 * ever renders, and the model's own guards keep the visible state honest (an
 * open() on the noop reports ERROR rather than pretending to show a page).
 */
package org.jdesktop.wonderland.modules.pdfviewer;

/** The {@link PageRasterizer} that resolves nothing, for headless hosts. */
final class NoopPageRasterizer implements PageRasterizer {

    /** Stateless, so one instance serves every headless plugin. */
    static final NoopPageRasterizer INSTANCE = new NoopPageRasterizer();

    private NoopPageRasterizer() {
    }

    @Override
    public int pageCount(String documentUri) {
        return -1; // nothing resolves without a real PDF engine
    }

    @Override
    public byte[] renderPage(String documentUri, int pageNumber, float zoom) {
        return null;
    }

    @Override
    public void release() {
        // no resources are ever held
    }
}
