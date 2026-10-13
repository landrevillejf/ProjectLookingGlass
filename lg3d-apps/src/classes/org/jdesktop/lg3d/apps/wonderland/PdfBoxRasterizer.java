/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * lg3d-apps Wonderland M7b: the live PageRasterizer — the jpedal → Apache
 * PDFBox replacement for the wonderland pdfviewer module.
 *
 * The 2006 pdfviewer rasterised pages with jpedalSTD, a dead library with no
 * maintained JDK 21 build. The modern equivalent — Apache PDFBox — is already
 * in-tree: this desktop's own PDF Viewer (org.jdesktop.lg3d.apps.pdfviewer)
 * rasterises through PdfDocument. This class is the thin adapter that lets the
 * wonderland module reach that engine through its PageRasterizer seam, so the
 * in-world cell and the start-menu PDF Viewer share ONE PDF backend instead of
 * the port shipping two.
 *
 * The seam speaks encoded image bytes (PNG), not BufferedImage — the module is
 * toolkit-free — so this is the class that pays the ImageIO encode. A document
 * is opened lazily and kept only while its URI stays current, because a viewer
 * flips pages within one document, not across documents.
 *
 * Unlike the M7a JavaFX sink, this adapter is CI-provable: PDFBox runs with
 * java.awt.headless=true (the standing PdfDocumentTest precedent), so
 * PdfBoxRasterizerTest resolves and rasterises real generated PDFs on the CI
 * JVM. Live in-world page display is still verified on the desktop, but the
 * PDF engine itself is asserted here, not deferred.
 */
package org.jdesktop.lg3d.apps.wonderland;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.jdesktop.lg3d.apps.pdfviewer.PdfDocument;
import org.jdesktop.wonderland.modules.pdfviewer.PageRasterizer;

/**
 * The Apache PDFBox-backed {@link PageRasterizer}, delegating the parsing and
 * rasterisation to the desktop's existing {@link PdfDocument}.
 */
public final class PdfBoxRasterizer implements PageRasterizer {

    private final PdfDocument document = new PdfDocument();

    /** The URI of the document currently open, or null when none is. */
    private String openUri;

    @Override
    public int pageCount(String documentUri) {
        if (documentUri == null || documentUri.trim().isEmpty()) {
            return -1;
        }
        if (!ensureOpen(documentUri)) {
            return -1;
        }
        return document.isOpen() ? document.getPageCount() : -1;
    }

    @Override
    public byte[] renderPage(String documentUri, int pageNumber, float zoom) {
        if (documentUri == null || !ensureOpen(documentUri)) {
            return null;
        }
        var image = document.renderPage(pageNumber, zoom);
        if (image == null) {
            return null;
        }
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            // PNG is lossless and AWT-builtin: the module never needs a codec.
            if (!ImageIO.write(image, "png", png) || png.size() == 0) {
                return null;
            }
            return png.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void release() {
        document.close();
        openUri = null;
    }

    /**
     * Keep exactly one document open: the same URI reuses the parsed document
     * (page flips must not re-load), any other URI replaces it.
     *
     * @return true when {@link #document} now holds this URI's bytes
     */
    private boolean ensureOpen(String documentUri) {
        if (Objects.equals(openUri, documentUri) && document.isOpen()) {
            return true;
        }
        document.close();
        openUri = null;
        if (!document.open(new File(documentUri))) {
            return false;
        }
        openUri = documentUri;
        return true;
    }
}
