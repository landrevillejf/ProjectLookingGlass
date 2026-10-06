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

/**
 * Chooses the pixel readback transport for a composited region and sizes the
 * buffer it needs (Phase E: MIT-SHM fast path).
 *
 * <p>Reading through an attached MIT-SHM segment ({@link X11ShmExt#getImage})
 * lets the server write the pixels straight into shared memory, avoiding a copy
 * of the whole region through the X socket — the dominant cost for a
 * video-playing or rapidly-repainting client. It is only usable when the
 * extension is present, the server supports SHM pixmaps, and the region fits the
 * attached segment; otherwise the planner falls back to a core {@code XGetImage}
 * round-trip ({@link gnu.x11.Drawable#image}).</p>
 *
 * <p>This class is a pure decision plus sizing arithmetic — no
 * {@code gnu.x11.Display}, no segment lifecycle — so the path selection and the
 * byte-size math are unit-testable headlessly. The live SHM attach/detach stays
 * in the compositor.</p>
 *
 * @see X11ShmExt
 * @see CompositeWindowImageLoader
 */
public final class ReadbackPlanner {

    private ReadbackPlanner() {
        // pure static utility
    }

    /** The readback transport for one region. */
    public enum Path {
        /** Read through an attached MIT-SHM segment (no socket copy). */
        SHM,
        /** Read through a core {@code XGetImage} round-trip. */
        XGETIMAGE
    }

    /**
     * The whole bytes per pixel for a bits-per-pixel value, rounding up. A
     * non-positive depth is treated as the 32-bit default.
     *
     * @param bitsPerPixel the server's bits-per-pixel for the pixmap depth
     * @return bytes per pixel, at least 1
     */
    public static int bytesPerPixel(int bitsPerPixel) {
        int bpp = (bitsPerPixel <= 0) ? 32 : bitsPerPixel;
        return Math.max(1, (bpp + 7) >>> 3);
    }

    /**
     * The ZPixmap byte size of a {@code width x height} region at
     * {@code bitsPerPixel}, ignoring scanline padding (a lower bound used to
     * size/validate an SHM segment). Zero for a non-positive region.
     *
     * @param width        region width in pixels
     * @param height       region height in pixels
     * @param bitsPerPixel the server's bits-per-pixel for the pixmap depth
     * @return the region size in bytes, or 0 if the region is empty
     */
    public static long regionBytes(int width, int height, int bitsPerPixel) {
        if (width <= 0 || height <= 0) {
            return 0L;
        }
        return (long) width * height * bytesPerPixel(bitsPerPixel);
    }

    /**
     * Chooses the readback path for one region.
     *
     * @param shmAvailable       the MIT-SHM extension is present and a segment
     *                           is attached for this window
     * @param sharedPixmaps      the server supports SHM pixmaps
     *                           ({@link X11ShmExt#shared_pixmaps_supported})
     * @param regionBytes        the region size in bytes (see {@link #regionBytes})
     * @param shmSegmentCapacity the attached segment's capacity in bytes; a value
     *                           &lt;= 0 means "unknown/unbounded"
     * @return {@link Path#SHM} when the fast path is usable, else
     *         {@link Path#XGETIMAGE}
     */
    public static Path choose(boolean shmAvailable, boolean sharedPixmaps,
            long regionBytes, long shmSegmentCapacity) {
        if (!shmAvailable || !sharedPixmaps || regionBytes <= 0L) {
            return Path.XGETIMAGE;
        }
        if (shmSegmentCapacity > 0L && regionBytes > shmSegmentCapacity) {
            return Path.XGETIMAGE;
        }
        return Path.SHM;
    }
}
