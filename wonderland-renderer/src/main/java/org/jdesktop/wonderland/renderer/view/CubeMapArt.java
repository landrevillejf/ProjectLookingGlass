/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.5: the procedural cube map an environment program
 * samples when no shipped world's art carries one.
 *
 * No COLLADA file in the bundled worlds names a reflection map: the 2006
 * format's `<reflectivity>` channel is read by TextureBindingPlan but its
 * texture reference never resolves to a real cube-map image. So the first
 * environment material that reaches the live program needs a source, and
 * inventing one from the sky rig is the only honest answer -- a six-face
 * gradient that stands for the world's ambient hemisphere, not a photograph
 * of a specific environment no artist authored.
 *
 * The image is generated headless (java2d only, no Java 3D), the same split
 * every art class here runs on: the raster is assertable in CI, and wrapping
 * it into a TextureCubeMap is the factory's probe-side half.
 *
 * Size: 64 pixels per face, which is the smallest power-of-two that a
 * bilinear-filtered sample does not alias visibly and the largest that six
 * copies of still fit a texture-unit budget on an integrated GPU. The content
 * is a vertical gradient per face: POS_Y (zenith) deepens to the sky floor,
 * NEG_Y (nadir) flattens to the ground tone, and the four side faces carry a
 * zenith-to-horizon-to-ground gradient that reads as "somewhere with a sky."
 */
package org.jdesktop.wonderland.renderer.view;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** A procedural six-face environment cube map (roadmap P2.5). */
public final class CubeMapArt {

    /** Pixels per cube-map face; power-of-two, small enough for six copies. */
    public static final int FACE_SIZE = 64;

    /** Face order: POS_X, NEG_X, POS_Y, NEG_Y, POS_Z, NEG_Z (OpenGL convention). */
    public static final int FACES = 6;

    private CubeMapArt() {
    }

    /**
     * The six faces of the default environment gradient: a blue-to-warm horizon
     * matching the renderer's own sky ramp, flat grey at the nadir. Returns a
     * 6 * FACE_SIZE * FACE_SIZE packed ARGB int array, face-major.
     *
     * <p>Headless, pure java2d, assertable in CI. The caller wraps the result
     * into a {@code TextureCubeMap} through the factory's load path.</p>
     */
    public static int[] gradientFaces() {
        int size = FACE_SIZE;
        int[] out = new int[FACES * size * size];
        // Zenith (sky top) and horizon colours matching the renderer's SkyArt ramp.
        int zenith = 0xFF3B5998;   // a deep blue overhead
        int horizon = 0xFFB0C4DE;  // a light steel-blue band
        int ground = 0xFF696969;   // a flat grey nadir (the ground reflection)

        for (int face = 0; face < FACES; face++) {
            int offset = face * size * size;
            for (int y = 0; y < size; y++) {
                float t = (float) y / (size - 1); // 0 = top of face, 1 = bottom
                int color;
                switch (face) {
                    case 2: // POS_Y: zenith -- all deep sky
                        color = zenith;
                        break;
                    case 3: // NEG_Y: nadir -- all ground
                        color = ground;
                        break;
                    default: // side faces: zenith→horizon→ground
                        if (t < 0.5f) {
                            color = lerpColor(zenith, horizon, t * 2.0f);
                        } else {
                            color = lerpColor(horizon, ground, (t - 0.5f) * 2.0f);
                        }
                        break;
                }
                for (int x = 0; x < size; x++) {
                    out[offset + y * size + x] = color;
                }
            }
        }
        return out;
    }

    /**
     * Encode one face as a PNG byte array. Useful for the probe to dump faces
     * and a test to verify dimensions; the factory's load path does not go
     * through PNG (it reads the int[] directly into ImageComponent).
     */
    public static byte[] facePng(int faceIndex, int[] faces) {
        if (faceIndex < 0 || faceIndex >= FACES) {
            return null;
        }
        int size = FACE_SIZE;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int offset = faceIndex * size * size;
        img.setRGB(0, 0, size, size, faces, offset, size);
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (IOException impossible) {
            return null;
        }
    }

    /** Linear interpolation between two packed ARGB colours. */
    private static int lerpColor(int a, int b, float t) {
        float w = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * w);
        int g = (int) (ag + (bg - ag) * w);
        int bl = (int) (ab + (bb - ab) * w);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
