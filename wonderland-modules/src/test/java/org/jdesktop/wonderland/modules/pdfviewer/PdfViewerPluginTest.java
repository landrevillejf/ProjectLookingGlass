/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7b: headless tests for the pdfviewer plugin — the
 * ServiceLoader registration, the lifecycle, the open-via-the-seam walk and
 * the render delegation, all on a recording stub rasteriser.
 */
package org.jdesktop.wonderland.modules.pdfviewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import org.jdesktop.wonderland.modules.api.PluginContext;
import org.jdesktop.wonderland.modules.api.WonderlandPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PdfViewerPluginTest {

    private static final PluginContext EMPTY_CONTEXT =
            new PluginContext(java.util.Collections.emptyMap());

    @AfterEach
    void uninstallFactory() {
        // keep the static slot clean for the rest of the suite
        PdfViewerPlugin.installRasterizerFactory(null);
    }

    @Test
    void discoveredByServiceLoader() {
        boolean found = false;
        for (WonderlandPlugin plugin : ServiceLoader.load(WonderlandPlugin.class)) {
            if (PdfViewerPlugin.MODULE_NAME.equals(plugin.getModuleInfo().getName())) {
                found = true;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void theShippedNoopRasterizerResolvesAndRendersNothing() {
        // the headless sink: no document resolves, no page renders and it holds
        // nothing to release. renderPage's null is the half the open-via-seam
        // walk never reaches on its own, so the shipped noop is pinned directly.
        assertThat(NoopPageRasterizer.INSTANCE.pageCount("any.pdf")).isEqualTo(-1);
        assertThat(NoopPageRasterizer.INSTANCE.renderPage("any.pdf", 1, 1.0f)).isNull();
        NoopPageRasterizer.INSTANCE.release();
    }

    @Test
    void headlessInitializeKeepsTheModelOnTheNoopRasterizer() {
        PdfViewerPlugin plugin = new PdfViewerPlugin();
        plugin.initialize(EMPTY_CONTEXT);

        assertThat(plugin.getModel()).isNotNull();
        // the shipped noop resolves nothing: open lands honestly on ERROR
        assertThat(plugin.open("anything.pdf")).isFalse();
        assertThat(plugin.getModel().getStatus())
                .isEqualTo(PdfViewModel.Status.ERROR);
        assertThat(plugin.renderCurrentPage()).isNull();

        plugin.dispose();
        assertThat(plugin.getModel()).isNull();
    }

    @Test
    void initializeIsIdempotent() {
        PdfViewerPlugin plugin = new PdfViewerPlugin();
        plugin.initialize(EMPTY_CONTEXT);
        PdfViewModel first = plugin.getModel();
        plugin.initialize(EMPTY_CONTEXT);
        assertThat(plugin.getModel()).isSameAs(first);
    }

    @Test
    void openAndRenderBeforeInitializeAreRefused() {
        PdfViewerPlugin plugin = new PdfViewerPlugin();
        assertThat(plugin.open("a.pdf")).isFalse();
        assertThat(plugin.renderCurrentPage()).isNull();
        plugin.dispose(); // dispose-before-initialize is a no-op
        assertThat(plugin.getModel()).isNull();
    }

    @Test
    void installedFactoryBacksTheOpenWalk() {
        List<String> calls = new ArrayList<>();
        RecordingRasterizer last = new RecordingRasterizer(calls);
        PdfViewerPlugin.installRasterizerFactory(() -> last);

        PdfViewerPlugin plugin = new PdfViewerPlugin();
        plugin.initialize(EMPTY_CONTEXT);
        assertThat(plugin.open("doc.pdf")).isTrue();
        assertThat(plugin.getModel().getStatus())
                .isEqualTo(PdfViewModel.Status.LOADED);
        assertThat(plugin.getModel().getPageCount()).isEqualTo(3);

        plugin.getModel().gotoPage(2);
        plugin.getModel().setZoom(1.5f);
        byte[] rendered = plugin.renderCurrentPage();
        assertThat(rendered).isNotNull();
        assertThat(calls).containsExactly(
                "pageCount:doc.pdf", "renderPage:doc.pdf:2:1.5");

        plugin.dispose();
        assertThat(calls).contains("release");
    }

    @Test
    void blankUriNeverReachesTheRasterizer() {
        List<String> calls = new ArrayList<>();
        PdfViewerPlugin.installRasterizerFactory(
                () -> new RecordingRasterizer(calls));
        PdfViewerPlugin plugin = new PdfViewerPlugin();
        plugin.initialize(EMPTY_CONTEXT);
        assertThat(plugin.open("  ")).isFalse();
        assertThat(calls).isEmpty();
        assertThat(plugin.getModel().getStatus())
                .isEqualTo(PdfViewModel.Status.EMPTY);
    }

    @Test
    void unresolvableDocumentLandsOnError() {
        List<String> calls = new ArrayList<>();
        PdfViewerPlugin.installRasterizerFactory(() -> new PageRasterizer() {
            @Override
            public int pageCount(String documentUri) {
                return -1; // the 2006 module got the same answer from a bad URL
            }

            @Override
            public byte[] renderPage(String documentUri, int pageNumber, float zoom) {
                return null;
            }

            @Override
            public void release() {
            }
        });
        PdfViewerPlugin plugin = new PdfViewerPlugin();
        plugin.initialize(EMPTY_CONTEXT);
        assertThat(plugin.open("missing.pdf")).isFalse();
        assertThat(plugin.getModel().getStatus())
                .isEqualTo(PdfViewModel.Status.ERROR);
        assertThat(plugin.renderCurrentPage()).isNull();
    }

    @Test
    void factorySlotReadsBackTheInstalledFactory() {
        PdfViewerPlugin.RasterizerFactory f = () -> NoopPageRasterizer.INSTANCE;
        assertThat(PdfViewerPlugin.rasterizerFactory()).isNull(); // headless default
        PdfViewerPlugin.installRasterizerFactory(f);
        assertThat(PdfViewerPlugin.rasterizerFactory()).isSameAs(f);
    }

    @Test
    void renderIsRefusedWhileTheDocumentIsOnlyLoading() {
        List<String> calls = new ArrayList<>();
        PdfViewerPlugin.installRasterizerFactory(
                () -> new RecordingRasterizer(calls) {
                    @Override
                    public int pageCount(String documentUri) {
                        calls.add("pageCount-pending");
                        return 0; // resolve as "still pending / empty"
                    }
                });
        PdfViewerPlugin plugin = new PdfViewerPlugin();
        plugin.initialize(EMPTY_CONTEXT);
        // a 0-page answer is an error: nothing to render
        assertThat(plugin.open("a.pdf")).isFalse();
        assertThat(plugin.renderCurrentPage()).isNull();
        assertThat(calls).doesNotContain("renderPage:a.pdf:1:1.0");
    }

    /** A stub that records the seam calls the model decided to make. */
    private static class RecordingRasterizer implements PageRasterizer {
        private final List<String> calls;

        RecordingRasterizer(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public int pageCount(String documentUri) {
            calls.add("pageCount:" + documentUri);
            return 3;
        }

        @Override
        public byte[] renderPage(String documentUri, int pageNumber, float zoom) {
            calls.add("renderPage:" + documentUri + ":" + pageNumber + ":" + zoom);
            return new byte[] {1, 2, 3};
        }

        @Override
        public void release() {
            calls.add("release");
        }
    }
}
