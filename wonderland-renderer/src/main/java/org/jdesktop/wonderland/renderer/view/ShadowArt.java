/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M8: the dark ellipse a standing thing sits on.
 *
 * The 2009 clinic screenshot is not convincing because of its geometry -- it is
 * convincing because every person in it is *on the floor*: there is a soft shadow
 * pool under each one, and without it a figure reads as a cut-out floating a
 * centimetre above the tiles. The original client drew that with a real shadow
 * volume; this port has no shadow caster and does not need one, because the only
 * thing it has to put on the ground is a blob whose shape never changes. So it is
 * one texture: a radial falloff from dark at the centre to nothing at the rim,
 * stretched to the figure's own footprint.
 *
 * It is art rather than geometry for the same reason the name tags are: Java 3D
 * takes alpha from a texture, the mesh factory already serves PNG bytes through
 * the TextureSource seam and switches to REPLACE + BLENDED when an image carries
 * transparent pixels, so a shadow is one quad and one entry in the same map. A
 * polygon colour with a blend factor instead would have been a hard-edged square
 * on the floor, which is the opposite of the cue.
 *
 * Like LabelArt this needs no display, so it is testable in CI.
 */
package org.jdesktop.wonderland.renderer.view;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** One soft-edged ground shadow, rasterised to PNG bytes. */
public final class ShadowArt {

    /** The raster is square; the quad it is stretched onto is not. */
    private static final int SIZE = 128;

    /** How dark the pool is at its centre: enough to read on beige tile, not a bruise. */
    private static final float CORE_ALPHA = 0.55f;

    /**
     * The shadow image, or {@code null} if an in-memory PNG write somehow failed
     * -- in which case the world is drawn without ground shadows rather than not
     * at all, the way any other missing art is handled.
     */
    public static byte[] ellipse() {
        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D d = img.createGraphics();
        try {
            d.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            // Three stops rather than a linear ramp: the middle of the pool has to
            // be a flat dark region, and only its outer half fades. A two-stop
            // falloff puts the darkest pixel at a single point, and the blob reads
            // as a ring on the floor instead of a shadow.
            d.setPaint(new RadialGradientPaint(SIZE / 2f, SIZE / 2f, SIZE / 2f,
                    new float[] {0.0f, 0.55f, 1.0f},
                    new Color[] {
                        new Color(0f, 0f, 0f, CORE_ALPHA),
                        new Color(0f, 0f, 0f, CORE_ALPHA * 0.7f),
                        new Color(0f, 0f, 0f, 0f),
                    }));
            // The gradient is bounded by the circle it fades within, so filling the
            // whole square is what puts the transparent corners in: a quad stretched
            // to a footprint has to carry nothing at its own four corners.
            d.fillRect(0, 0, SIZE, SIZE);
        } finally {
            d.dispose();
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!javax.imageio.ImageIO.write(img, "png", bytes)) {
                return null;
            }
            return bytes.toByteArray();
        } catch (IOException ex) {
            return null;
        }
    }

    private ShadowArt() {
    }
}
