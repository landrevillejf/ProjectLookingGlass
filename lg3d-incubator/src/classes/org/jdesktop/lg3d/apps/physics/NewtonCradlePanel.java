/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.apps.physics;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import org.jdesktop.lg3d.apps.physics.model.NewtonCradleModel;

/**
 * The 2D/Swing face of the resurrected Physics app: a Newton's cradle desk toy.
 *
 * <p>The native-3D {@link NewtonCradleDemo} builds the same scene from the
 * in-package rigid-body engine and steps it on a background thread that copies
 * transforms into the Java&nbsp;3D scene graph. That engine cannot be reused on
 * the 2D/Swing desktop, which must never load Java&nbsp;3D, so this panel drives
 * the AWT-free, Java&nbsp;3D-free {@link NewtonCradleModel} - a planar
 * transcription of the demo's dynamics over the very same physical constants -
 * and paints the result with Java&nbsp;2D. Both desktops therefore swing the
 * same cradle with the same physics; only the renderer differs.</p>
 *
 * <p>It is registered in {@code Desktop2DAppRegistry.PANEL_APPS} against the
 * {@code NewtonCradleDemo} main class, so the one shared start-menu descriptor
 * opens this panel as an MDI internal frame in the 2D/Swing desktop while the 3D
 * desktop keeps building the {@code Frame3D}.</p>
 *
 * <p>The toy starts on its own when the panel is shown and, once the impacts have
 * bled the motion down to a gentle common-mode sway, re-pulls the first ball on a
 * fixed cadence so an idle desktop keeps it going. A timer drives the animation,
 * started in {@link #addNotify()} and stopped in {@link #removeNotify()} so
 * closing the window never leaves a timer running and a headless test never
 * spawns one; the tests drive {@link #tick()} directly instead.</p>
 *
 * <p>Per the hosted-panel palette rule, no <em>widget</em> here is given a
 * hand-picked colour - the toolbar, buttons and status line all wear the active
 * look-and-feel's own chrome. The only painted colours are inside the canvas,
 * which is rendered simulation content (a steel cradle on a slate backdrop),
 * exactly as {@code ChessPanel}'s board is.</p>
 */
public class NewtonCradlePanel extends JPanel {

    /** Panel size in native pixels; the desktop window sizes itself to this. */
    public static final int WIDTH_PX = 560;
    public static final int HEIGHT_PX = 470;

    /** Animation frame interval in milliseconds (~60 fps). */
    private static final int TIMER_DELAY_MS = 16;

    /** Simulated seconds advanced per frame; matches the timer's real cadence. */
    private static final float FIXED_DT = 1f / 60f;

    /**
     * Simulated seconds a release plays before the toy re-pulls the first ball.
     * The satisfying one-in-one-out action runs for the first few seconds and, by
     * this point, has decayed into a small common-mode sway the whole row shares,
     * so the reset's snap back to the rest hang is barely visible.
     */
    private static final float RESTART_SECONDS = 9.0f;

    // --- rendered simulation content colours (not widget chrome) ------------
    private static final Color BACKDROP = new Color(0x2B, 0x2F, 0x36);
    private static final Color FRAME = new Color(0x9A, 0xA3, 0xAE);
    private static final Color STRING = new Color(0xC9, 0xCF, 0xD7);
    private static final Color BALL_HI = new Color(0xF4, 0xF6, 0xF9);
    private static final Color BALL_LO = new Color(0x5C, 0x63, 0x6E);

    private final NewtonCradleModel model = new NewtonCradleModel();
    private final CradleCanvas canvas = new CradleCanvas();
    private final JLabel statusLabel = new JLabel(" ", SwingConstants.CENTER);
    private final JButton runButton = new JButton("Pause");
    private final JButton resetButton = new JButton("Reset");

    private final Timer timer = new Timer(TIMER_DELAY_MS, e -> tick());

    private boolean running;

    public NewtonCradlePanel() {
        super(new BorderLayout(0, 0));
        setPreferredSize(new Dimension(WIDTH_PX, HEIGHT_PX));

        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));

        add(buildToolbar(), BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        refreshStatus();
    }

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        runButton.addActionListener(e -> toggleRun());
        resetButton.addActionListener(e -> reset());
        bar.add(runButton);
        bar.add(resetButton);
        return bar;
    }

    // ------------------------------------------------------------------
    // Lifecycle: run only while actually on screen
    // ------------------------------------------------------------------

    /** Starts the animation when the panel is placed in a displayable peer. */
    @Override
    public void addNotify() {
        super.addNotify();
        start();
    }

    /** Stops the animation when the panel leaves the hierarchy, so no timer leaks. */
    @Override
    public void removeNotify() {
        pause();
        super.removeNotify();
    }

    // ------------------------------------------------------------------
    // Controls (also invoked headlessly by the tests)
    // ------------------------------------------------------------------

    /** Starts the swing and flips the button to "Pause". */
    public void start() {
        running = true;
        runButton.setText("Pause");
        timer.start();
        refreshStatus();
    }

    /** Freezes the swing mid-motion and flips the button to "Start". */
    public void pause() {
        running = false;
        runButton.setText("Start");
        timer.stop();
        refreshStatus();
    }

    /** Starts a frozen cradle or freezes a running one. */
    public void toggleRun() {
        if (running) {
            pause();
        } else {
            start();
        }
    }

    /** Re-pulls the first ball and holds the current run/pause state. */
    public void reset() {
        model.reset();
        canvas.repaint();
        refreshStatus();
    }

    /** True while the animation timer is meant to be advancing the model. */
    public boolean isRunning() {
        return running;
    }

    /**
     * Advances the simulation by one frame and repaints. This is exactly what the
     * timer does on each tick, exposed so a headless test can drive the motion
     * deterministically without a display or a real timer. When the release has
     * played out for {@value #RESTART_SECONDS} simulated seconds the cradle is
     * re-pulled, so the toy keeps swinging on its own.
     */
    void tick() {
        model.step(FIXED_DT);
        if (model.getTime() >= RESTART_SECONDS) {
            model.reset();
        }
        canvas.repaint();
        refreshStatus();
    }

    private void refreshStatus() {
        statusLabel.setText(statusText());
    }

    /** The one-line state message shown under the cradle. */
    String statusText() {
        return String.format("Newton's cradle  -  t = %.1f s  -  fastest = %.2f m/s  -  %s",
                model.getTime(), model.getMaxSpeed(), running ? "running" : "paused");
    }

    // ------------------------------------------------------------------
    // The custom-painted canvas
    // ------------------------------------------------------------------

    /**
     * Paints the cradle - a steel support bar, one string per ball and the balls
     * themselves - fit to the canvas from the model's static world bounds. The
     * bounds never change as the balls swing, so the fit transform is stable and
     * the scene does not jitter or rescale frame to frame.
     */
    private final class CradleCanvas extends JPanel {

        // Cached per-paint world-to-screen transform.
        private float scale;
        private float originX;
        private float originY;

        CradleCanvas() {
            setBackground(BACKDROP);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);

            computeTransform(w, h);
            if (scale <= 0f || !Float.isFinite(scale)) {
                return;
            }

            paintFrame(g);
            paintStrings(g);
            paintBalls(g);
        }

        /** Fits the model's world bounds into the canvas, preserving aspect. */
        private void computeTransform(int w, int h) {
            float worldW = model.getMaxX() - model.getMinX();
            float worldH = model.getMaxY() - model.getMinY();
            int margin = 14;
            float availW = Math.max(1f, w - 2f * margin);
            float availH = Math.max(1f, h - 2f * margin);
            scale = Math.min(availW / worldW, availH / worldH);
            originX = (w - worldW * scale) / 2f;
            originY = (h - worldH * scale) / 2f;
        }

        /** Maps a world {@code x} (metres) to a canvas pixel. */
        private int sx(float wx) {
            return Math.round(originX + (wx - model.getMinX()) * scale);
        }

        /** Maps a world {@code y} (metres, up positive) to a canvas pixel (down). */
        private int sy(float wy) {
            return Math.round(originY + (model.getMaxY() - wy) * scale);
        }

        /** The steel support bar the strings hang from. */
        private void paintFrame(Graphics2D g) {
            int count = model.getBallCount();
            int left = sx(model.getAnchorX(0));
            int right = sx(model.getAnchorX(count - 1));
            int pad = Math.max(6, Math.round(model.getBallRadius() * scale));
            int y = sy(model.getAnchorY());
            int barH = Math.max(4, Math.round(model.getBallRadius() * scale * 0.9f));
            g.setColor(FRAME);
            g.fillRoundRect(left - pad, y - barH / 2,
                    (right - left) + 2 * pad, barH, barH, barH);
        }

        /** One string per ball, from its anchor to the ball centre. */
        private void paintStrings(Graphics2D g) {
            g.setColor(STRING);
            for (int i = 0; i < model.getBallCount(); i++) {
                g.drawLine(sx(model.getAnchorX(i)), sy(model.getAnchorY()),
                        sx(model.getBallX(i)), sy(model.getBallY(i)));
            }
        }

        /** The balls, shaded with a radial gradient for a sphere look. */
        private void paintBalls(Graphics2D g) {
            float r = model.getBallRadius() * scale;
            if (r <= 0f) {
                return;
            }
            int diameter = Math.max(1, Math.round(2f * r));
            for (int i = 0; i < model.getBallCount(); i++) {
                int cx = sx(model.getBallX(i));
                int cy = sy(model.getBallY(i));
                Point2D highlight =
                        new Point2D.Float(cx - r * 0.35f, cy - r * 0.35f);
                g.setPaint(new RadialGradientPaint(highlight, r * 1.15f,
                        new float[] {0f, 1f}, new Color[] {BALL_HI, BALL_LO}));
                g.fillOval(Math.round(cx - r), Math.round(cy - r), diameter, diameter);
            }
            g.setPaint(null);
        }
    }

    // ------------------------------------------------------------------
    // Test hooks (package-private)
    // ------------------------------------------------------------------

    NewtonCradleModel model() {
        return model;
    }

    /**
     * Test hook: gives the canvas a real pixel size so the fit-to-world transform
     * can be exercised without a display.
     */
    void sizeCanvasForTest(int w, int h) {
        canvas.setSize(w, h);
    }
}
