/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer M8: the sky, as one vertical ramp.
 *
 * The first sky this port drew was six flat cards of a single blue, and it is the
 * reason a rendered world looked like a box of bricks sitting on a board: nothing
 * in a real view of an outdoor place is one colour. The horizon is pale, the
 * zenith is deep, and it is that gradient -- not the hue -- that tells an eye the
 * space above the buildings is open rather than painted.
 *
 * So the sky is a texture, not a colour. That choice is forced twice over by this
 * stack: a {@code Background} paints nothing here at all, and a mesh has no
 * per-vertex colour channel to shade a dome with (see {@link ColladaMeshData}).
 * But a textured quad is the one thing the renderer demonstrably fills -- it is
 * what every shipped model is made of, and what the name tags and the ground
 * shadows are made of -- so the sky is the same flat card with a ramp on it. The
 * cards stay where the measured box of them left them, wound the same way, and
 * only their colour changes.
 *
 * The ramp is one column, stretched across each face by the vertex's own height:
 * a face's texture coordinate is where its corner sits between the world's floor
 * and its ceiling, so the four side cards read as a continuous gradient and the
 * top and bottom cards land on the ramp's two ends. That is also why the image is
 * a gradient and not a picture of a cloud -- a sky has to tile at any aspect, and
 * a horizon line drawn by an artist would be straight in a box whose faces are
 * nowhere near the same size.
 *
 * Like {@link LabelArt} and {@link ShadowArt} this needs no display, so it is
 * testable in CI.
 */
package org.jdesktop.wonderland.renderer.view;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** One daytime sky ramp, rasterised to PNG bytes for the sky's own cards. */
public final class SkyArt {

    /** How tall the ramp is. The faces stretch it to any height, so only its steps matter. */
    private static final int HEIGHT = 256;

    /** How wide the ramp is. One would do; four keeps the filtering honest at a seam. */
    private static final int WIDTH = 4;

    /** The zenith: deep, slightly desaturated blue, the colour overhead at midday. */
    private static final Color ZENITH = new Color(0.22f, 0.42f, 0.72f);

    /**
     * The horizon: pale and near-white with a little warmth in it, because that is
     * where the sky meets the ground and it is the band a person actually reads a
     * world against. The top of the image is the zenith and the bottom this, which
     * is the same order the sky cards' texture coordinates run in -- the image's
     * first row is sampled at {@code v = 1}, and {@code v = 1} is the top of the
     * box; see {@link #rowOf}.
     */
    private static final Color HORIZON = new Color(0.78f, 0.86f, 0.93f);

    /** The band just under the horizon: a touch of ground haze, so the join is not a cliff. */
    private static final Color GROUND_HAZE = new Color(0.62f, 0.66f, 0.68f);

    /**
     * How far inside the ramp's two ends a sky corner is allowed to sit.
     *
     * <p>The ramp is not a tile, but Java 3D's default wrap mode is REPEAT, which
     * makes {@code v = 1} the very same texel as {@code v = 0}. The box's floor card
     * is the degenerate case that exposes it: every one of its corners is at exactly
     * one end of the ramp, so the whole card came back that end's colour -- the
     * sky's own zenith, painted on the ground. That is the second, and real, "ciel à
     * l'envers": the meeting complex's lower half was a flat (56,107,184) because
     * the card under everything was reading the top of the gradient. One row in
     * from each end clears the seam and every bilinear tap that reaches past it, and
     * one row of a 256-row ramp is a colour no eye can tell apart from its neighbour.</p>
     */
    private static final float SEAM_INSET = 1f / HEIGHT;

    /**
     * The sky image, or {@code null} if an in-memory PNG write somehow failed --
     * in which case the world keeps its flat single-colour sky rather than losing
     * the sky altogether, the way any other missing art is handled.
     */
    public static byte[] ramp() {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D d = img.createGraphics();
        try {
            d.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            // The upper three quarters is the sky, and the lower quarter already
            // belongs to whatever ground the world ships: a gradient that reaches
            // its pale end at the very bottom of the box puts a bright band under
            // the floor, which is the one thing that makes an outdoor world read
            // as being inside a lit box.
            d.setPaint(new GradientPaint(0f, 0f, ZENITH,
                    0f, HEIGHT * 0.75f, HORIZON));
            d.fillRect(0, 0, WIDTH, HEIGHT);
            d.setPaint(new GradientPaint(0f, HEIGHT * 0.75f, HORIZON,
                    0f, HEIGHT, GROUND_HAZE));
            d.fillRect(0, (int) (HEIGHT * 0.75f), WIDTH,
                    HEIGHT - (int) (HEIGHT * 0.75f));
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

    /**
     * The texture row one sky-card corner sits on: the ramp's top end (1) at the
     * box's ceiling, where the zenith is, and its bottom end (0) at the floor.
     * Public and pure so the mapping is asserted in CI -- a corner's height, not
     * its place on the face, is the whole trick, and a face whose corners are all
     * at the same height is a face of one colour.
     *
     * <p>The direction is the renderer's, not the image's. The upload in
     * {@code J3dMeshFactory.toComponent} hands every texture to Java 3D with its
     * origin at the upper left ({@code yUp = false}), so a picture's first row is
     * sampled at {@code v = 1} -- the same fact, and the same one seam, that decides
     * which way up a name tag reads, see {@code WorldScene.tagQuad}. The ramp's
     * zenith, written at the top of the image, is therefore sampled at
     * {@code v = 1}, which is the top of the box. Mapping it the other way --
     * putting the ceiling on {@code v = 0}, where that upload samples the ramp's
     * LAST row -- is a sky with its ground haze overhead and its zenith pooling at
     * its feet: measured off a rendered clinic, the band above the lobby wall came
     * back paler the higher it rose (177,202,229 at the top of the frame against
     * 168,194,225 lower down), which is the gradient running downhill. Uploading
     * {@code set(img)} on a default-constructed component inverts it the other way
     * (the first row at {@code v = 0}) and is the "ciel a l'envers" a person sees
     * the moment a world has no ground of its own to look at.</p>
     *
     * <p>The two ends are pulled in by {@link #SEAM_INSET} rather than landing on
     * 0 and 1, because a whole face pinned to {@code v = 1} is a whole face that
     * REPEAT hands back to {@code v = 0} -- one ramp end painted on the other.</p>
     *
     * @param y the corner's height relative to the box centre
     * @param half the box's half-height, so the answer is in {@code [-1, 1]}
     * @return the texture row, held inside the ramp's own texels
     */
    public static float rowOf(float y, float half) {
        if (!(half > 0f) || !Float.isFinite(half)) {
            return 0.5f; // a box with no height is one colour, and that colour is the horizon
        }
        float ratio = (1f + y / half) / 2f;
        if (ratio < SEAM_INSET) {
            return SEAM_INSET;
        }
        return (ratio > 1f - SEAM_INSET) ? 1f - SEAM_INSET : ratio;
    }

    private SkyArt() {
    }
}
