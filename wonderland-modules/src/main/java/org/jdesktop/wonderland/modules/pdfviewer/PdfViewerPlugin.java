/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7b (pdfviewer): the module's WonderlandPlugin provider
 * plus the rasteriser-factory seam, and the jpedal → Apache PDFBox anchor.
 *
 * The 2006 pdfviewer module rendered pages with jpedal (jpedalSTD), a dead
 * library with no maintained JDK 21 build — the modern, function-preserving
 * replacement is Apache PDFBox, already in-tree behind the lg3d-apps PDF
 * Viewer. PDFBox rasterises to a BufferedImage, which this headless module must
 * not touch, so the module owns only the paging / zoom / load state machine
 * (PdfViewModel) and reaches the real renderer through this static factory slot
 * — the same installer-seam idiom M6a/M6b used for the renderers and M7a used
 * for the videoplayer. The lg3d-apps PdfBoxRasterizer is installed from the
 * desktop boot; a headless JVM sees none and the model runs on the shipped
 * NoopPageRasterizer (open() then honestly reports ERROR), so every model
 * decision is CI-provable against a recording stub.
 */
package org.jdesktop.wonderland.modules.pdfviewer;

import org.jdesktop.wonderland.common.modules.ModuleInfo;
import org.jdesktop.wonderland.modules.api.PluginContext;
import org.jdesktop.wonderland.modules.api.WonderlandPlugin;

/** The pdfviewer feature module. */
public final class PdfViewerPlugin implements WonderlandPlugin {

    /** The module name this plugin is registered under. */
    public static final String MODULE_NAME = "pdfviewer";

    /**
     * Builds the page rasteriser for one viewer. The live implementation wraps
     * an Apache PDFBox renderer; installed from the desktop JVM only.
     */
    @FunctionalInterface
    public interface RasterizerFactory {
        PageRasterizer create();
    }

    private static volatile RasterizerFactory factory;

    /**
     * Install the live rasteriser factory. Idempotent by last-writer-wins,
     * like the M6b / M7a installer slots.
     *
     * @param rasterizerFactory the factory, or null to uninstall (test isolation)
     */
    public static void installRasterizerFactory(RasterizerFactory rasterizerFactory) {
        factory = rasterizerFactory;
    }

    /** The currently-installed factory, or null in a headless JVM. */
    public static RasterizerFactory rasterizerFactory() {
        return factory;
    }

    private final ModuleInfo info =
            new ModuleInfo(MODULE_NAME, 1, 0, 0, "PDF document viewing (Apache PDFBox)");

    private PdfViewModel model;
    private PageRasterizer rasterizer;

    @Override
    public ModuleInfo getModuleInfo() {
        return info;
    }

    @Override
    public void initialize(PluginContext context) {
        if (model != null) {
            return; // idempotent, like every M4 module
        }
        rasterizer = factory == null
                ? NoopPageRasterizer.INSTANCE
                : factory.create();
        model = new PdfViewModel();
    }

    @Override
    public void dispose() {
        if (rasterizer != null) {
            rasterizer.release();
        }
        rasterizer = null;
        model = null;
    }

    /** The live model, or {@code null} before initialize()/after dispose(). */
    public PdfViewModel getModel() {
        return model;
    }

    /**
     * Resolve a document: move the model to LOADING, ask the rasteriser how
     * many pages it has, and land it on LOADED (a positive count) or ERROR.
     *
     * @param documentUri a non-blank path or URL
     * @return true when the document resolved with at least one page
     */
    public boolean open(String documentUri) {
        if (model == null || !model.setSource(documentUri)) {
            return false;
        }
        int pages = rasterizer.pageCount(documentUri);
        return model.markLoaded(pages);
    }

    /**
     * Rasterise the current page through the installed seam.
     *
     * @return the encoded page image, or null when nothing is ready or the
     *         rasteriser cannot render the page
     */
    public byte[] renderCurrentPage() {
        if (model == null || !model.isReady()) {
            return null;
        }
        return rasterizer.renderPage(model.getUri(),
                model.getCurrentPage(), model.getZoom());
    }
}
