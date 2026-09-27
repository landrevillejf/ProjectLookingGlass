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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

/**
 * Builds throwaway multi-page PDFs in memory with Apache PDFBox so the reader
 * tests need no bundled sample document and run fully headless. Each page is a
 * blank A4 sheet (595 x 842 points), which is all the rasterisation and paging
 * assertions require.
 */
final class TestPdfs {

    /** A4 in PDF points; a 100&nbsp;% (72&nbsp;DPI) render is this many pixels. */
    static final int A4_W = 595;
    static final int A4_H = 842;

    private TestPdfs() {
    }

    static byte[] sample(int pages) {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage(PDRectangle.A4));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not build the sample PDF", e);
        }
    }
}
