/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.3: the water a JMEWaterInstance record asked for.
 *
 * The 2006 mtgame engine answered a scene's {@code JMEWaterInstance} with an
 * animated ground shader -- a scrolling normal-mapped surface, jME's own water
 * material. This port has no mtgame and, after the P2.2 reading, no shader
 * path either that a probe has validated. What it DOES have, proven by every
 * name tag and shadow pool since M6: the fixed pipeline texturing a quad with
 * a served PNG, and a TexCoordGeneration the probe can drive -- which means
 * the honest re-honour of the dropped record is not the engine's shader, it
 * is the WATER LOOK as a moving texture: one seamless tile whose bands scroll,
 * drawn on the plane the record names.
 *
 * Like ShadowArt and LabelArt this class rasterises and computes; nothing in
 * it needs a display, so the look and the motion are testable in CI. Placing
 * the quad and wiring the scroll to the frame clock is the probe-side step,
 * documented at {@link #offsetAt} -- the same seam split the whole renderer
 * runs on.
 */
package org.jdesktop.wonderland.renderer.view;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** The procedural water tile and its scroll maths (roadmap P2.3). */
public final class WaterArt {

    /** The tile is square and seamless; its on-world size is the record's. */
    private static final int SIZE = 128;

    /** The engine's water was a dark blue-green; these are its own family. */
    private static final Color DEEP = new Color(28, 68, 96);
    private static final Color SHALLOW = new Color(56, 120, 148);
    private static final Color GLINT = new Color(150, 200, 220, 90);

    /** How far a tile edge travels per second on the primary axis, world units. */
    public static final double SCROLL_SPEED_U = 0.04;

    /** The crossing axis drifts slower, so the motion never reads as a slide. */
    public static final double SCROLL_SPEED_V = 0.017;

    private WaterArt() {
    }

    /**
     * The seamless water tile as PNG bytes, or {@code null} when an in-memory
     * write somehow failed -- a world without its ponds beats a world that does
     * not load, the same rule every other art raster follows.
     */
    public static byte[] tile() {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            paintBase(g);
            paintBands(g);
        } finally {
            g.dispose();
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException ex) {
            return null;
        }
    }

    /** A vertical depth gradient: every tile edge is a pure colour row. */
    private static void paintBase(Graphics2D g) {
        g.setPaint(new GradientPaint(0, 0, SHALLOW, 0, SIZE, DEEP));
        g.fillRect(0, 0, SIZE, SIZE);
    }

    /**
     * Three families of horizontal glint bands. Each sine completes an
     * integer number of periods across the tile, each family's vertical
     * period divides the height, and every band is also drawn one tile over
     * on each side -- so left meets right AND top meets bottom with no seam.
     * Seamlessness is the one property a SCROLLING texture cannot fake: a
     * non-seamless tile shows every wrap as a visible line crossing the pond.
     */
    private static void paintBands(Graphics2D g) {
        for (int band = 0; band < 3; band++) {
            int periods = band + 1;                     // integer wrap: seamless
            int rowPeriod = SIZE / (band + 1);          // divides the height
            int amplitude = 6 + 4 * band;
            float width = 2.0f + band;
            g.setColor(new Color(GLINT.getRed(), GLINT.getGreen(), GLINT.getBlue(),
                    60 - 15 * band));
            for (int row = 0; row < SIZE; row += rowPeriod) {
                // the band, then its two wrapped copies: a crest that overflows
                // the tile's top re-enters from its bottom, which is what makes
                // the vertical edge continuous as well as the horizontal one
                drawWave(g, row, periods, amplitude, width);
                drawWave(g, row - SIZE, periods, amplitude, width);
                drawWave(g, row + SIZE, periods, amplitude, width);
            }
        }
    }

    /** One horizontal sine of light, wrapped at the tile edge by construction. */
    private static void drawWave(Graphics2D g, int baseline, int periods,
                                 int amplitude, float stroke) {
        g.setStroke(new java.awt.BasicStroke(stroke));
        int previousX = 0;
        int previousY = waveY(baseline, 0, periods, amplitude);
        for (int x = 2; x <= SIZE; x += 2) {
            int y = waveY(baseline, x, periods, amplitude);
            g.drawLine(previousX, previousY, x, y);
            // draw the wrapped head of the wave too, so the crossing at x=0
            // continues into the tile's left edge rather than restarting
            g.drawLine(previousX - SIZE, previousY, x - SIZE, y);
            previousX = x;
            previousY = y;
        }
    }

    private static int waveY(int baseline, int x, int periods, int amplitude) {
        return baseline + (int) Math.round(
                amplitude * Math.sin(2 * Math.PI * periods * x / (double) SIZE));
    }

    /**
     * The texture-coordinate offset after {@code elapsedSeconds} -- the pure
     * maths the probe feeds to {@code TexCoordGeneration}'s plane constants.
     *
     * <p>The wrap is what makes the animation a scroll and not a drift: the
     * offset is modulo one tile, so the surface travels forever without the
     * numbers growing (a live renderer that forgot the modulo would one day
     * hand GLSL huge texture coordinates and precision would eat the ripple).
     * Applying this offset belongs to the Java 3D seam -- a TexCoordGeneration
     * rebuilt per frame or a translate composed into the plane coefficients --
     * and is asserted here only as arithmetic, because a frame clock is exactly
     * what a headless test may not claim.</p>
     */
    public static double offsetAt(double elapsedSeconds, double speedPerSecond) {
        if (!(elapsedSeconds >= 0) || !Double.isFinite(elapsedSeconds)
                || !Double.isFinite(speedPerSecond)) {
            return 0.0; // a broken clock leaves the surface still, never NaN-moving
        }
        double offset = (elapsedSeconds * speedPerSecond) % 1.0;
        return offset < 0 ? offset + 1.0 : offset;
    }

    /** The primary scroll offset for a frame time. */
    public static double scrollUAt(double elapsedSeconds) {
        return offsetAt(elapsedSeconds, SCROLL_SPEED_U);
    }

    /** The crossing drift for a frame time. */
    public static double scrollVAt(double elapsedSeconds) {
        return offsetAt(elapsedSeconds, SCROLL_SPEED_V);
    }

    /**
     * The world rectangle the record names: centre and half-extents, the
     * instance's own horizontal scale folded in. Pure arithmetic on the
     * loaded data -- the probe's quad (and the texture-coordinate scroll
     * above) are built from exactly these five numbers, so the pond lands
     * where the author put it without anyone re-reading the {@code .mtg}
     * line twice. Water lies flat: the record's Y scale is not a plane
     * extent and is deliberately not folded here.
     *
     * @param record one {@link org.jdesktop.wonderland.modules.asset.WaterInstance}
     * @return {centreX, centreY, centreZ, halfWidth, halfLength}
     */
    public static double[] planeOf(org.jdesktop.wonderland.modules.asset.WaterInstance record) {
        return new double[] {
                record.tx(), record.ty(), record.tz(),
                record.halfWidth() * record.scaleX(),
                record.halfLength() * record.scaleZ(),
        };
    }
}
