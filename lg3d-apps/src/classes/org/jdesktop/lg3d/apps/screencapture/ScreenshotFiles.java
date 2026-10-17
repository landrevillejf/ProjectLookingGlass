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
package org.jdesktop.lg3d.apps.screencapture;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Where snapshots land and what they are called. The naming matches the 3D
 * {@code ScreenCaptureBehavior} ({@code lgscreen-<index>-<n>.<format>}) so a
 * snapshot taken in the 2D/Swing desktop and one taken in the 3D desktop are
 * indistinguishable in the destination folder.
 *
 * <p>The default directory, the naming and the on-demand {@code mkdirs} are
 * pure and headless-testable; only {@link #write} touches the disk, which is
 * still safe to exercise against a temporary folder without a display.</p>
 */
final class ScreenshotFiles {

    /** The image format every capture path writes. */
    static final String FORMAT = "png";

    /** Per-process counter, mirroring the 3D behavior's own {@code imageNo}. */
    private static int imageNo;

    private ScreenshotFiles() {
    }

    /**
     * The default directory screenshots are saved to: {@code Screenshots}
     * inside the user's {@code Documents} folder ({@code ~/Documents/Screenshots}),
     * rather than the process working directory.
     *
     * @return the {@code ~/Documents/Screenshots} directory (not yet created)
     */
    static File defaultDirectory() {
        File home = new File(System.getProperty("user.home"));
        return new File(new File(home, "Documents"), "Screenshots");
    }

    /**
     * Creates {@code dir} (and any missing parents) if it does not already
     * exist, so every capture path has a writable destination. A directory
     * that cannot be created is returned as-is; the writer reports the failure.
     *
     * @param dir the directory to ensure
     * @return {@code dir}, for chaining
     */
    static File ensureDirectory(File dir) {
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * The file name for one captured image, matching the 3D naming:
     * {@code lgscreen-<index>-<number>.<format>}.
     *
     * @param index  the screen/window index within this capture
     * @param number the per-process image counter value
     * @param format the file extension (e.g. {@code png})
     * @return the file name (not a path)
     */
    static String nameFor(int index, int number, String format) {
        return "lgscreen-" + index + "-" + number + "." + format;
    }

    /**
     * Reserves and returns the next per-process image number. Synchronised so
     * concurrent captures cannot collide on a file name.
     *
     * @return a monotonically increasing image number
     */
    static synchronized int nextNumber() {
        return imageNo++;
    }

    /**
     * Writes {@code image} to {@code out} in {@code format}, swallowing any
     * {@link IOException} so a failed snapshot degrades to "not written"
     * rather than an exception on the EDT.
     *
     * @return {@code true} when an encoder wrote the file
     */
    static boolean write(BufferedImage image, File out, String format) {
        if (image == null || out == null) {
            return false;
        }
        try {
            return ImageIO.write(image, format == null ? FORMAT : format, out);
        } catch (IOException ex) {
            return false;
        }
    }
}
