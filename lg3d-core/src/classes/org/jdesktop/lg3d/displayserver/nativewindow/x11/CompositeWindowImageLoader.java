/**
 * Project Looking Glass
 *
 * Copyright (c) 2026 Project Looking Glass contributors.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

import gnu.x11.Data;
import gnu.x11.Display;
import gnu.x11.Pixmap;

import org.jdesktop.lg3d.displayserver.fws.WindowContents;
import org.jdesktop.lg3d.displayserver.nativewindow.TiledNativeWindowImage;
import org.jdesktop.lg3d.displayserver.nativewindow.TiledNativeWindowImageLoader;
import org.jdesktop.lg3d.sg.ImageComponent2D;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pure-Java pixel pipeline from a Composite offscreen pixmap into the
 * {@link TiledNativeWindowImage} tiles that back a {@code NativeWindow3D}.
 *
 * <p>This is the compositing replacement for the legacy
 * {@code fws/x11/ImageLoader} + {@code ImageUpdater} (LightPipe / native)
 * path. It implements the same {@link TiledNativeWindowImageLoader} contract
 * so it plugs into {@link TiledNativeWindowImage#fillTiles} unchanged, but
 * instead of a native updater it reads pixels with core {@code XGetImage}
 * ({@link gnu.x11.Drawable#image}) from the window's
 * {@code CompositeNameWindowPixmap} and blits them into each tile's
 * {@link BufferedImage}.
 *
 * <h3>Data flow</h3>
 * <ol>
 *   <li>{@link X11Compositor} receives a {@code DamageNotify} for a window
 *       and calls the registered {@link X11Compositor.DamageListener}.</li>
 *   <li>The listener (Stage 4 wiring) calls {@link #updateRegion} with the
 *       damaged rectangle.</li>
 *   <li>{@code updateRegion} delegates to
 *       {@link TiledNativeWindowImage#fillTiles(TiledNativeWindowImageLoader,
 *       int, int, int, int, WindowContents)}, which calls {@link #loadTile}
 *       for each affected tile.</li>
 *   <li>{@code loadTile} reads the region from the Composite pixmap and
 *       updates the tile's image, triggering a texture re-upload on the next
 *       rendered frame.</li>
 * </ol>
 *
 * <h3>Pixel format</h3>
 * <p>Pixels are read as a {@code ZPixmap} and decoded using the window's
 * TrueColor red/green/blue masks (defaulting to the standard 24-bit layout).
 * The per-scanline stride honours the server's bits-per-pixel and
 * scanline-pad for the pixmap's depth, and the byte assembly honours
 * {@link Display#image_byte_order}.
 *
 * <h3>Threading</h3>
 * <p>{@link #loadTile} performs a synchronous X round-trip and must therefore
 * run on the X11WindowManager event thread (or another thread that safely
 * serializes access to the shared {@link Display}), not on the Java 3D
 * rendering thread.
 *
 * @see X11Compositor
 * @see TiledNativeWindowImage
 */
public class CompositeWindowImageLoader implements TiledNativeWindowImageLoader {

    private static final Logger logger =
        Logger.getLogger("lg.x11.compositor.imageloader");

    /** X image format: ZPixmap. */
    private static final int ZPIXMAP = 2;
    /** AllPlanes plane mask for GetImage. */
    private static final int ALL_PLANES = 0xFFFFFFFF;
    /** Byte offset of the pixel data within a GetImage reply. */
    private static final int IMAGE_DATA_OFFSET = 32;
    /** X image byte order value meaning LSBFirst. */
    private static final int LSB_FIRST = 0;

    private final X11Compositor compositor;
    private final Display display;
    private final int windowId;

    // TrueColor channel masks. Default to the standard 24-bit layout; the
    // Stage 4 wiring may override these with the window's actual visual masks.
    private int redMask = 0xff0000;
    private int greenMask = 0x00ff00;
    private int blueMask = 0x0000ff;

    /**
     * Creates a loader bound to one managed X window.
     *
     * @param compositor the compositor that tracks the window's pixmap
     * @param windowId   the X window id whose contents are read
     */
    public CompositeWindowImageLoader(X11Compositor compositor, int windowId) {
        this.compositor = compositor;
        this.display = compositor.getDisplay();
        this.windowId = windowId;
    }

    /**
     * Overrides the default TrueColor masks with the window's visual masks.
     * Needed for 15/16-bit visuals whose channel layout differs from 24-bit.
     */
    public void setVisualMasks(int redMask, int greenMask, int blueMask) {
        this.redMask = redMask;
        this.greenMask = greenMask;
        this.blueMask = blueMask;
    }

    /** Returns the X window id this loader reads from. */
    public int getWindowId() {
        return windowId;
    }

    /**
     * Reads the damaged region of the window's Composite pixmap and updates
     * the affected tiles of {@code image}.
     *
     * @param image   the tiled image backing the window's 3D quad
     * @param x       damaged region left, in window pixels
     * @param y       damaged region top, in window pixels
     * @param width   damaged region width
     * @param height  damaged region height
     * @param winContents per-tile metadata passed through to
     *        {@link TiledNativeWindowImage#fillTiles}; may describe the full
     *        window size
     */
    public void updateRegion(TiledNativeWindowImage image,
                             int x, int y, int width, int height,
                             WindowContents winContents) {
        if (image == null || width <= 0 || height <= 0) {
            return;
        }
        image.fillTiles(this, x, y, width, height, winContents);
    }

    // ------------------------------------------------------------------
    // TiledNativeWindowImageLoader
    // ------------------------------------------------------------------

    /**
     * Fills {@code tile}'s [0,0,width,height] region with the window pixels
     * at [srcX,srcY,width,height] read from the Composite pixmap.
     */
    public void loadTile(ImageComponent2D tile, int srcX, int srcY,
                         int width, int height, WindowContents winContents) {
        int pixmapId = compositor.getPixmapId(windowId);
        if (pixmapId < 0) {
            // Window not (yet) redirected, or pixmap invalidated by a resize.
            return;
        }
        if (srcX < 0) srcX = 0;
        if (srcY < 0) srcY = 0;
        if (width <= 0 || height <= 0) return;

        BufferedImage src = readPixmapRegion(pixmapId, srcX, srcY, width, height);
        if (src == null) {
            return;
        }

        // Blit into the tile's existing (by-reference) image so the tile keeps
        // its power-of-two dimensions, then re-set to trigger a texture upload.
        // The tile covers the whole window, so a damaged sub-region read from
        // [srcX,srcY] must be drawn back at [srcX,srcY] (not the tile origin).
        RenderedImage ri = tile.getRenderedImage();
        if (ri instanceof BufferedImage) {
            BufferedImage dst = (BufferedImage) ri;
            Graphics2D g = dst.createGraphics();
            try {
                g.drawImage(src, srcX, srcY, null);
            } finally {
                g.dispose();
            }
            tile.set(dst);
        } else {
            tile.set(src);
        }
    }

    // ------------------------------------------------------------------
    // Pixel readback
    // ------------------------------------------------------------------

    /**
     * Reads a rectangular region of a Composite pixmap via {@code XGetImage}
     * and decodes it into a {@code TYPE_INT_RGB} {@link BufferedImage}.
     *
     * @return the decoded image, or null if the read failed or the reply was
     *         truncated (e.g. the pixmap was invalidated by a concurrent
     *         resize)
     */
    BufferedImage readPixmapRegion(int pixmapId, int x, int y, int w, int h) {
        try {
            Pixmap pixmap = new Pixmap(pixmapId);
            pixmap.display = display;

            Data reply = pixmap.image(x, y, w, h, ALL_PLANES, ZPIXMAP);
            if (reply == null || reply.data == null) {
                return null;
            }

            int depth = reply.read1(1);
            int bitsPerPixel = 32;
            int scanlinePad = 32;
            Pixmap.Format[] formats = display.pixmap_formats;
            if (formats != null) {
                for (Pixmap.Format f : formats) {
                    if (f.depth() == depth) {
                        bitsPerPixel = f.bits_per_pixel();
                        scanlinePad = f.scanline_pad();
                        break;
                    }
                }
            }

            int bytesPerPixel = bitsPerPixel >>> 3;
            if (bytesPerPixel < 1 || bytesPerPixel > 4) {
                logger.warning("Unsupported bits-per-pixel " + bitsPerPixel
                    + " for depth " + depth);
                return null;
            }

            // Scanline stride, padded up to the server's scanline-pad.
            int padBytes = scanlinePad >>> 3;
            int stride = ((w * bitsPerPixel + scanlinePad - 1) / scanlinePad)
                * padBytes;

            byte[] data = reply.data;
            int needed = IMAGE_DATA_OFFSET + stride * h;
            if (data.length < needed) {
                logger.fine("Truncated GetImage reply: have " + data.length
                    + " need " + needed + " (window resized?)");
                return null;
            }

            boolean lsb = display.image_byte_order == LSB_FIRST;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            int[] row = new int[w];
            for (int yy = 0; yy < h; yy++) {
                int base = IMAGE_DATA_OFFSET + yy * stride;
                for (int xx = 0; xx < w; xx++) {
                    int pv = readPixel(data, base + xx * bytesPerPixel,
                        bytesPerPixel, lsb);
                    row[xx] = 0xff000000
                        | (channel(pv, redMask) << 16)
                        | (channel(pv, greenMask) << 8)
                        | channel(pv, blueMask);
                }
                img.setRGB(0, yy, w, 1, row, 0, w);
            }
            return img;
        } catch (gnu.x11.Error e) {
            // Most commonly BadMatch/BadDrawable when the pixmap was freed or
            // the window resized mid-read; the next DamageNotify retries.
            logger.log(Level.FINE, "readPixmapRegion failed for pixmap 0x"
                + Integer.toHexString(pixmapId), e);
            return null;
        }
    }

    /** Assembles one pixel from {@code bytesPerPixel} bytes at {@code off}. */
    private static int readPixel(byte[] d, int off, int bytesPerPixel, boolean lsb) {
        int pv = 0;
        if (lsb) {
            for (int i = bytesPerPixel - 1; i >= 0; i--) {
                pv = (pv << 8) | (d[off + i] & 0xff);
            }
        } else {
            for (int i = 0; i < bytesPerPixel; i++) {
                pv = (pv << 8) | (d[off + i] & 0xff);
            }
        }
        return pv;
    }

    /**
     * Extracts one colour channel from a pixel value using its mask and
     * scales it up to 8 bits.
     */
    private static int channel(int pixel, int mask) {
        if (mask == 0) {
            return 0;
        }
        int shift = Integer.numberOfTrailingZeros(mask);
        int width = Integer.bitCount(mask);
        int lowBits = (width >= 32) ? -1 : ((1 << width) - 1);
        int v = (pixel >>> shift) & lowBits;
        return (width >= 8) ? (v >>> (width - 8)) : (v << (8 - width));
    }
}
