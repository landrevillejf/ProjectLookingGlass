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

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JDesktopPane;
import javax.swing.JInternalFrame;

/**
 * The 2D/Swing desktop's capture engine. There is no scene graph to read a
 * raster from and {@code java.awt.Robot} cannot grab a rootless/Wayland
 * display, so the desktop is rendered straight into images by painting its own
 * Swing windows offscreen. Three areas are supported, matching
 * {@link CaptureArea}:
 *
 * <ul>
 *   <li>{@link #captureFullScreen(File)} composites every visible top-level
 *       window into one screen-sized image.</li>
 *   <li>{@link #captureAllWindows(File)} writes each application window
 *       (every {@link JInternalFrame} of the desktop's {@link JDesktopPane},
 *       plus any conventional top-level app window) to its own image.</li>
 *   <li>{@link #captureRegion(File, Rectangle)} composites the screen and
 *       crops it to a region the user selected.</li>
 * </ul>
 *
 * <p>The window enumeration ({@link #findDesktopPane(Container)},
 * {@link #visibleInternalFrames(JDesktopPane)}) and all the geometry run
 * without a display and are unit-tested; only the calls that paint a live
 * window need the desktop to be up.</p>
 */
final class DesktopScreenshot {

    private DesktopScreenshot() {
    }

    // ------------------------------------------------------------------
    // Whole screen
    // ------------------------------------------------------------------

    /**
     * Composites every visible top-level window into one screen-sized PNG in
     * {@code dir}. Returns the number of files written (0 or 1).
     */
    static int captureFullScreen(File dir) {
        ScreenshotFiles.ensureDirectory(dir);
        List<Frame> frames = visibleFrames();
        Rectangle bounds = screenBounds(frames);
        if (bounds.width <= 0 || bounds.height <= 0) {
            return 0;
        }
        BufferedImage shot = composite(frames, bounds);
        return writeOne(shot, dir);
    }

    /**
     * Composites the screen and crops it to {@code region} (in screen
     * coordinates), writing a single PNG in {@code dir}. Returns the number of
     * files written (0 or 1).
     */
    static int captureRegion(File dir, Rectangle region) {
        ScreenshotFiles.ensureDirectory(dir);
        List<Frame> frames = visibleFrames();
        Rectangle bounds = screenBounds(frames);
        if (bounds.width <= 0 || bounds.height <= 0 || region == null) {
            return 0;
        }
        BufferedImage shot = composite(frames, bounds);
        Rectangle local = new Rectangle(region.x - bounds.x,
                region.y - bounds.y, region.width, region.height);
        BufferedImage cropped = CaptureRegions.crop(shot, local);
        return (cropped == null) ? 0 : writeOne(cropped, dir);
    }

    // ------------------------------------------------------------------
    // Every window
    // ------------------------------------------------------------------

    /**
     * Writes each open application window to its own PNG in {@code dir}. In
     * the MDI desktop the windows are the {@link JDesktopPane}'s internal
     * frames; a conventional top-level app window counts as one window too.
     * Returns the number of files written.
     */
    static int captureAllWindows(File dir) {
        ScreenshotFiles.ensureDirectory(dir);
        int index = 0;
        for (Component window : captureWindows()) {
            if (window.getWidth() <= 0 || window.getHeight() <= 0) {
                continue;
            }
            BufferedImage image = new BufferedImage(window.getWidth(),
                    window.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                window.paint(g);
            } catch (RuntimeException ex) {
                g.dispose();
                continue;
            }
            g.dispose();
            File out = new File(dir, ScreenshotFiles.nameFor(index,
                    ScreenshotFiles.nextNumber(), ScreenshotFiles.FORMAT));
            if (ScreenshotFiles.write(image, out, ScreenshotFiles.FORMAT)) {
                index++;
            }
        }
        return index;
    }

    /**
     * The windows to capture individually: for each visible top-level frame,
     * the internal frames of its desktop pane when it hosts one (the MDI
     * shell), otherwise the frame itself (a conventional app window).
     */
    static List<Component> captureWindows() {
        List<Component> windows = new ArrayList<>();
        for (Frame frame : visibleFrames()) {
            JDesktopPane pane = findDesktopPane(frame);
            if (pane != null) {
                windows.addAll(visibleInternalFrames(pane));
            } else {
                windows.add(frame);
            }
        }
        return windows;
    }

    // ------------------------------------------------------------------
    // Enumeration + geometry (pure / headless-testable)
    // ------------------------------------------------------------------

    /**
     * The first {@link JDesktopPane} nested anywhere inside {@code root}, or
     * {@code null} when it hosts none. The MDI desktop shell has exactly one;
     * a conventional app window has none.
     */
    static JDesktopPane findDesktopPane(Container root) {
        if (root == null) {
            return null;
        }
        if (root instanceof JDesktopPane) {
            return (JDesktopPane) root;
        }
        for (Component child : root.getComponents()) {
            if (child instanceof Container) {
                JDesktopPane found = findDesktopPane((Container) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * The visible internal frames of {@code pane}, in the pane's own z-order.
     * Iconified and hidden frames are skipped.
     */
    static List<JInternalFrame> visibleInternalFrames(JDesktopPane pane) {
        List<JInternalFrame> frames = new ArrayList<>();
        if (pane == null) {
            return frames;
        }
        for (JInternalFrame frame : pane.getAllFrames()) {
            if (frame.isVisible()) {
                frames.add(frame);
            }
        }
        return frames;
    }

    /** The visible top-level frames with a non-empty size. */
    static List<Frame> visibleFrames() {
        List<Frame> frames = new ArrayList<>();
        for (Frame frame : Frame.getFrames()) {
            if (frame.isShowing() && frame.getWidth() > 0
                    && frame.getHeight() > 0) {
                frames.add(frame);
            }
        }
        return frames;
    }

    /**
     * The region to composite: the union of the frame bounds, falling back to
     * the default screen's maximum window bounds when no frame is showing.
     */
    static Rectangle screenBounds(List<Frame> frames) {
        List<Rectangle> rects = new ArrayList<>();
        for (Frame frame : frames) {
            rects.add(frame.getBounds());
        }
        Rectangle bounds = CaptureRegions.union(rects);
        if (bounds.width <= 0 || bounds.height <= 0) {
            bounds = maximumWindowBounds();
        }
        return bounds;
    }

    private static Rectangle maximumWindowBounds() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
        } catch (Throwable t) {
            return new Rectangle();
        }
    }

    // ------------------------------------------------------------------
    // Painting (needs a live desktop)
    // ------------------------------------------------------------------

    /**
     * Paints every frame into one image spanning {@code bounds}, background
     * (MDI desktop) frames first so conventional app windows land on top.
     */
    static BufferedImage composite(List<Frame> frames, Rectangle bounds) {
        int width = Math.max(1, bounds.width);
        int height = Math.max(1, bounds.height);
        BufferedImage image = new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, width, height);
            for (Frame frame : orderedForComposite(frames)) {
                paintAt(frame, g, bounds);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Desktop-root frames first (the background), then the rest on top. */
    private static List<Frame> orderedForComposite(List<Frame> frames) {
        List<Frame> background = new ArrayList<>();
        List<Frame> floating = new ArrayList<>();
        for (Frame frame : frames) {
            if (findDesktopPane(frame) != null) {
                background.add(frame);
            } else {
                floating.add(frame);
            }
        }
        background.addAll(floating);
        return background;
    }

    private static void paintAt(Frame frame, Graphics2D g, Rectangle bounds) {
        int dx = frame.getX() - bounds.x;
        int dy = frame.getY() - bounds.y;
        g.translate(dx, dy);
        try {
            frame.paint(g);
        } catch (RuntimeException ex) {
            // A window that refuses to paint offscreen must not abort the rest.
        } finally {
            g.translate(-dx, -dy);
        }
    }

    private static int writeOne(BufferedImage image, File dir) {
        File out = new File(dir, ScreenshotFiles.nameFor(0,
                ScreenshotFiles.nextNumber(), ScreenshotFiles.FORMAT));
        boolean written = ScreenshotFiles.write(image, out,
                ScreenshotFiles.FORMAT);
        if (written) {
            System.out.println("SNAPSHOT (2D) written to "
                    + out.getAbsolutePath());
        }
        return written ? 1 : 0;
    }
}
