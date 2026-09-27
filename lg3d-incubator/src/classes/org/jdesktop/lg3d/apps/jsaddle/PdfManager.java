/*
 * PdfManager.java
 *
 * Created on 2007/01/02, 0:24
 *
 * PdfManager is an adapter class between the jsaddle app and its PDF backend.
 *
 * Originally written against the commercial JPedal library (org.jpedal.PdfDecoder),
 * which is absent from this repository and not on Maven Central. It has been
 * rewritten for JDK 21 on top of Apache PDFBox (Apache-2.0): each page is
 * rendered to a BufferedImage with PDFRenderer.renderImageWithDPI, preserving the
 * exact public contract the app's 3D viewer (JSaddleManager / ViewerContainer /
 * ThumbnailViewerContainer) already consumes, so no caller had to change.
 */

package org.jdesktop.lg3d.apps.jsaddle;

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
 * Adapts the jsaddle 3D viewer to Apache PDFBox. All page numbers exposed by this
 * class are 1-based (as the original JPedal-backed implementation was); PDFBox
 * itself is 0-based, so the conversion happens here.
 *
 * @author Yasuhiro Fujitsuki(thaniwa)
 */
public class PdfManager {

    /** PDF user space is defined at 72 units per inch, so a JPedal-style scale of
     *  1.0 (100%) maps to a 72 DPI render; scale s maps to 72*s DPI. */
    private static final float BASE_DPI = 72.0f;

    private PDDocument document;
    private PDFRenderer renderer;
    private String currentFile;
    private int currentPage = 1;

    private int panelHeight;
    private int panelWidth;

    private int pdfHeight;
    private int pdfWidth;

    private float scale;

    /** Creates a new instance of PdfManager */
    public PdfManager() {
        setPanelSize(0, 0);
    }

    public PdfManager(int width, int height) {
        setPanelSize(width, height);
    }

    public BufferedImage getImage() {
        return getImage(currentPage);
    }

    public BufferedImage getImage(int number) {
        return getImage(number, this.scale);
    }

    public synchronized BufferedImage getImage(int number, float scale) {
        if (renderer == null || number <= 0 || number > getPageCount()) {
            return null;
        }
        try {
            // PDFBox pages are 0-based; the app counts from 1.
            return renderer.renderImageWithDPI(number - 1, BASE_DPI * scale, ImageType.RGB);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public BufferedImage getThumbnailImage() {
        return getThumbnailImage(currentPage);
    }

    public BufferedImage getThumbnailImage(int number) {
        return getThumbnailImage(number, 256, 256);
    }

    public BufferedImage getThumbnailImage(int number, int width, int height) {
        if (document == null || number <= 0 || number > getPageCount()) {
            return null;
        }
        if (width <= 0) width = 200;
        if (height <= 0) height = 150;
        float thumbScale = 0.25f;
        if (pdfHeight > 0 && pdfWidth > 0) {
            float scaleHeight = (float) height / pdfHeight;
            float scaleWidth = (float) width / pdfWidth;
            thumbScale = Math.min(scaleHeight, scaleWidth);
            if (thumbScale == 0.0f) thumbScale = 0.25f;
        }
        return getImage(number, thumbScale);
    }

    public void setPanelSize(int width, int height) {
        panelHeight = height;
        panelWidth = width;
    }

    public void decodeResourceURL(String name) {
        currentFile = name;
        ClassLoader loader = this.getClass().getClassLoader();
        try (InputStream is = loader.getResourceAsStream(name)) {
            if (is == null) {
                System.err.println("PdfManager: resource not found: " + name);
                return;
            }
            open(Loader.loadPDF(is.readAllBytes()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void decodeFile(String name) {
        currentFile = name; // store file name for use in page changer
        try {
            open(Loader.loadPDF(new File(name)));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Adopts a freshly loaded document, closing any previously open one, then
     *  derives the natural page size and the fit-to-panel scale. */
    private void open(PDDocument newDocument) {
        close();
        document = newDocument;
        renderer = new PDFRenderer(document);
        currentPage = 1;

        pdfWidth = 0;
        pdfHeight = 0;
        if (getPageCount() > 0) {
            PDRectangle cropBox = document.getPage(0).getCropBox();
            pdfWidth = Math.round(cropBox.getWidth());
            pdfHeight = Math.round(cropBox.getHeight());
        }

        scale = 1.0f;
        if (pdfHeight != 0 && pdfWidth != 0) {
            float scaleHeight = (float) panelHeight / pdfHeight;
            float scaleWidth = (float) panelWidth / pdfWidth;
            scale = Math.min(scaleHeight, scaleWidth);
        }
        if (scale == 0.0f) scale = 1.0f;
    }

    public void setPage(int number) {
        if (number > 0 && number <= getPageCount()) {
            currentPage = number;
        }
    }

    public void next() {
        setPage(currentPage + 1);
    }

    public void prev() {
        setPage(currentPage - 1);
    }

    public int getCurrentPageNumber() {
        return currentPage;
    }

    public int getLastPageNumber() {
        return getPageCount();
    }

    public String getCurrentFile() {
        return currentFile;
    }

    public float getScale() {
        return scale;
    }

    private int getPageCount() {
        return document == null ? 0 : document.getNumberOfPages();
    }

    private void close() {
        if (document != null) {
            try {
                document.close();
            } catch (IOException e) {
                // Nothing useful to do when releasing the previous document.
            }
            document = null;
            renderer = null;
        }
    }
}
