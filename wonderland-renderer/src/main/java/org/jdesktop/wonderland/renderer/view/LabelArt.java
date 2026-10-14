/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M8: a name tag as art the scene graph can hold.
 *
 * The reference screenshot of the 2009 outpatient clinic is not legible because
 * of its geometry: it is legible because every person has a name over their head
 * and the lobby has a directory board listing the rooms. Wonderland drew those as
 * its own billboarded text; this port has no text renderer, and Java 3D's
 * {@code Text3D} needs a {@code Font3D} loaded from a .jav file no one ships
 * here. So the label is rasterised the way the rest of the world already is: Java
 * 2D paints the glyphs into an ARGB image, the image becomes a PNG, and the PNG is
 * served to {@link org.jdesktop.wonderland.renderer.j3d.J3dMeshFactory} through
 * the same {@code TextureSource} seam a module jar's textures arrive by. One
 * pipeline, one set of alpha rules, and a label that is a texture like any other.
 *
 * Java 2D text needs no display (headless font rendering is a JDK capability, not
 * an X one), so this class is testable in CI -- unlike everything that touches the
 * scene graph. The outline is not decoration: the tags sit on white walls, blue
 * dado, beige carpet and a black directory board in the same view, so the ink and
 * its halo are opposite ends of the scale -- whichever surface a tag lands on,
 * one of the two carries it. That is why the halo is stroked rather than the text
 * dropped on its own.
 */
package org.jdesktop.wonderland.renderer.view;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** One piece of text, rasterised to PNG bytes with the aspect ratio it came out at. */
public final class LabelArt {

    /** The glyph height in pixels; only the ratio to it matters, the tags are metres-tall. */
    private static final int FONT_SIZE = 40;

    /** The typeface: plain and heavy, the way the 2009 tags read at a distance. */
    private static final Font FONT = new Font(Font.SANS_SERIF, Font.BOLD, FONT_SIZE);

    /** Pixels of clear space around the text, so the outline is not clipped. */
    private static final int PAD = 8;

    /** The stroke width of the light halo around each glyph. */
    private static final float OUTLINE = 5.5f;

    /**
     * The text colour: black, as the 2009 client drew it. White was the first
     * guess and it is wrong twice over -- the tags sit on white walls most of all,
     * and a white glyph on a white wall is not a label, it is a smudge.
     */
    private static final Color INK = new Color(0x10, 0x10, 0x10);

    /**
     * The halo colour: white. It is what makes the black glyphs readable on the
     * one surface a clinic has most of -- a black directory board -- where the ink
     * alone would vanish and only its outline survives.
     */
    private static final Color HALO = new Color(0xF7, 0xF7, 0xF7);

    /**
     * A rasterised label: its PNG bytes and the width/height it came out at.
     *
     * @param png    the ARGB PNG to serve as a texture
     * @param width  the image width in pixels
     * @param height the image height in pixels
     */
    public record Image(byte[] png, int width, int height) {

        /** width / height -- how wide a tag of a given metre-height has to be. */
        public double aspect() {
            return height == 0 ? 1.0 : (double) width / (double) height;
        }
    }

    private LabelArt() {
    }

    /**
     * Rasterise {@code text}, one line per embedded {@code \n}, as the reference
     * board's list is. Blank or null text yields null: a cell the server did not
     * name gets no tag rather than an empty white card.
     */
    public static Image of(String text) {
        List<String> lines = linesOf(text);
        if (lines.isEmpty()) {
            return null;
        }
        // The metrics come from a scratch image; Java 2D will not answer font
        // questions without a device, and a 1x1 buffer is the cheapest device
        // there is (and works headless).
        BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scratch.createGraphics();
        try {
            g.setFont(FONT);
            FontMetrics fm = g.getFontMetrics();
            int leading = Math.max(1, fm.getHeight());
            int widest = 0;
            for (String line : lines) {
                widest = Math.max(widest, fm.stringWidth(line));
            }
            int width = widest + 2 * PAD;
            int height = lines.size() * leading + 2 * PAD;
            BufferedImage img = new BufferedImage(Math.max(1, width),
                    Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
            Graphics2D d = img.createGraphics();
            try {
                d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                d.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                d.setFont(FONT);
                // The halo is the glyph outline filled under a wide round-joined
                // stroke, then the ink is drawn on top of it. Stroking the string
                // itself is not an option -- Graphics2D does not stroke text -- and
                // offsetting copies of it leaves seams at the joins on a bold face
                // at this size.
                d.setColor(HALO);
                d.setStroke(new java.awt.BasicStroke(OUTLINE,
                        java.awt.BasicStroke.CAP_ROUND,
                        java.awt.BasicStroke.JOIN_ROUND));
                int baseline = PAD + fm.getAscent();
                for (String line : lines) {
                    // Centred per line, so a directory board's list stays tidy even
                    // when one room name is much longer than its neighbours.
                    int x = (width - fm.stringWidth(line)) / 2;
                    java.awt.Shape glyphs = outline(line, x, baseline, d);
                    // Both, not one: filling the outline is the glyph body and
                    // stroking it is the ring around it. Filling alone -- which is
                    // what this did first, and what the probe caught -- paints a
                    // halo exactly the size of the ink it then hides, so the tag
                    // arrives with no outline at all.
                    d.draw(glyphs);
                    d.fill(glyphs);
                    baseline += leading;
                }
                d.setStroke(new java.awt.BasicStroke(1f));
                d.setColor(INK);
                int ink = PAD + fm.getAscent();
                for (String line : lines) {
                    d.drawString(line, (width - fm.stringWidth(line)) / 2, ink);
                    ink += leading;
                }
            } finally {
                d.dispose();
            }
            byte[] encoded = png(img);
            return encoded == null ? null : new Image(encoded, width, height);
        } finally {
            g.dispose();
        }
    }

    /** The glyph outline of one line, for the halo pass; empty when the font cannot say. */
    private static java.awt.Shape outline(String line, int x, int baseline, Graphics2D d) {
        return d.getFont().createGlyphVector(d.getFontRenderContext(), line)
                .getOutline(x, baseline);
    }

    /** The non-blank lines, in order; a label of nothing at all has none. */
    private static List<String> linesOf(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bytes);
            return bytes.toByteArray();
        } catch (IOException ex) {
            // An in-memory PNG write cannot fail on a BufferedImage the JVM just
            // painted; a label that cannot be encoded is a label that is not shown.
            return null;
        }
    }

    /** Whether an encoded image carries at least one transparent pixel. */
    public static boolean hasAlpha(byte[] pngBytes) {
        if (pngBytes == null || pngBytes.length == 0) {
            return false;
        }
        try {
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(pngBytes));
            if (img == null || !img.getColorModel().hasAlpha()) {
                return false;
            }
            for (int y = 0; y < img.getHeight(); y += 2) {
                for (int x = 0; x < img.getWidth(); x += 2) {
                    if ((img.getRGB(x, y) >>> 24) != 0xFF) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * The label's own footprint in metres, given the height it is shown at: the
     * width follows the aspect, because a long room name needs a long tag.
     */
    public static double widthForMeters(Image image, double heightMeters) {
        return image == null ? 0.0 : heightMeters * image.aspect();
    }
}
