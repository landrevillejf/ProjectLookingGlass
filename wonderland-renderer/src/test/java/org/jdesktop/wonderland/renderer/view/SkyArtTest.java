/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M8: the sky's one vertical ramp, and the mapping that puts it
 * on the box the right way up.
 *
 * Two claims live here, and both were paid for in renders rather than in reading.
 *
 * <p>The first is <b>direction</b>. The renderer's one texture upload hands a
 * picture to Java 3D origin-at-upper-left, which samples its first row at
 * {@code v = 1}; that is the layout every card here is authored against, and it is
 * the same fact that decides which way up a name tag reads. So the zenith, which
 * the ramp paints at the top of its image, must be sampled at {@code v = 1}, which
 * is the top of the box. Mapping it the other way is a sky with its ground haze on
 * its ceiling and its zenith under its floor.</p>
 *
 * <p>The second is <b>the seam</b>. Java 3D's default wrap mode is REPEAT, which
 * makes {@code v = 1} the very same texel as {@code v = 0}. The box's floor card is
 * the degenerate case: every one of its six corners sits at one ramp end, so the
 * whole card came back that end's colour -- and with the ceiling on the seam, that
 * end was the zenith. That is what a person sees as a world whose sky is upside
 * down, and it is why the two ends are inset rather than landing on 0 and 1:
 * measured off the meeting complex, whose lower half was a flat (56,107,184) and is
 * now the ramp's ground haze at (158,168,174).</p>
 *
 * <p>Neither is visible in a world with ground of its own, which is exactly why one
 * survived a full sweep unnoticed: the frame fills with the box's own faces only when
 * there is nothing else to look at.</p>
 */
package org.jdesktop.wonderland.renderer.view;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SkyArtTest {

    /** The inset from each ramp end, mirrored from the class under test. */
    private static final float INSET = 1f / 256f;

    // ---- where the ramp lands on the box ---------------------------------

    @Test
    void theCeilingIsTheTopOfTheRampAndTheFloorIsItsBottom() {
        assertThat(SkyArt.rowOf(10f, 10f))
                .as("the box's ceiling takes the ramp's first rows, where the zenith is")
                .isGreaterThan(0.99f);
        assertThat(SkyArt.rowOf(0f, 10f))
                .as("the box's middle is the horizon")
                .isCloseTo(0.5f, within(1e-6f));
        assertThat(SkyArt.rowOf(-10f, 10f))
                .as("the box's floor takes the ramp's last rows, where the haze is")
                .isCloseTo(INSET, within(1e-6f));
    }

    @Test
    void noFaceIsEverPinnedToTheWrapSeam() {
        // The REPEAT bug's pin: v = 0 and v = 1 are the same texel under REPEAT, so a
        // value that touches either hands a floor card the sky's zenith. Nothing this
        // method returns may sit on the seam, whatever the corner's height -- including
        // a corner outside the box, which is what a stretched sky can produce.
        for (float half : new float[] {0.5f, 1f, 130.1f, 1e4f}) {
            for (float y = -3f * half; y <= 3f * half; y += half / 7f) {
                float v = SkyArt.rowOf(y, half);
                assertThat(v).as("rowOf(" + y + ", " + half + ')')
                        .isGreaterThanOrEqualTo(INSET)
                        .isLessThanOrEqualTo(1f - INSET);
            }
        }
        assertThat(SkyArt.rowOf(1e6f, 1f)).isLessThan(1f).isGreaterThan(0f);
        assertThat(SkyArt.rowOf(-1e6f, 1f)).isLessThan(1f).isGreaterThan(0f);
    }

    @Test
    void theRampRunsUpwardsAsTheBoxRises() {
        // Monotonic, and in the upload's direction: the shared texture upload
        // samples a picture's first row at v = 1, so v must grow with height or the
        // side cards show the gradient running downhill -- a sky whose haze sits
        // overhead. The side cards are what a person reads as sky.
        float previous = Float.NEGATIVE_INFINITY;
        for (float y = -10f; y <= 10f; y += 1f) {
            float v = SkyArt.rowOf(y, 10f);
            assertThat(v).as("rowOf(" + y + ", 10)").isGreaterThan(previous);
            previous = v;
        }
    }

    @Test
    void aBoxWithNoHeightIsOneColourAndThatColourIsTheHorizon() {
        assertThat(SkyArt.rowOf(0f, 0f)).isEqualTo(0.5f);
        assertThat(SkyArt.rowOf(1f, -1f)).as("a negative half-extent is the same degeneracy")
                .isEqualTo(0.5f);
        assertThat(SkyArt.rowOf(1f, Float.NaN)).isEqualTo(0.5f);
        assertThat(SkyArt.rowOf(1f, Float.POSITIVE_INFINITY)).isEqualTo(0.5f);
    }

    // ---- the ramp itself -------------------------------------------------

    @Test
    void theRampIsAPngWhoseFirstRowIsTheZenithAndWhoseLastIsGroundHaze()
            throws IOException {
        byte[] png = SkyArt.ramp();
        assertThat(png).as("an in-memory PNG write that fails is a flat sky, not a crash")
                .isNotNull();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(img).isNotNull();
        assertThat(img.getHeight()).as("the ramp's steps are its whole content").isGreaterThan(64);

        int top = img.getRGB(img.getWidth() / 2, 0);
        int bottom = img.getRGB(img.getWidth() / 2, img.getHeight() - 1);
        assertThat(red(top)).as("row 0 is the deep overhead blue").isLessThan(red(bottom));
        assertThat(blue(top)).as("and it is blue, not grey").isGreaterThan(red(top));
        assertThat(red(bottom)).as("the last row is the pale ground haze")
                .isGreaterThan(red(top) + 60);
        assertThat(green(bottom)).as("haze is desaturated, so green and red nearly agree")
                .isCloseTo(red(bottom), within(12));
    }

    @Test
    void theHorizonSitsAboveTheBottomSoNoBrightBandLiesUnderTheFloor() {
        // A gradient that reaches its pale end at the very bottom of the box puts a
        // bright band under the floor, which is the one thing that makes an outdoor
        // world read as being inside a lit box. So the pale end is at three quarters.
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(SkyArt.ramp()));
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
        int pale = red(img.getRGB(img.getWidth() / 2, (int) (img.getHeight() * 0.75)));
        int below = red(img.getRGB(img.getWidth() / 2, img.getHeight() - 1));
        assertThat(pale).as("the brightest row is the horizon, not the last row")
                .isGreaterThan(below);
    }

    private static int red(int argb) {
        return (argb >> 16) & 0xFF;
    }

    private static int green(int argb) {
        return (argb >> 8) & 0xFF;
    }

    private static int blue(int argb) {
        return argb & 0xFF;
    }
}
