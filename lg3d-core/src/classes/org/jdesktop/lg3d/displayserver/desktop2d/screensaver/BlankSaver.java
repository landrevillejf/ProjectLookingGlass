/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.displayserver.desktop2d.screensaver;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;

/**
 * The built-in failsafe saver: a black screen carrying one slowly pulsing
 * dot. It lives in the desktop core (not the Kotlin suite in lg3d-apps) on
 * purpose - it is the last saver that must always work, so it has the
 * shortest dependency chain: AWT/Swing only, no classpath resources, no
 * images to decode, one allocation-free paint.
 *
 * <p>Two roles:</p>
 * <ul>
 *   <li>the saver offered when a broken third-party registration makes every
 *       other {@link ScreenSaver} fail (the registry always keeps this one);</li>
 *   <li>the fallback the {@link ScreensaverService} engages when the saver
 *       selected in the Control Center throws while its view is being built
 *       (a broken saver can blank the screens, but cannot crash the
 *       desktop).</li>
 * </ul>
 */
public final class BlankSaver implements ScreenSaver {

    /** The id every host code path can rely on being present. */
    public static final String ID = "blank";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Blank";
    }

    @Override
    public String description() {
        return "Plain black screen with a slow pulsing dot; the always-available failsafe.";
    }

    @Override
    public List<ScreenSaverOption> options() {
        return List.of();
    }

    @Override
    public JComponent createView(Map<String, String> options) {
        return new BlankView();
    }

    /**
     * The view: paints black plus one dot whose size oscillates on a
     * 4-second sine. A single shared {@code javax.swing.Timer} at a sleepy
     * 10 fps drives it - the "screen off soon" signal costs under a
     * millisecond per frame and stops dead on {@link #stop()}.
     */
    private static final class BlankView extends JComponent
            implements ScreenSaverView {

        private static final int FRAME_MS = 100;
        private static final long PERIOD_NS = 4_000_000_000L;

        private transient javax.swing.Timer timer;
        private long startedAt;

        BlankView() {
            setOpaque(true);
        }

        @Override
        public void start() {
            if (timer != null && timer.isRunning()) {
                return;
            }
            startedAt = System.nanoTime();
            timer = new javax.swing.Timer(FRAME_MS,
                    e -> repaint());
            timer.setCoalesce(true);
            timer.start();
        }

        @Override
        public void stop() {
            if (timer != null) {
                timer.stop();
                timer = null;
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                int w = getWidth();
                int h = getHeight();
                g2.setColor(Color.BLACK);
                g2.fillRect(0, 0, w, h);
                double phase = ((System.nanoTime() - startedAt) % PERIOD_NS)
                        / (double) PERIOD_NS;
                double r = 2 + 2 * (0.5 + 0.5 * Math.sin(phase * 2 * Math.PI));
                Dimension d = getPreferredSize();
                int cx = Math.max(w, d.width) / 2;
                int cy = Math.max(h, d.height) / 2;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0x2a3b52));
                int ri = (int) Math.round(r);
                g2.fillOval(cx - ri, cy - ri, ri * 2, ri * 2);
            } finally {
                g2.dispose();
            }
        }
    }
}
