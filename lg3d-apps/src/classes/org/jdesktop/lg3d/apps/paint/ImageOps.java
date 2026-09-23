/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.paint;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;

/**
 * Pure-{@code java.awt.image} pixel transforms and filters for the Paint app -
 * geometry (scale, crop, rotate, flip) and adjustments (brightness/contrast,
 * grayscale, invert, hue/saturation, posterize, threshold, blur, sharpen,
 * emboss). No JAI and no external dependency.
 *
 * <p>Single-pixel adjustments preserve the alpha channel and are applied through
 * {@link #perPixel}; neighbourhood filters use {@link ConvolveOp}. Every method
 * returns a new {@code TYPE_INT_ARGB} image and never mutates its source, so
 * they are safe to use for live filter previews.</p>
 */
public final class ImageOps {

    /** A whole-image adjustment/transform. */
    public interface Filter {
        BufferedImage apply(BufferedImage src);
    }

    /** A per-pixel colour transform that preserves alpha unless it changes it. */
    private interface PixelOp {
        int apply(int argb);
    }

    private ImageOps() {
    }

    // ------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------

    /** Bilinear scale to an exact size. */
    public static BufferedImage scale(BufferedImage src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        BufferedImage out = newImage(w, h);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** Extracts {@code rect} (clamped to the source) as a new image. */
    public static BufferedImage crop(BufferedImage src, Rectangle rect) {
        Rectangle r = rect.intersection(
                new Rectangle(0, 0, src.getWidth(), src.getHeight()));
        if (r.width <= 0 || r.height <= 0) {
            r = new Rectangle(0, 0, Math.max(1, src.getWidth()),
                    Math.max(1, src.getHeight()));
        }
        BufferedImage out = newImage(r.width, r.height);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, r.width, r.height,
                r.x, r.y, r.x + r.width, r.y + r.height, null);
        g.dispose();
        return out;
    }

    /** Mirrors the image horizontally or vertically. */
    public static BufferedImage flip(BufferedImage src, boolean horizontal) {
        BufferedImage out = newImage(src.getWidth(), src.getHeight());
        Graphics2D g = out.createGraphics();
        if (horizontal) {
            g.drawImage(src, 0, 0, src.getWidth(), src.getHeight(),
                    src.getWidth(), 0, 0, src.getHeight(), null);
        } else {
            g.drawImage(src, 0, 0, src.getWidth(), src.getHeight(),
                    0, src.getHeight(), src.getWidth(), 0, null);
        }
        g.dispose();
        return out;
    }

    /**
     * Rotates by an arbitrary angle about the centre, growing the canvas to the
     * rotated bounding box. When {@code outSize} is non-null its first two
     * entries receive the new width and height.
     */
    public static BufferedImage rotate(BufferedImage src, double degrees,
            int[] outSize) {
        double r = Math.toRadians(degrees);
        int w = src.getWidth();
        int h = src.getHeight();
        double sin = Math.abs(Math.sin(r));
        double cos = Math.abs(Math.cos(r));
        int nw = (int) Math.floor(w * cos + h * sin);
        int nh = (int) Math.floor(h * cos + w * sin);
        nw = Math.max(1, nw);
        nh = Math.max(1, nh);
        BufferedImage out = newImage(nw, nh);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.translate((nw - w) / 2.0, (nh - h) / 2.0);
        g.rotate(r, w / 2.0, h / 2.0);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        if (outSize != null && outSize.length >= 2) {
            outSize[0] = nw;
            outSize[1] = nh;
        }
        return out;
    }

    /** Rotates the whole image about an anchor, keeping the canvas size. */
    public static BufferedImage rotateInPlace(BufferedImage src, double degrees,
            double anchorX, double anchorY) {
        BufferedImage out = newImage(src.getWidth(), src.getHeight());
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.rotate(Math.toRadians(degrees), anchorX, anchorY);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    // ------------------------------------------------------------------
    // Adjustments (single pixel)
    // ------------------------------------------------------------------

    /**
     * Brightness/contrast. {@code brightness} is an additive offset in
     * [-255,255]; {@code contrast} is a multiplier around the mid-grey (1.0 is
     * unchanged). Alpha is preserved.
     */
    public static Filter brightnessContrast(final float brightness,
            final float contrast) {
        return new Filter() {
            public BufferedImage apply(BufferedImage src) {
                return perPixel(src, new PixelOp() {
                    public int apply(int argb) {
                        int a = (argb >>> 24) & 0xFF;
                        int r = bc((argb >> 16) & 0xFF, brightness, contrast);
                        int g = bc((argb >> 8) & 0xFF, brightness, contrast);
                        int b = bc(argb & 0xFF, brightness, contrast);
                        return (a << 24) | (r << 16) | (g << 8) | b;
                    }
                });
            }
        };
    }

    private static int bc(int c, float brightness, float contrast) {
        int v = Math.round(contrast * (c - 128) + 128 + brightness);
        return clamp(v);
    }

    /** Luma grayscale, alpha preserved. */
    public static Filter grayscale() {
        return new Filter() {
            public BufferedImage apply(BufferedImage src) {
                return perPixel(src, new PixelOp() {
                    public int apply(int argb) {
                        int a = (argb >>> 24) & 0xFF;
                        int r = (argb >> 16) & 0xFF;
                        int g = (argb >> 8) & 0xFF;
                        int b = argb & 0xFF;
                        int y = clamp(Math.round(
                                0.299f * r + 0.587f * g + 0.114f * b));
                        return (a << 24) | (y << 16) | (y << 8) | y;
                    }
                });
            }
        };
    }

    /** Colour inversion, alpha preserved. */
    public static Filter invert() {
        return new Filter() {
            public BufferedImage apply(BufferedImage src) {
                return perPixel(src, new PixelOp() {
                    public int apply(int argb) {
                        int a = (argb >>> 24) & 0xFF;
                        int r = 255 - ((argb >> 16) & 0xFF);
                        int g = 255 - ((argb >> 8) & 0xFF);
                        int b = 255 - (argb & 0xFF);
                        return (a << 24) | (r << 16) | (g << 8) | b;
                    }
                });
            }
        };
    }

    /**
     * Hue rotation (degrees) and saturation scaling (1.0 unchanged), alpha
     * preserved.
     */
    public static Filter hueSaturation(final float hueDegrees,
            final float saturation) {
        return new Filter() {
            public BufferedImage apply(BufferedImage src) {
                return perPixel(src, new PixelOp() {
                    public int apply(int argb) {
                        int a = (argb >>> 24) & 0xFF;
                        int r = (argb >> 16) & 0xFF;
                        int g = (argb >> 8) & 0xFF;
                        int b = argb & 0xFF;
                        float[] hsb = Color.RGBtoHSB(r, g, b, null);
                        float h = hsb[0] + hueDegrees / 360.0f;
                        h = h - (float) Math.floor(h);
                        float s = clamp01(hsb[1] * saturation);
                        int rgb = Color.HSBtoRGB(h, s, hsb[2]);
                        return (a << 24) | (rgb & 0x00FFFFFF);
                    }
                });
            }
        };
    }

    /** Reduces each channel to {@code levels} evenly spaced values. */
    public static Filter posterize(final int levels) {
        final int n = Math.max(2, levels);
        return new Filter() {
            public BufferedImage apply(BufferedImage src) {
                return perPixel(src, new PixelOp() {
                    public int apply(int argb) {
                        int a = (argb >>> 24) & 0xFF;
                        int r = posterizeChannel((argb >> 16) & 0xFF, n);
                        int g = posterizeChannel((argb >> 8) & 0xFF, n);
                        int b = posterizeChannel(argb & 0xFF, n);
                        return (a << 24) | (r << 16) | (g << 8) | b;
                    }
                });
            }
        };
    }

    private static int posterizeChannel(int c, int levels) {
        float step = 255.0f / (levels - 1);
        int i = Math.round(c / step);
        return clamp(Math.round(i * step));
    }

    /** Black-and-white threshold on luma; alpha preserved. */
    public static Filter threshold(final int t) {
        return new Filter() {
            public BufferedImage apply(BufferedImage src) {
                return perPixel(src, new PixelOp() {
                    public int apply(int argb) {
                        int a = (argb >>> 24) & 0xFF;
                        int r = (argb >> 16) & 0xFF;
                        int g = (argb >> 8) & 0xFF;
                        int b = argb & 0xFF;
                        int y = Math.round(0.299f * r + 0.587f * g + 0.114f * b);
                        int v = (y >= t) ? 255 : 0;
                        return (a << 24) | (v << 16) | (v << 8) | v;
                    }
                });
            }
        };
    }

    // ------------------------------------------------------------------
    // Convolution filters
    // ------------------------------------------------------------------

    /** Normalised box blur of the given radius (kernel 2r+1). */
    public static Filter blur(final int radius) {
        return new Filter() {
            public BufferedImage apply(BufferedImage src) {
                int r = Math.max(1, radius);
                int size = r * 2 + 1;
                float[] data = new float[size * size];
                float v = 1.0f / (size * size);
                for (int i = 0; i < data.length; i++) {
                    data[i] = v;
                }
                return convolve(src, data, size, size);
            }
        };
    }

    /** 3x3 sharpen. */
    public static Filter sharpen() {
        final float[] data = {
            0f, -1f, 0f,
            -1f, 5f, -1f,
            0f, -1f, 0f
        };
        return new Filter() {
            public BufferedImage apply(BufferedImage src) {
                return convolve(src, data, 3, 3);
            }
        };
    }

    /** 3x3 emboss. */
    public static Filter emboss() {
        final float[] data = {
            -2f, -1f, 0f,
            -1f, 1f, 1f,
            0f, 1f, 2f
        };
        return new Filter() {
            public BufferedImage apply(BufferedImage src) {
                return convolve(src, data, 3, 3);
            }
        };
    }

    private static BufferedImage convolve(BufferedImage src, float[] data,
            int w, int h) {
        BufferedImage in = toArgb(src);
        Kernel kernel = new Kernel(w, h, data);
        ConvolveOp op = new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null);
        BufferedImage out = newImage(in.getWidth(), in.getHeight());
        op.filter(in, out);
        return out;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static BufferedImage perPixel(BufferedImage src, PixelOp op) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] px = toArgb(src).getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {
            px[i] = op.apply(px[i]);
        }
        BufferedImage out = newImage(w, h);
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }

    /** A blank ARGB image. */
    public static BufferedImage newImage(int w, int h) {
        return new BufferedImage(Math.max(1, w), Math.max(1, h),
                BufferedImage.TYPE_INT_ARGB);
    }

    /** Ensures an ARGB copy suitable for getRGB/setRGB and ConvolveOp. */
    public static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) {
            return src;
        }
        BufferedImage out = newImage(src.getWidth(), src.getHeight());
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** A convenience identity transform, useful as a preview baseline. */
    public static AffineTransform identity() {
        return new AffineTransform();
    }
}
