/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-modules M7b: headless tests for the pdfviewer's pure document
 * model — the load lifecycle, the clamped paging walk and the clamped zoom.
 */
package org.jdesktop.wonderland.modules.pdfviewer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PdfViewModelTest {

    private PdfViewModel model;

    @BeforeEach
    void setUp() {
        model = new PdfViewModel();
    }

    @Test
    void freshModelIsEmptyAtPageOne() {
        assertThat(model.getStatus()).isEqualTo(PdfViewModel.Status.EMPTY);
        assertThat(model.getUri()).isNull();
        assertThat(model.getPageCount()).isZero();
        assertThat(model.getCurrentPage()).isEqualTo(1);
        assertThat(model.getZoom()).isEqualTo(1.0f);
        assertThat(model.isReady()).isFalse();
    }

    @Test
    void setSourceMovesToLoadingAndResetsNavigation() {
        model.setSource("a.pdf");
        model.markLoaded(10);
        model.gotoPage(7);
        model.setZoom(2.0f);

        assertThat(model.setSource("b.pdf")).isTrue();
        assertThat(model.getStatus()).isEqualTo(PdfViewModel.Status.LOADING);
        assertThat(model.getUri()).isEqualTo("b.pdf");
        assertThat(model.getPageCount()).isZero();
        assertThat(model.getCurrentPage()).isEqualTo(1);
        assertThat(model.getZoom()).isEqualTo(1.0f);
    }

    @Test
    void blankSourceIsRefusedAndKeepsThePreviousState() {
        model.setSource("a.pdf");
        model.markLoaded(3);
        assertThat(model.setSource(null)).isFalse();
        assertThat(model.setSource("   ")).isFalse();
        assertThat(model.getUri()).isEqualTo("a.pdf");
        assertThat(model.getStatus()).isEqualTo(PdfViewModel.Status.LOADED);
        assertThat(model.getPageCount()).isEqualTo(3);
    }

    @Test
    void markLoadedNeedsAPendingSource() {
        assertThat(model.markLoaded(5)).isFalse(); // EMPTY, nothing pending
        model.setSource("a.pdf");
        assertThat(model.markLoaded(5)).isTrue();
        assertThat(model.getStatus()).isEqualTo(PdfViewModel.Status.LOADED);
        assertThat(model.isReady()).isTrue();
        // a second callback after LOADED is refused (a late worker result)
        assertThat(model.markLoaded(9)).isFalse();
        assertThat(model.getPageCount()).isEqualTo(5);
    }

    @Test
    void aDocumentWithNoPagesIsAnErrorNotLoaded() {
        model.setSource("empty.pdf");
        assertThat(model.markLoaded(0)).isFalse();
        assertThat(model.getStatus()).isEqualTo(PdfViewModel.Status.ERROR);
        model.setSource("neg.pdf");
        assertThat(model.markLoaded(-2)).isFalse();
        assertThat(model.getStatus()).isEqualTo(PdfViewModel.Status.ERROR);
    }

    @Test
    void markErrorOnlyLandsFromLoading() {
        model.markError(); // EMPTY: no-op
        assertThat(model.getStatus()).isEqualTo(PdfViewModel.Status.EMPTY);
        model.setSource("a.pdf");
        model.markError();
        assertThat(model.getStatus()).isEqualTo(PdfViewModel.Status.ERROR);
        model.markError(); // ERROR: stays
        assertThat(model.getStatus()).isEqualTo(PdfViewModel.Status.ERROR);
    }

    @Test
    void clearForgetsTheWholeDocument() {
        model.setSource("a.pdf");
        model.markLoaded(4);
        model.gotoPage(3);
        model.clear();
        assertThat(model.getStatus()).isEqualTo(PdfViewModel.Status.EMPTY);
        assertThat(model.getUri()).isNull();
        assertThat(model.getCurrentPage()).isEqualTo(1);
        assertThat(model.getZoom()).isEqualTo(1.0f);
    }

    @Test
    void gotoPageClampsIntoTheDocumentRange() {
        model.setSource("a.pdf");
        model.markLoaded(5);
        assertThat(model.gotoPage(99)).isTrue();
        assertThat(model.getCurrentPage()).isEqualTo(5);
        assertThat(model.gotoPage(-7)).isTrue();
        assertThat(model.getCurrentPage()).isEqualTo(1);
        assertThat(model.gotoPage(1)).isFalse(); // already there
    }

    @Test
    void gotoPageBeforeLoadIsRefused() {
        model.setSource("a.pdf"); // LOADING, pages unknown yet
        assertThat(model.gotoPage(2)).isFalse();
        assertThat(model.getCurrentPage()).isEqualTo(1);
    }

    @Test
    void nextAndPreviousStopAtTheEdges() {
        model.setSource("a.pdf");
        model.markLoaded(3);
        assertThat(model.nextPage()).isTrue();
        assertThat(model.getCurrentPage()).isEqualTo(2);
        assertThat(model.nextPage()).isTrue();
        assertThat(model.nextPage()).isFalse(); // stays on the last page
        assertThat(model.getCurrentPage()).isEqualTo(3);
        assertThat(model.previousPage()).isTrue();
        assertThat(model.previousPage()).isTrue();
        assertThat(model.previousPage()).isFalse(); // stays on page 1
        assertThat(model.getCurrentPage()).isEqualTo(1);
    }

    @Test
    void zoomIsClampedToTheReadableRange() {
        assertThat(model.setZoom(20.0f)).isTrue();
        assertThat(model.getZoom()).isEqualTo(PdfViewModel.MAX_ZOOM);
        assertThat(model.setZoom(0.001f)).isTrue();
        assertThat(model.getZoom()).isEqualTo(PdfViewModel.MIN_ZOOM);
        assertThat(model.setZoom(2.5f)).isTrue();
        assertThat(model.getZoom()).isEqualTo(2.5f);
        assertThat(model.setZoom(2.5f)).isFalse(); // unchanged
    }

    @Test
    void nonsenseZoomResetsToHundredPercent() {
        model.setZoom(4.0f);
        assertThat(model.setZoom(Float.NaN)).isTrue(); // 4.0 -> reset 1.0
        assertThat(model.getZoom()).isEqualTo(1.0f);
        // -2 and 0 both reset to 1.0, which the zoom already is: refused
        assertThat(model.setZoom(-2.0f)).isFalse();
        assertThat(model.setZoom(0.0f)).isFalse();
        assertThat(model.getZoom()).isEqualTo(1.0f);
    }

    @Test
    void zoomStepsWalkAndStickAtTheCeiling() {
        for (int i = 0; i < 40; i++) {
            model.zoomIn();
        }
        assertThat(model.getZoom()).isEqualTo(PdfViewModel.MAX_ZOOM);
        for (int i = 0; i < 60; i++) {
            model.zoomOut();
        }
        assertThat(model.getZoom()).isEqualTo(PdfViewModel.MIN_ZOOM);
    }

    @Test
    void navigationOnTheErrorStateIsRefused() {
        model.setSource("broken.pdf");
        model.markError();
        assertThat(model.nextPage()).isFalse();
        assertThat(model.previousPage()).isFalse();
        assertThat(model.isReady()).isFalse();
    }
}
