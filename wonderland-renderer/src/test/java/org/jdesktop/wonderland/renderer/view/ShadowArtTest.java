/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M8: the ground shadow every standing thing sits on.
 *
 * The raster is the whole of what this class decides, so the whole of the test
 * is about the raster: that it is a PNG Java 3D can take, that it carries the
 * transparent pixels the mesh factory looks for before it chooses REPLACE over
 * DECAL, and that the darkness is in the middle and gone at the edges, which is
 * the difference between a shadow and a black square.
 */
package org.jdesktop.wonderland.renderer.view;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ShadowArtTest {

    private static BufferedImage raster() {
        byte[] png = ShadowArt.ellipse();
        assertThat(png).as("the shadow art is what the pool is drawn from").isNotNull();
        assertThat(LabelArt.hasAlpha(png))
                .as("a fully opaque pool would hide the floor it lies on")
                .isTrue();
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (Exception ex) {
            throw new AssertionError("the shipped shadow bytes are not a readable PNG", ex);
        }
    }

    private static int alphaAt(BufferedImage img, int x, int y) {
        return (img.getRGB(x, y) >>> 24) & 0xFF;
    }

    @Test
    void thePoolIsDarkestUnderTheFeetAndNowhereElse() {
        BufferedImage img = raster();
        int centre = img.getWidth() / 2;
        assertThat(alphaAt(img, centre, centre))
                .as("the middle of the pool is a flat dark region, not a point")
                .isGreaterThan(80);
        assertThat(alphaAt(img, img.getWidth() - 2, centre))
                .as("the rim fades to nothing, or the quad reads as a decal")
                .isLessThan(alphaAt(img, centre, centre) / 4);
    }

    @Test
    void theCornersCarryNothingBecauseTheQuadIsWiderThanItIsTall() {
        // A shadow stretched to a footprint is not a circle; whatever lands in the
        // quad's corners must be transparent or the pool shows a square edge.
        BufferedImage img = raster();
        assertThat(alphaAt(img, 1, 1)).isZero();
        assertThat(alphaAt(img, img.getWidth() - 2, 1)).isZero();
        assertThat(alphaAt(img, 1, img.getHeight() - 2)).isZero();
        assertThat(alphaAt(img, img.getWidth() - 2, img.getHeight() - 2)).isZero();
    }

    @Test
    void theEllipseIsWiderThanItIsTallOnlyInTheQuadNotInTheRaster() {
        BufferedImage img = raster();
        assertThat(img.getWidth()).as("the raster is square; the quad stretches it").isEqualTo(img.getHeight());
    }
}
