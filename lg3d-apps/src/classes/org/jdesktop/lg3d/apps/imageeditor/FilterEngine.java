/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.imageeditor;

import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.awt.image.RescaleOp;

/**
 * The image editor's pixel filters, implemented with plain Java 2D
 * ({@link RescaleOp}, {@link ConvolveOp} and per-pixel loops) - no third-party
 * imaging library, no Java 3D. Every method is pure: it takes a source image and
 * returns a <em>new</em> {@link BufferedImage}, never mutating the input, so a
 * filter can be previewed and the original restored on cancel.
 *
 * <p>{@link BufferedImage} and its raster ops work headless, so the whole filter
 * table is unit-testable in CI without a display.</p>
 */
public final class FilterEngine {

    private FilterEngine() {
        // no instances
    }

    /**
     * Scales brightness: each RGB channel is multiplied by {@code factor}
     * (1.0 is unchanged), the alpha channel is preserved.
     *
     * @param factor a multiplier, clamped to 0..4
     */
    public static BufferedImage brightness(BufferedImage src, float factor) {
        BufferedImage img = argb(src);
        float f = Math.max(0f, Math.min(4f, factor));
        new RescaleOp(new float[] { f, f, f, 1f }, new float[4], null)
                .filter(img, img);
        return img;
    }

    /**
     * Adjusts contrast around the mid-point: {@code (c - 128) * factor + 128}.
     *
     * @param factor a multiplier, clamped to 0..4
     */
    public static BufferedImage contrast(BufferedImage src, float factor) {
        BufferedImage img = argb(src);
        float f = Math.max(0f, Math.min(4f, factor));
        float offset = 128f * (1f - f);
        new RescaleOp(new float[] { f, f, f, 1f },
                new float[] { offset, offset, offset, 0f }, null).filter(img, img);
        return img;
    }

    /** Converts to greyscale using the luminance weights, preserving alpha. */
    public static BufferedImage grayscale(BufferedImage src) {
        BufferedImage img = argb(src);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xff;
                int r = (argb >> 16) & 0xff;
                int g = (argb >> 8) & 0xff;
                int b = argb & 0xff;
                int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                img.setRGB(x, y, (a << 24) | (lum << 16) | (lum << 8) | lum);
            }
        }
        return img;
    }

    /** Inverts each RGB channel, preserving alpha. */
    public static BufferedImage invert(BufferedImage src) {
        BufferedImage img = argb(src);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xff;
                int r = 255 - ((argb >> 16) & 0xff);
                int g = 255 - ((argb >> 8) & 0xff);
                int b = 255 - (argb & 0xff);
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    /** Applies a warm sepia tone, preserving alpha. */
    public static BufferedImage sepia(BufferedImage src) {
        BufferedImage img = grayscale(src);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xff;
                int v = argb & 0xff;
                int r = Math.min(255, v + 60);
                int g = Math.min(255, v + 30);
                int b = Math.max(0, v - 20);
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }

    /**
     * Binarises to black / white at {@code threshold} (0..255), preserving
     * alpha.
     */
    public static BufferedImage threshold(BufferedImage src, int threshold) {
        BufferedImage img = grayscale(src);
        int t = Math.max(0, Math.min(255, threshold));
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xff;
                int v = (argb & 0xff) >= t ? 255 : 0;
                img.setRGB(x, y, (a << 24) | (v << 16) | (v << 8) | v);
            }
        }
        return img;
    }

    /** A 3x3 box blur; {@code passes} repeats the convolution. */
    public static BufferedImage blur(BufferedImage src, int passes) {
        BufferedImage img = argb(src);
        float w = 1f / 9f;
        float[] kernel = new float[9];
        for (int i = 0; i < 9; i++) {
            kernel[i] = w;
        }
        ConvolveOp op = new ConvolveOp(new Kernel(3, 3, kernel),
                ConvolveOp.EDGE_NO_OP, null);
        int n = Math.max(1, passes);
        for (int i = 0; i < n; i++) {
            BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            op.filter(img, out);
            img = out;
        }
        return img;
    }

    /** A 3x3 unsharp / sharpen convolution. */
    public static BufferedImage sharpen(BufferedImage src) {
        BufferedImage img = argb(src);
        float[] kernel = {
            0f, -1f, 0f,
            -1f, 5f, -1f,
            0f, -1f, 0f
        };
        ConvolveOp op = new ConvolveOp(new Kernel(3, 3, kernel),
                ConvolveOp.EDGE_NO_OP, null);
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        op.filter(img, out);
        return out;
    }

    /** Detects edges with a 3x3 Laplacian, preserving alpha. */
    public static BufferedImage edges(BufferedImage src) {
        BufferedImage img = grayscale(src);
        float[] kernel = {
            -1f, -1f, -1f,
            -1f, 8f, -1f,
            -1f, -1f, -1f
        };
        ConvolveOp op = new ConvolveOp(new Kernel(3, 3, kernel),
                ConvolveOp.EDGE_NO_OP, null);
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        op.filter(img, out);
        return out;
    }

    /**
     * Returns a mutable {@code TYPE_INT_ARGB} copy of {@code src}, so a filter
     * can operate in place without ever mutating the caller's original image.
     */
    private static BufferedImage argb(BufferedImage src) {
        if (src == null) {
            throw new IllegalArgumentException("src must not be null");
        }
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }
}
