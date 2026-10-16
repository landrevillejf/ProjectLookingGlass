/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.5: the procedural environment cube map, headless.
 *
 * Two claims are safe to assert without a display and one is deliberately
 * absent. Asserted: the six faces are the layout and the gradient the class
 * promises (a zenith face, a nadir face, four side faces running sky -> horizon
 * -> ground, every pixel opaque), and the PNG encoder round-trips a face back
 * to those same pixels. NOT asserted anywhere below: that an environment
 * material actually reflects on screen -- wrapping these faces into a
 * {@code TextureCubeMap} and binding it to a {@code ShaderAppearance} are Java
 * 3D calls that need a live GL context, so the painted reflection belongs to
 * the in-JVM probe, exactly as P2.2/P2.3/P3.2 left their on-screen halves. This
 * suite proves the SOURCE exists and is well-formed; it does not claim a glossy
 * cell was measured glowing.
 */
package org.jdesktop.wonderland.renderer.view;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CubeMapArtTest {

    // The three tones the class paints with, spelled here so a change to the
    // source that moves a colour without moving the test is caught, and vice
    // versa. POS_Y is all zenith, NEG_Y all ground, the sides ramp between them.
    private static final int ZENITH = 0xFF3B5998;
    private static final int HORIZON = 0xFFB0C4DE;
    private static final int GROUND = 0xFF696969;

    private static final int POS_Y = 2; // zenith
    private static final int NEG_Y = 3; // nadir

    @Test
    void theFacesArePowerOfTwoSixCopiesPackedFaceMajor() {
        int[] faces = CubeMapArt.gradientFaces();

        assertThat(CubeMapArt.FACE_SIZE)
                .as("the smallest bilinear-filtered size that does not alias")
                .isEqualTo(64);
        assertThat((CubeMapArt.FACE_SIZE & (CubeMapArt.FACE_SIZE - 1)))
                .as("power of two, so a mip chain halves cleanly")
                .isZero();
        assertThat(faces)
                .as("face-major: FACES copies of size*size")
                .hasSize(CubeMapArt.FACES * CubeMapArt.FACE_SIZE * CubeMapArt.FACE_SIZE);
    }

    @Test
    void everyPixelIsOpaque() {
        // A cube map with a transparent face would let the world bleed through an
        // environment lookup; the sky has no holes, so neither does the raster.
        int[] faces = CubeMapArt.gradientFaces();

        for (int argb : faces) {
            assertThat(argb >>> 24).as("alpha is full").isEqualTo(0xFF);
        }
    }

    @Test
    void theZenithFaceIsSolidSky() {
        int[] faces = CubeMapArt.gradientFaces();
        int base = POS_Y * CubeMapArt.FACE_SIZE * CubeMapArt.FACE_SIZE;

        for (int i = 0; i < CubeMapArt.FACE_SIZE * CubeMapArt.FACE_SIZE; i++) {
            assertThat(faces[base + i])
                    .as("POS_Y reads as the deep overhead blue everywhere")
                    .isEqualTo(ZENITH);
        }
    }

    @Test
    void theNadirFaceIsSolidGround() {
        int[] faces = CubeMapArt.gradientFaces();
        int base = NEG_Y * CubeMapArt.FACE_SIZE * CubeMapArt.FACE_SIZE;

        for (int i = 0; i < CubeMapArt.FACE_SIZE * CubeMapArt.FACE_SIZE; i++) {
            assertThat(faces[base + i])
                    .as("NEG_Y reads as the flat ground reflection everywhere")
                    .isEqualTo(GROUND);
        }
    }

    @Test
    void aSideFaceRunsZenithToHorizonToGroundTopToBottom() {
        int size = CubeMapArt.FACE_SIZE;
        int[] faces = CubeMapArt.gradientFaces();
        // Face 0 is POS_X, a side face; read its first column top/middle/bottom.
        // Only the two endpoints are colour-exact -- t = y/(size-1) never lands
        // on 0.5 at an integer row -- so the middle is asserted as "near the
        // horizon band," not equal to it, which is what the ramp actually is.
        int base = 0;

        assertThat(faces[base + 0 * size])
                .as("the top row starts exactly at the zenith")
                .isEqualTo(ZENITH);
        assertThat(faces[base + (size - 1) * size])
                .as("the bottom row lands exactly on the ground")
                .isEqualTo(GROUND);
        assertThat(near(faces[base + (size / 2) * size], HORIZON))
                .as("the middle row sits at the horizon band")
                .isTrue();
    }

    /** Whether two packed ARGB colours are within a few units per channel. */
    private static boolean near(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) <= 8
                && Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) <= 8
                && Math.abs((a & 0xFF) - (b & 0xFF)) <= 8;
    }

    @Test
    void aSideFaceIsFlatAcrossItsRows() {
        // The gradient is a function of height alone, so one row is one colour;
        // a per-column drift here would read as a painted light source that the
        // hemisphere does not have.
        int size = CubeMapArt.FACE_SIZE;
        int[] faces = CubeMapArt.gradientFaces();
        int row = size / 4; // inside the zenith -> horizon ramp
        int base = row * size;
        int first = faces[base];

        for (int x = 1; x < size; x++) {
            assertThat(faces[base + x]).as("row is one colour").isEqualTo(first);
        }
    }

    @Test
    void allFourSideFacesAgreeOnTheSameRamp() {
        // POS_X, NEG_X, POS_Z, NEG_Z (0, 1, 4, 5) share the vertical ramp so the
        // horizon is continuous around the box; only the two caps differ.
        int size = CubeMapArt.FACE_SIZE;
        int[] faces = CubeMapArt.gradientFaces();
        int[] sides = {0, 1, 4, 5};

        for (int face : sides) {
            int base = face * size * size;
            assertThat(faces[base])
                    .as("side face %d tops at the zenith", face)
                    .isEqualTo(ZENITH);
            assertThat(faces[base + (size - 1) * size])
                    .as("side face %d bottoms at the ground", face)
                    .isEqualTo(GROUND);
        }
    }

    @Test
    void aFaceEncodesToPngThatReadsBackTheSamePixels() {
        // The probe dumps faces to disk to be looked at; the encoder must be a
        // faithful round trip or a "what did the environment look like" capture
        // would lie about the raster.
        int size = CubeMapArt.FACE_SIZE;
        int[] faces = CubeMapArt.gradientFaces();
        byte[] png = CubeMapArt.facePng(POS_Y, faces);

        assertThat(png).as("a valid face always encodes").isNotNull();
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(png));
        } catch (Exception ex) {
            throw new AssertionError("the encoded face is not a readable PNG", ex);
        }
        assertThat(img.getWidth()).isEqualTo(size);
        assertThat(img.getHeight()).isEqualTo(size);
        assertThat(img.getRGB(0, 0)).as("decoded zenith matches the source").isEqualTo(ZENITH);
    }

    @Test
    void anOutOfRangeFaceYieldsNoImage() {
        int[] faces = CubeMapArt.gradientFaces();

        assertThat(CubeMapArt.facePng(-1, faces)).as("below the first face").isNull();
        assertThat(CubeMapArt.facePng(CubeMapArt.FACES, faces)).as("past the last face").isNull();
    }
}
