/*
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved.
 */
/*
 * wonderland-renderer P2.4: image bytes -> one GL 2D texture.
 *
 * The pure half already decided WHICH image a material binds (TextureBindingPlan
 * -> MaterialAppearance's diffuse path) and the asset store already fetched its
 * bytes; this is the JOGL half that turns those bytes into a sampled texture. It
 * decodes with ImageIO (the same reader the GUI-cell pictures use), repacks to
 * tightly-packed RGBA and uploads with mipmaps, so a world's poster, carpet and
 * wall art all sample the way the java3d Texture2D path did.
 *
 * Probe-only by nature: a GLContext cannot be constructed in a headless test JVM,
 * so upload() is verified by the P2.5 probe, not asserted. The one headless-
 * reachable branch is the null/empty guard, which degrades to "no texture" (0)
 * rather than throwing -- the same degrade-never-throw rule every GL seam here
 * follows, because a texture that will not decode is an untextured shape, never a
 * second failure on the render thread.
 */
package org.jdesktop.wonderland.renderer.gl;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2ES3;

/** Decodes encoded image bytes into a mipmapped GL texture id. */
public final class GlTexture {

    private GlTexture() {
        // holder: no instances
    }

    /**
     * Upload {@code encoded} (PNG/JPEG/... bytes ImageIO can read) as a new
     * texture with mipmaps and clamp-to-edge wrapping.
     *
     * @param gl      the current context's GL surface, or {@code null} for none
     * @param encoded the image bytes, or {@code null}/empty for none
     * @return the texture name, or {@code 0} when there is nothing to upload or
     *         the decode/GL failed (the caller then renders the shape untextured)
     */
    public static int upload(GL2ES3 gl, byte[] encoded) {
        if (gl == null || encoded == null || encoded.length == 0) {
            return 0;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(encoded));
            if (image == null) {
                return 0;
            }
            int w = image.getWidth();
            int h = image.getHeight();
            if (w <= 0 || h <= 0) {
                return 0;
            }
            int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer pixels = ByteBuffer.allocateDirect(w * h * 4);
            for (int p : argb) {
                pixels.put((byte) ((p >> 16) & 0xFF)); // R
                pixels.put((byte) ((p >> 8) & 0xFF));  // G
                pixels.put((byte) (p & 0xFF));         // B
                pixels.put((byte) ((p >> 24) & 0xFF)); // A
            }
            pixels.rewind();
            int[] ids = new int[1];
            gl.glGenTextures(1, ids, 0);
            gl.glBindTexture(GL.GL_TEXTURE_2D, ids[0]);
            gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, w, h, 0,
                    GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels);
            gl.glGenerateMipmap(GL.GL_TEXTURE_2D);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER,
                    GL.GL_LINEAR_MIPMAP_LINEAR);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
            gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
            gl.glBindTexture(GL.GL_TEXTURE_2D, 0);
            return ids[0];
        } catch (Throwable decodeOrGlFault) {
            // An undecodable picture or a driver that refuses the texture leaves the
            // shape untextured; it must never become a second failure per frame.
            return 0;
        }
    }

    /**
     * Delete a texture name. Best-effort and never throws; {@code 0} is a no-op so
     * a caller can hand back whatever {@link #upload} returned without a guard.
     *
     * @param gl the current context's GL surface, or {@code null} to skip
     * @param id the texture name, or {@code 0} for none
     */
    public static void dispose(GL2ES3 gl, int id) {
        if (gl == null || id == 0) {
            return;
        }
        try {
            gl.glDeleteTextures(1, new int[] {id}, 0);
        } catch (Throwable ignored) {
            // A teardown fault is not worth a second failure; drop the handle.
        }
    }
}
