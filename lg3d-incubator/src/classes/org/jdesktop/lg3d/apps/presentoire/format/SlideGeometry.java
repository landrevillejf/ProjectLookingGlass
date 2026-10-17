/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.presentoire.format;

import org.jdesktop.lg3d.apps.presentoire.model.PresentoireModel;

/**
 * Maps geometry between Presentoire's square slide and an office page, so an
 * imported deck lands where the renderers already draw and an export lands where
 * PowerPoint / Impress expect it.
 *
 * <p>A Presentoire slide is <em>square</em>: it spans {@code -0.1 .. 0.1} on
 * both axes ({@link PresentoireModel#SLIDE_HALF}), so its usable span is
 * {@code 0.2} and its Y axis grows <em>up</em>. A PowerPoint or OpenDocument
 * page is 4:3 or 16:9, measured from its top-left corner with Y growing
 * <em>down</em>. This helper reconciles the two with a fit-inside,
 * aspect-preserving, centred transform - never a stretch - so a wide page is
 * letter-boxed top and bottom and a tall page is pillar-boxed left and right.</p>
 *
 * <p>The transform is {@code scale = 0.2 / max(pageW, pageH)}, with the leftover
 * on the short axis split evenly as {@code offX} / {@code offY}. A source point
 * {@code (px, py)} (py from the top) maps to slide
 * {@code (-0.1 + offX + px*scale, +0.1 - offY - py*scale)}; sizes scale by
 * {@code scale}. The export methods are the exact inverse, so an
 * import-then-export round trip returns the original page geometry.</p>
 *
 * <p>Element anchoring matches {@code SlideRenderer}: a text block or bullet list
 * is anchored bottom-left (its {@code y} is the box's bottom edge), while an
 * image is anchored centre (its {@code x}/{@code y} are the box centre). Each
 * direction therefore offers a text/list and an image variant, and a codec picks
 * the one that matches the element it is placing.</p>
 *
 * <p>Instances are immutable and unit-agnostic: the page size and every source
 * coordinate share one unit (points for {@code .pptx}/{@code .ppt}, centimetres
 * for {@code .odp}), and only slide-unit results are always in Presentoire's
 * {@code -0.1 .. 0.1} space.</p>
 */
public final class SlideGeometry {

    /** The full slide span in document units ({@code 2 * SLIDE_HALF}). */
    public static final float SPAN = 2f * PresentoireModel.SLIDE_HALF;

    /** Half the slide span in document units. */
    public static final float HALF = PresentoireModel.SLIDE_HALF;

    /** Default PowerPoint page size in points (10in x 7.5in, 4:3). */
    public static final double PPT_PAGE_WIDTH_PT = 720d;
    /** Default PowerPoint page height in points. */
    public static final double PPT_PAGE_HEIGHT_PT = 540d;

    /** Default OpenDocument presentation page width in centimetres (28.0cm). */
    public static final double ODP_PAGE_WIDTH_CM = 28.0d;
    /** Default OpenDocument presentation page height in centimetres (21.0cm). */
    public static final double ODP_PAGE_HEIGHT_CM = 21.0d;

    private final double scale;
    private final double offX;
    private final double offY;

    /**
     * Builds the transform for a page of the given size.
     *
     * @param pageWidth  the source page width, in the caller's unit
     * @param pageHeight the source page height, in the same unit
     */
    public SlideGeometry(double pageWidth, double pageHeight) {
        double longest = Math.max(pageWidth, pageHeight);
        if (longest <= 0d) {
            longest = 1d;
        }
        this.scale = SPAN / longest;
        this.offX = (SPAN - pageWidth * scale) / 2d;
        this.offY = (SPAN - pageHeight * scale) / 2d;
    }

    /** The fit-inside scale, in slide units per source unit. */
    public double scale() {
        return scale;
    }

    // ------------------------------------------------------------------
    // Import: source page rect -> Presentoire element box (slide units)
    // ------------------------------------------------------------------

    /**
     * Maps a source rect (top-left origin, Y down) to a bottom-left-anchored
     * text or list element box, in slide units.
     *
     * @param sx the rect's left edge, in source units
     * @param sy the rect's top edge, in source units
     * @param sw the rect width, in source units
     * @param sh the rect height, in source units
     */
    public Box importTextBox(double sx, double sy, double sw, double sh) {
        double w = sw * scale;
        double h = sh * scale;
        double left = -HALF + offX + sx * scale;
        double top = HALF - offY - sy * scale;
        return new Box((float) left, (float) (top - h), (float) w, (float) h);
    }

    /**
     * Maps a source rect (top-left origin, Y down) to a centre-anchored image
     * element box, in slide units.
     *
     * @param sx the rect's left edge, in source units
     * @param sy the rect's top edge, in source units
     * @param sw the rect width, in source units
     * @param sh the rect height, in source units
     */
    public Box importImageBox(double sx, double sy, double sw, double sh) {
        double w = sw * scale;
        double h = sh * scale;
        double left = -HALF + offX + sx * scale;
        double top = HALF - offY - sy * scale;
        return new Box((float) (left + w / 2d), (float) (top - h / 2d),
                (float) w, (float) h);
    }

    // ------------------------------------------------------------------
    // Export: Presentoire element box (slide units) -> source page rect
    // ------------------------------------------------------------------

    /**
     * Maps a bottom-left-anchored text or list element (slide units) to a source
     * rect with a top-left origin and Y down, in source units.
     *
     * @param x the element's left edge, in slide units
     * @param y the element's bottom edge, in slide units
     * @param w the element width, in slide units
     * @param h the element height, in slide units
     */
    public Box exportTextBox(float x, float y, float w, float h) {
        return toSource(x, y + h, w, h);
    }

    /**
     * Maps a centre-anchored image element (slide units) to a source rect with a
     * top-left origin and Y down, in source units.
     *
     * @param cx the image centre X, in slide units
     * @param cy the image centre Y, in slide units
     * @param w  the element width, in slide units
     * @param h  the element height, in slide units
     */
    public Box exportImageBox(float cx, float cy, float w, float h) {
        return toSource(cx - w / 2f, cy + h / 2f, w, h);
    }

    /** Inverse of the import mapping: slide-unit left/top/size -> source rect. */
    private Box toSource(double left, double top, double w, double h) {
        double sx = (left + HALF - offX) / scale;
        double sy = (HALF - offY - top) / scale;
        return new Box((float) sx, (float) sy, (float) (w / scale), (float) (h / scale));
    }

    /**
     * Four coordinates in whatever space the producing method names: slide units
     * for the import methods, source units for the export methods.
     */
    public static final class Box {

        private final float x;
        private final float y;
        private final float width;
        private final float height;

        Box(float x, float y, float width, float height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        /** The X coordinate (left edge, or centre for an imported image). */
        public float x() {
            return x;
        }

        /** The Y coordinate (bottom edge, or centre for an imported image). */
        public float y() {
            return y;
        }

        /** The width. */
        public float width() {
            return width;
        }

        /** The height. */
        public float height() {
            return height;
        }
    }
}
