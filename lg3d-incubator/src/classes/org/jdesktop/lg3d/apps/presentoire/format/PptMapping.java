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

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.util.Locale;
import org.apache.poi.sl.draw.DrawPaint;
import org.apache.poi.sl.usermodel.PaintStyle;
import org.apache.poi.sl.usermodel.PictureData;
import org.jdesktop.lg3d.apps.presentoire.model.ModelColor;

/**
 * Helpers the two PowerPoint codecs (XSLF for {@code .pptx}, HSLF for the legacy
 * {@code .ppt}) share, so both map colour, font size and picture type the same
 * way even though POI exposes them through different concrete classes.
 *
 * <p>Everything here is headless: {@link Color} and {@link Rectangle2D} are
 * plain value types that need no display, and POI's text and picture extraction
 * returns bytes rather than rendering anything. Geometry itself lives in
 * {@link SlideGeometry}; this class only translates the office-specific bits
 * Presentoire's vocabulary does not model directly.</p>
 */
final class PptMapping {

    /**
     * The fraction of a line box {@code SlideRenderer.fitFontSize} fills with
     * glyphs; reused on export so a re-imported deck sizes text the same way the
     * 2D renderer would have drawn it.
     */
    private static final double FONT_FILL = 0.72d;

    private PptMapping() {
        // no instances
    }

    /**
     * Reduces a POI paint style to a packed {@code 0xAARRGGBB} colour. Only a
     * solid paint has a single colour; a gradient, pattern or picture fill - and
     * an inherited "no explicit colour" style - degrades to opaque black, the
     * same fallback {@link ModelColor#parse(String)} uses, so imported text is
     * always legible on the white slide.
     *
     * @param style the run's font paint, may be null
     * @return the packed ARGB colour, never null
     */
    static int argb(PaintStyle style) {
        if (style instanceof PaintStyle.SolidPaint solid) {
            try {
                Color color = DrawPaint.applyColorTransform(solid.getSolidColor());
                if (color != null) {
                    return color.getRGB();
                }
            } catch (RuntimeException e) {
                // an exotic colour transform we cannot resolve; fall back to black
            }
        }
        return ModelColor.BLACK;
    }

    /**
     * Derives a point size for exported text from the height of its box, mirroring
     * {@code SlideRenderer.fitFontSize}: the box is split across the lines and
     * each line's glyphs fill about 72% of their band. Presentoire
     * carries no font size of its own, so height is the only signal, and this
     * keeps an export visually close to what the 2D renderer drew.
     *
     * @param box       the shape's anchor in source units (points)
     * @param lineCount the number of lines the text will occupy
     * @return a sane point size, at least 1
     */
    static double fontSize(Rectangle2D box, int lineCount) {
        double perLine = box.getHeight() / Math.max(1, lineCount);
        double size = perLine * FONT_FILL;
        if (size < 1d) {
            return 1d;
        }
        return Math.round(size * 100d) / 100d;
    }

    /**
     * Picks the POI picture type matching a picture's {@code src} extension, so an
     * exported image is stored in the container PowerPoint expects. Anything
     * unrecognised is written as PNG, the safest lossless default.
     *
     * @param src the {@code <img>} source path
     * @return the picture type, never null
     */
    static PictureData.PictureType pictureType(String src) {
        switch (DeckImages.extension(src)) {
            case "jpg":
            case "jpeg":
            case "jpe":
                return PictureData.PictureType.JPEG;
            case "gif":
                return PictureData.PictureType.GIF;
            case "bmp":
                return PictureData.PictureType.BMP;
            case "tif":
            case "tiff":
                return PictureData.PictureType.TIFF;
            case "emf":
                return PictureData.PictureType.EMF;
            case "wmf":
                return PictureData.PictureType.WMF;
            default:
                return PictureData.PictureType.PNG;
        }
    }

    /**
     * The file extension to store an imported picture under, taken from its POI
     * picture type so the {@code src} a codec invents round-trips through
     * {@link #pictureType(String)}.
     *
     * @param type the POI picture type, may be null
     * @return a lower-case extension, {@code png} when the type is null/unknown
     */
    static String extensionFor(PictureData.PictureType type) {
        if (type == null || type.extension == null || type.extension.isEmpty()) {
            return "png";
        }
        return type.extension.toLowerCase(Locale.ROOT);
    }
}
