/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.imagestudio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link ImageStudioPanel}, the 2D/Swing counterpart of
 * ImageStudioFrame3D. They drive the panel's package-private interaction seams
 * ({@code runOpLabel}, {@code setSliderPosition}) and assert on the reused
 * {@link EditorModel} plus real {@link JaiProcessor} operations, so the whole
 * editing chain (JAI included) is exercised with no display.
 */
class ImageStudioPanelTest {

    private static int pixel(ImageStudioPanel panel, int x, int y) {
        return panel.model().getCurrent().getRGB(x, y) & 0xFFFFFF;
    }

    @Test
    @DisplayName("a fresh panel opens on the placeholder image at its native size")
    void freshPanelHasPlaceholder() {
        ImageStudioPanel panel = new ImageStudioPanel();
        assertEquals(ImageStudioPanel.WIDTH_PX, panel.getPreferredSize().width);
        assertEquals(ImageStudioPanel.HEIGHT_PX, panel.getPreferredSize().height);
        assertTrue(panel.model().hasImage());
        assertEquals(720, panel.model().getCurrent().getWidth());
        assertEquals(480, panel.model().getCurrent().getHeight());
        assertTrue(panel.statusText().contains("720x480"),
                "the status line reports the placeholder geometry");
        assertFalse(panel.model().canUndo());
    }

    @Test
    @DisplayName("a one-shot op applies through JAI and undo restores the pixel")
    void oneShotOpAppliesAndUndoes() {
        ImageStudioPanel panel = new ImageStudioPanel();
        int before = pixel(panel, 5, 5);
        panel.runOpLabel("Invert");
        int after = pixel(panel, 5, 5);
        assertNotEquals(before, after, "Invert must change the image");
        assertTrue(panel.statusText().startsWith("Invert"));
        assertTrue(panel.model().canUndo());
        panel.model().undo();
        assertEquals(before, pixel(panel, 5, 5),
                "undo restores the pre-Invert pixel exactly");
    }

    @Test
    @DisplayName("a parameterized op arms the slider, previews without history and commits once")
    void parameterizedOpArmsAndCommitsOnce() {
        ImageStudioPanel panel = new ImageStudioPanel();
        int baseline = pixel(panel, 5, 5);

        panel.runOpLabel("Brighter");            // arms at +0 (image unchanged)
        assertFalse(panel.model().canUndo(),
                "arming an op alone pushes no history");
        panel.setSliderPosition(1000);           // previews at +128
        assertNotEquals(baseline, pixel(panel, 5, 5),
                "the slider drag previews against the armed baseline");
        assertFalse(panel.model().canUndo(),
                "slider previews never grow the undo stack");

        panel.runOpLabel("Gray");                // commits Brighter, then applies
        assertTrue(panel.model().canUndo());
        panel.model().undo();                    // undo the Gray
        panel.model().undo();                    // undo the committed Brighter drag
        assertEquals(baseline, pixel(panel, 5, 5),
                "the whole slider drag commits as a single undo entry");
    }

    @Test
    @DisplayName("an unknown op label is ignored")
    void unknownLabelIsIgnored() {
        ImageStudioPanel panel = new ImageStudioPanel();
        String status = panel.statusText();
        panel.runOpLabel("Definitely Not An Op");
        assertEquals(status, panel.statusText());
        assertFalse(panel.model().canUndo());
    }

    @Test
    @DisplayName("the JAI histogram computes for the current image")
    void histogramComputes() {
        ImageStudioPanel panel = new ImageStudioPanel();
        assertNotNull(panel.model().histogram(),
                "the vendored JAI must run under the test JVM's add-exports");
    }

    @Test
    @DisplayName("the shared catalog resolves labels and guards unknown codes")
    void catalogLookups() {
        OpCatalog.OpDef blur = OpCatalog.forLabel("Blur");
        assertNotNull(blur);
        assertTrue(blur.param);
        assertEquals(1f, blur.min);
        assertEquals(20f, blur.max);
        assertNull(OpCatalog.forLabel(null));
        assertNull(OpCatalog.forLabel("nope"));
        assertEquals(4, OpCatalog.CATEGORIES.length);
        assertEquals(4, OpCatalog.CATALOG.length);
    }

    @Test
    @DisplayName("parameter formatting matches every catalog conversion")
    void formatsEveryCatalogConversion() {
        // An Integer fed to a %f pattern throws IllegalFormatConversionException;
        // formatValue picks the right boxed type per conversion (regression for
        // the 3D slider's silently failing value label).
        assertEquals("+128", OpCatalog.formatValue("%+.0f", true, 128.4f));
        assertEquals("0xF0", OpCatalog.formatValue("0x%02X", true, 240f));
        assertEquals("x1.50", OpCatalog.formatValue("x%.2f", false, 1.5f));
        assertEquals("90\u00B0", OpCatalog.formatValue("%.0f\u00B0", true, 90f));
        // Every catalogued range must survive formatting at both extremes.
        for (OpCatalog.OpDef[] category : OpCatalog.CATALOG) {
            for (OpCatalog.OpDef op : category) {
                if (op.param) {
                    assertNotNull(OpCatalog.formatValue(op.fmt, op.intFmt, op.min));
                    assertNotNull(OpCatalog.formatValue(op.fmt, op.intFmt, op.max));
                }
            }
        }
    }

    @Test
    @DisplayName("an unknown op code passes the source image through")
    void unknownCodeIsIdentity() throws Exception {
        BufferedImage src = new BufferedImage(
                4, 4, BufferedImage.TYPE_INT_RGB);
        BufferedImage out = OpCatalog.opFor(9999, 0f).apply(src);
        assertEquals(src, out, "an uncatalogued code must be a no-op");
    }
}
