/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.3: the water look and its scroll maths, headless.
 *
 * Two claims are safe to assert without a display and one is deliberately
 * absent. Asserted: the tile is a decodable, edge-continuous raster (a
 * scrolling texture whose wrap shows a seam is the failure this art exists to
 * avoid), and the scroll offset is bounded arithmetic with a fail-still clock
 * rule. NOT asserted anywhere below: that a pond renders -- placing the quad
 * and driving TexCoordGeneration from a frame clock are Java 3D calls the
 * in-JVM probe owns, and until a probe run paints one this suite reports the
 * water as re-honoured DATA, not as a verified pixel.
 */
package org.jdesktop.wonderland.renderer.view;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.jdesktop.wonderland.modules.asset.WaterInstance;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WaterArtTest {

    private static BufferedImage tileImage() {
        byte[] png = WaterArt.tile();
        assertThat(png).as("the water raster must always encode").isNotNull();
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (Exception ex) {
            throw new AssertionError("the shipped water bytes are not a readable PNG", ex);
        }
    }

    @Test
    void theTileIsASquareRgbRaster() {
        BufferedImage img = tileImage();

        assertThat(img.getWidth()).isEqualTo(img.getHeight());
        assertThat(img.getWidth()).isGreaterThanOrEqualTo(64);
    }

    @Test
    void theTileWrapsHorizontallyWithNoEdgeDiscontinuity() {
        // The seam test a scrolling texture cannot pass by luck: the column at
        // x = width-1 and the column at x = 0 must be neighbours of the SAME
        // wave, so a wrap mid-scroll shows no line. Per-pixel-exact is too
        // strict (antialiasing widens the crest differently at each edge);
        // an aggregate channel bound is what "continuous" means for band art.
        BufferedImage img = tileImage();
        int w = img.getWidth();
        int h = img.getHeight();
        long delta = 0;
        for (int y = 0; y < h; y++) {
            int left = img.getRGB(0, y);
            int right = img.getRGB(w - 1, y);
            delta += Math.abs(((left >> 16) & 0xff) - ((right >> 16) & 0xff));
            delta += Math.abs(((left >> 8) & 0xff) - ((right >> 8) & 0xff));
            delta += Math.abs((left & 0xff) - (right & 0xff));
        }
        long meanPerChannel = delta / (3L * h);

        assertThat(meanPerChannel).as("left edge continues into the right edge")
                .isLessThan(48);
    }

    @Test
    void theTileWrapsVerticallyForTheBandPattern() {
        // The top and bottom rows share the band family (a crest that leaves
        // the tile re-enters from the other side -- paintBands draws the
        // wrapped copies for exactly this); the depth GRADIENT under it
        // legitimately differs, so the bound is looser than the horizontal
        // one and is about the bands, not the shade.
        BufferedImage img = tileImage();
        int w = img.getWidth();
        long delta = 0;
        for (int x = 0; x < w; x++) {
            delta += Math.abs(luma(img.getRGB(x, 0)) - luma(img.getRGB(x, img.getHeight() - 1)));
        }
        long meanLuma = delta / w;

        assertThat(meanLuma).as("the band pattern is the family at top and bottom")
                .isLessThan(120);
    }

    private static int luma(int rgb) {
        return (((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff)) / 3;
    }

    @Test
    void theScrollOffsetWrapsEveryTileAndNeverGrows() {
        // One full tile at the shipped speed, the wrap after it, and the far
        // future a frame clock eventually reaches: the offset must stay in
        // [0, 1) forever -- the modulo is the difference between a scroll and
        // a number that outgrows float precision and eats the ripple.
        double period = 1.0 / WaterArt.SCROLL_SPEED_U;

        assertThat(WaterArt.offsetAt(0, WaterArt.SCROLL_SPEED_U)).isZero();
        assertThat(WaterArt.offsetAt(period, WaterArt.SCROLL_SPEED_U))
                .as("one whole tile wraps to its start; a hair off is float, not a seam")
                .isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(WaterArt.offsetAt(period * 37 + 2, WaterArt.SCROLL_SPEED_U))
                .isBetween(0.0, 1.0);
        assertThat(WaterArt.scrollUAt(1e9)).isBetween(0.0, 1.0);
        assertThat(WaterArt.scrollVAt(1e9)).isBetween(0.0, 1.0);
    }

    @Test
    void theTwoAxesScrollAtDifferentRates() {
        // Identical speeds would read as one flat slide; the crossing drift is
        // what sells the surface as water rather than a moving texture.
        assertThat(WaterArt.SCROLL_SPEED_U).isNotEqualTo(WaterArt.SCROLL_SPEED_V);
        assertThat(WaterArt.scrollUAt(10)).isNotEqualTo(WaterArt.scrollVAt(10));
    }

    @Test
    void aBrokenClockLeavesTheSurfaceStillRatherThanNanning() {
        assertThat(WaterArt.offsetAt(Double.NaN, 0.04)).isZero();
        assertThat(WaterArt.offsetAt(Double.POSITIVE_INFINITY, 0.04)).isZero();
        assertThat(WaterArt.offsetAt(10, Double.NaN)).isZero();
        assertThat(WaterArt.offsetAt(-5, 0.04)).as("a clock that ran backwards").isZero();
    }

    @Test
    void aRecordFoldsToThePlaneItsQuadShouldOccupy() {
        // The data half of P2.3 reaching the renderer half: centre, and the
        // extent with the instance's horizontal scale folded in. Y scale is
        // deliberately not a plane extent -- water lies flat.
        WaterInstance record = new WaterInstance("Pond",
                4, -1, 7, 0, 1, 0, 0, 2, 1, 3, 60, 40, "Ground");

        double[] plane = WaterArt.planeOf(record);

        assertThat(plane).containsExactly(4, -1, 7, 60, 60);
    }
}
