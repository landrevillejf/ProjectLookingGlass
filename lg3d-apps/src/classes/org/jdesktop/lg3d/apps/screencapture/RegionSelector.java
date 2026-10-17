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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JWindow;

/**
 * A full-screen, translucent overlay the user drags a rectangle out of to
 * choose the {@link CaptureArea#SELECTION} region. On release it reports the
 * selection in <em>screen</em> coordinates and disposes itself; Escape (or a
 * click without a drag) cancels and reports {@code null}.
 *
 * <p>The overlay is a {@link JWindow}, never a {@link java.awt.Frame}, so it is
 * not one of the windows {@link DesktopScreenshot} composites &mdash; and it is
 * disposed before the capture runs anyway, so it can never appear in its own
 * snapshot. It uses per-pixel translucency when the platform supports it (the
 * selection is punched clear so the real desktop shows through), a uniform
 * window opacity when only that is available, and an opaque overlay with
 * instructions as a last resort. All the rectangle math lives in the
 * headless-tested {@link CaptureRegions}; this class is the live, probe-only
 * view. It must be shown on the event-dispatch thread.</p>
 */
final class RegionSelector {

    /** A drag smaller than this in either axis is treated as a cancel. */
    private static final int MIN_DRAG_PX = 4;

    private RegionSelector() {
    }

    /**
     * Shows the selection overlay over {@code screenBounds} and, when the user
     * finishes, invokes {@code onDone} with the chosen region in screen
     * coordinates (or {@code null} when cancelled). Returns immediately; the
     * callback fires later, on the EDT. Must be called on the EDT.
     */
    static void select(Rectangle screenBounds, Consumer<Rectangle> onDone) {
        Rectangle bounds = (screenBounds == null || screenBounds.isEmpty())
                ? defaultScreenBounds() : new Rectangle(screenBounds);
        JWindow window = new JWindow();
        SelectionPane pane = new SelectionPane(window, bounds, onDone);
        window.setContentPane(pane);
        applyTranslucency(window, pane);
        window.setBounds(bounds);
        window.setAlwaysOnTop(true);
        window.setVisible(true);
        window.toFront();
        pane.requestFocusInWindow();
    }

    private static void applyTranslucency(JWindow window, SelectionPane pane) {
        GraphicsDevice device = defaultDevice();
        if (device != null && device.isWindowTranslucencySupported(
                GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)) {
            pane.setTranslucent(true);
            window.setBackground(new Color(0, 0, 0, 0));
        } else if (device != null && device.isWindowTranslucencySupported(
                GraphicsDevice.WindowTranslucency.TRANSLUCENT)) {
            window.setOpacity(0.45f);
        }
        // else: no translucency - the pane paints an opaque veil with a hint.
    }

    private static GraphicsDevice defaultDevice() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice();
        } catch (Throwable t) {
            return null;
        }
    }

    static Rectangle defaultScreenBounds() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
        } catch (Throwable t) {
            return new Rectangle(0, 0, 1, 1);
        }
    }

    /**
     * The draggable overlay surface. Tracks the press/drag/release gesture,
     * paints the veil and the selection rectangle, and hands the result back
     * through the callback.
     */
    private static final class SelectionPane extends JComponent {

        private final JWindow window;
        private final Rectangle bounds;
        private final Consumer<Rectangle> onDone;

        private Point anchor;
        private Rectangle selection;
        private boolean translucent;
        private boolean finished;

        SelectionPane(JWindow window, Rectangle bounds,
                Consumer<Rectangle> onDone) {
            this.window = window;
            this.bounds = bounds;
            this.onDone = onDone;
            setOpaque(false);
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    anchor = e.getPoint();
                    selection = new Rectangle(anchor.x, anchor.y, 0, 0);
                    repaint();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (anchor != null) {
                        Point p = e.getPoint();
                        selection = CaptureRegions.normalise(anchor.x, anchor.y,
                                p.x, p.y);
                        repaint();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    finish(selection);
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        finish(null);
                    }
                }
            });
        }

        void setTranslucent(boolean value) {
            this.translucent = value;
            setOpaque(false);
        }

        private void finish(Rectangle region) {
            if (finished) {
                return;
            }
            finished = true;
            Rectangle result = toScreen(region);
            window.dispose();
            if (onDone != null) {
                onDone.accept(result);
            }
        }

        /** Converts a pane-local region to screen coords; null when too small. */
        private Rectangle toScreen(Rectangle region) {
            if (region == null || region.width < MIN_DRAG_PX
                    || region.height < MIN_DRAG_PX) {
                return null;
            }
            Rectangle local = CaptureRegions.clamp(region,
                    new Rectangle(0, 0, getWidth(), getHeight()));
            if (local.isEmpty()) {
                return null;
            }
            return new Rectangle(local.x + bounds.x, local.y + bounds.y,
                    local.width, local.height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                if (translucent) {
                    g2.setColor(new Color(0, 0, 0, 110));
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    if (selection != null && !selection.isEmpty()) {
                        g2.setComposite(AlphaComposite.Clear);
                        g2.fillRect(selection.x, selection.y, selection.width,
                                selection.height);
                        g2.setComposite(AlphaComposite.SrcOver);
                    }
                } else {
                    g2.setColor(new Color(20, 24, 32));
                    g2.fillRect(0, 0, getWidth(), getHeight());
                }
                paintHint(g2);
                paintSelectionOutline(g2);
            } finally {
                g2.dispose();
            }
        }

        private void paintHint(Graphics2D g2) {
            String hint = "Drag to select the area to capture  \u2022  Esc cancels";
            g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            g2.setColor(new Color(255, 255, 255, 220));
            g2.drawString(hint, 16, 28);
        }

        private void paintSelectionOutline(Graphics2D g2) {
            if (selection == null || selection.isEmpty()) {
                return;
            }
            g2.setColor(new Color(90, 165, 255));
            g2.drawRect(selection.x, selection.y, selection.width,
                    selection.height);
            String size = selection.width + " \u00d7 " + selection.height;
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g2.setColor(new Color(90, 165, 255, 220));
            int tx = selection.x;
            int ty = selection.y - 6;
            if (ty < 14) {
                ty = selection.y + 16;
            }
            g2.drawString(size, tx, ty);
        }
    }
}
