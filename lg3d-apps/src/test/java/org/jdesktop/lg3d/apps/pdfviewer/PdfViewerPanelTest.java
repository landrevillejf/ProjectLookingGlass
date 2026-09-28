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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.ImageIcon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link PdfViewerPanel}, the Swing reader hosted in the 2D
 * desktop (and on a {@code SwingNode} in 3D). Constructing the panel needs no X
 * display, so these drive the real widget: open a document, page through it,
 * zoom, and read back the page canvas icon and the status line. The file
 * chooser is never touched (it is only reachable from the Open button).
 */
class PdfViewerPanelTest {

    @Test
    @DisplayName("a fresh panel is empty and advertises the host size")
    void emptyPanel() {
        PdfViewerPanel panel = new PdfViewerPanel();
        assertEquals(0, panel.pageCount());
        assertEquals(1, panel.currentPage());
        assertNull(panel.pageIcon(), "no page is rendered before a file opens");
        assertTrue(panel.statusText().contains("no document open"));
        assertEquals(PdfViewerPanel.WIDTH_PX,
                panel.getPreferredSize().width);
        assertEquals(PdfViewerPanel.HEIGHT_PX,
                panel.getPreferredSize().height);
    }

    @Test
    @DisplayName("opening a file renders page 1 and fills the status line")
    void openRendersFirstPage(@TempDir Path dir) throws IOException {
        Path pdf = write(dir, "doc.pdf", TestPdfs.sample(3));
        PdfViewerPanel panel = new PdfViewerPanel();

        assertTrue(panel.openFile(pdf.toFile()));
        assertEquals(3, panel.pageCount());
        assertEquals(1, panel.currentPage());
        assertNotNull(panel.pageIcon(), "page 1 must be rendered on open");
        assertTrue(panel.statusText().contains("page 1 of 3"),
                "status must report page 1 of 3 (got: " + panel.statusText() + ")");
        assertTrue(panel.statusText().contains("doc.pdf"),
                "status must name the file (got: " + panel.statusText() + ")");
    }

    @Test
    @DisplayName("showPage navigates and clamps to the document range")
    void navigationClamps(@TempDir Path dir) throws IOException {
        Path pdf = write(dir, "doc.pdf", TestPdfs.sample(3));
        PdfViewerPanel panel = new PdfViewerPanel();
        panel.openFile(pdf.toFile());

        panel.showPage(3);
        assertEquals(3, panel.currentPage());
        assertTrue(panel.statusText().contains("page 3 of 3"));

        panel.showPage(99);
        assertEquals(3, panel.currentPage(), "showPage must clamp above the last page");
        panel.showPage(0);
        assertEquals(3, panel.currentPage(), "showPage must clamp below page 1");
        panel.showPage(2);
        assertEquals(2, panel.currentPage());
    }

    @Test
    @DisplayName("zoom re-renders the page at the new scale and updates readouts")
    void zoomRerenders(@TempDir Path dir) throws IOException {
        Path pdf = write(dir, "doc.pdf", TestPdfs.sample(2));
        PdfViewerPanel panel = new PdfViewerPanel();
        panel.openFile(pdf.toFile());

        int widthAt100 = iconWidth(panel);
        panel.setZoom(2f);
        assertEquals(2f, panel.zoom());
        assertTrue(panel.statusText().contains("200%"),
                "status must report the zoom (got: " + panel.statusText() + ")");
        int widthAt200 = iconWidth(panel);
        assertTrue(Math.abs(widthAt200 - widthAt100 * 2) <= 4,
                "200% must double the rendered page width ("
                        + widthAt100 + " -> " + widthAt200 + ")");

        // The zoom clamp keeps an absurd zoom inside the supported range.
        panel.setZoom(1000f);
        assertTrue(panel.zoom() <= 8.0f, "zoom must clamp to the maximum");
        panel.setZoom(0.0001f);
        assertTrue(panel.zoom() >= 0.10f, "zoom must clamp to the minimum");
    }

    @Test
    @DisplayName("a file that is not a PDF leaves the open document in place")
    void openFailureKeepsDocument(@TempDir Path dir) throws IOException {
        Path good = write(dir, "good.pdf", TestPdfs.sample(2));
        Path bad = write(dir, "bad.pdf", "not a pdf".getBytes(StandardCharsets.UTF_8));
        PdfViewerPanel panel = new PdfViewerPanel();
        panel.openFile(good.toFile());

        assertFalse(panel.openFile(bad.toFile()));
        assertEquals(2, panel.pageCount(), "the previous document must survive");
        assertEquals(1, panel.currentPage());
        assertNotNull(panel.pageIcon(), "the rendered page must survive");
        assertTrue(panel.statusText().contains("Could not open bad.pdf"),
                "status must report the failure (got: " + panel.statusText() + ")");
    }

    private static Path write(Path dir, String name, byte[] bytes) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    private static int iconWidth(PdfViewerPanel panel) {
        return ((ImageIcon) panel.pageIcon()).getIconWidth();
    }
}
