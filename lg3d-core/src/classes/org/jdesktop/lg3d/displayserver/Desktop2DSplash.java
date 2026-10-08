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
package org.jdesktop.lg3d.displayserver;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * A minimal start-up splash for the conventional <strong>2D/Swing</strong>
 * desktop, shown while the desktop shell is being built and disposed the moment
 * it is on screen.
 *
 * <p>The 3D desktop has always shown the artwork splash driven by
 * {@link SplashStarter}/{@link SplashWindow} (a full-bleed image plus a version
 * and build-date caption). The 2D desktop showed nothing, so a cold start looked
 * like a hang while the shell, the start menu and the restored session were
 * assembled. This gives it an equivalent cue: an undecorated, always-on-top
 * window carrying the product name, the resolved build version and - as a nod to
 * the remote-viewing conceit behind the product's name - a "looking-glass"
 * mirror in which the project mascot (the same icon the About window shows) is
 * seen through the glass with a faded reflection beneath it. It never loads the
 * artwork splash and never touches the 3D code path.</p>
 *
 * <p>Like the rest of the desktop's dialogs, the content logic is split from the
 * window assembly so it is unit-testable headless: {@link #resolveVersion},
 * {@link #versionLine} and {@link #buildContent} construct only a {@code JPanel}
 * (no top-level window), while {@link #show} and {@link #dispose} create and
 * tear down the {@code JWindow} and are no-ops when the JVM is headless. The
 * version is resolved the same way {@code AboutInfo}/{@code AboutDialog} do it -
 * the {@value #VERSION_PROPERTY} system property (set by the Gradle {@code run}
 * task and the release launcher) first, then the generated
 * {@link LgBuildInfo#getVersion()}, then {@link #UNKNOWN_VERSION} - so no version
 * literal is hardcoded here and a bump cannot miss it.</p>
 */
public final class Desktop2DSplash {

    private static final Logger logger = Logger.getLogger("lg.desktop2d");

    /** The product name shown as the splash heading. */
    public static final String PRODUCT_NAME = "Project Looking Glass";

    /** System property carrying the canonical build version at runtime. */
    public static final String VERSION_PROPERTY = "lg.version";

    /** Shown when no version can be resolved from any source. */
    public static final String UNKNOWN_VERSION = "unknown";

    /** Prefix on the second splash line, before the resolved version. */
    static final String VERSION_LABEL = "Version ";

    /** Heading font size, in points. */
    private static final float HEADING_FONT_SIZE = 26f;

    /**
     * Classpath location of the mascot shown in the looking-glass - the same
     * icon the About window loads, so the splash and About agree on the face of
     * the product.
     */
    static final String MASCOT_PATH = "resources/images/icon/lg3d-logo.png";

    /** Height, in pixels, the mascot is scaled to inside the looking-glass. */
    private static final int MASCOT_HEIGHT = 88;

    /** Padding between the mascot and the glass frame, in pixels. */
    private static final int GLASS_PAD = 8;

    /** Corner arc of the rounded looking-glass, in pixels. */
    private static final float GLASS_ARC = 26f;

    /** Gap between the glass and its reflection, in pixels. */
    private static final int REFLECTION_GAP = 4;

    /** Fraction of the mascot height used for the mirrored reflection. */
    private static final float REFLECTION_RATIO = 0.5f;

    /** The single splash window currently showing, or null when none is. */
    private static JWindow window;

    private Desktop2DSplash() {
        // no instances
    }

    // ------------------------------------------------------------------
    // Pure, headless-testable content logic
    // ------------------------------------------------------------------

    /**
     * Resolves the build version from the {@value #VERSION_PROPERTY} system
     * property, falling back to the generated {@link LgBuildInfo} version and
     * then to {@link #UNKNOWN_VERSION}.
     */
    public static String getVersion() {
        return resolveVersion(System.getProperty(VERSION_PROPERTY),
                LgBuildInfo.getVersion());
    }

    /**
     * Pure version-resolution helper: the first non-blank of {@code sysProp}
     * and {@code buildVersion}, else {@link #UNKNOWN_VERSION}. Split out so the
     * fallback chain is unit-testable without touching global system state.
     */
    static String resolveVersion(String sysProp, String buildVersion) {
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp.trim();
        }
        if (buildVersion != null && !buildVersion.isBlank()) {
            return buildVersion.trim();
        }
        return UNKNOWN_VERSION;
    }

    /** The second splash line: the version label plus the resolved version. */
    public static String versionLine() {
        return VERSION_LABEL + getVersion();
    }

    /**
     * Builds the splash content: the product name over the version line, centred
     * on a plain white card. A {@code JPanel} with no top-level window, so it can
     * be constructed and asserted on headless; {@link #show} wraps it in an
     * undecorated {@code JWindow}.
     */
    static JPanel buildContent() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(true);
        content.setBackground(Color.WHITE);
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x33, 0x66, 0x99), 2),
                BorderFactory.createEmptyBorder(36, 56, 36, 56)));

        JComponent mirror = buildMirror(loadMascot());
        if (mirror != null) {
            mirror.setAlignmentX(Component.CENTER_ALIGNMENT);
            content.add(mirror);
            content.add(Box.createVerticalStrut(14));
        }

        JLabel name = new JLabel(PRODUCT_NAME, SwingConstants.CENTER);
        name.setFont(name.getFont().deriveFont(Font.BOLD, HEADING_FONT_SIZE));
        name.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(name);
        content.add(Box.createVerticalStrut(10));

        JLabel version = new JLabel(versionLine(), SwingConstants.CENTER);
        version.setForeground(new Color(0x55, 0x55, 0x55));
        version.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(version);
        return content;
    }

    // ------------------------------------------------------------------
    // The looking-glass "mirror"
    // ------------------------------------------------------------------

    /**
     * Reads the mascot PNG from the classpath, or null when it cannot be read
     * (e.g. the runtime-resources tree is not on the classpath), so the splash
     * simply omits the mirror instead of failing.
     */
    static BufferedImage loadMascot() {
        ClassLoader loader = Desktop2DSplash.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(MASCOT_PATH)) {
            if (in == null) {
                return null;
            }
            return ImageIO.read(in);
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not load the splash mascot " + MASCOT_PATH, e);
            return null;
        }
    }

    /**
     * Builds the looking-glass mirror for {@code mascot}, or null when there is
     * no mascot to reflect. A {@code JComponent} with no top-level window, so it
     * can be constructed and painted headless.
     */
    static JComponent buildMirror(BufferedImage mascot) {
        if (mascot == null) {
            return null;
        }
        return new MirrorPanel(mascot);
    }

    /**
     * The mirror: the mascot seen through a rounded, lit "looking-glass" with a
     * metallic frame and a diagonal sheen, over a faded vertical reflection of
     * itself on the surface below - the remote-viewing nod.
     */
    private static final class MirrorPanel extends JPanel {

        private final BufferedImage mascot;
        private final BufferedImage reflection;

        MirrorPanel(BufferedImage source) {
            setOpaque(false);
            int height = Math.min(MASCOT_HEIGHT, source.getHeight());
            int width = Math.max(1, (int) Math.round(
                    (double) source.getWidth() * height / source.getHeight()));
            this.mascot = scale(source, width, height);
            this.reflection = reflect(this.mascot,
                    (int) Math.max(1, Math.round(height * REFLECTION_RATIO)));
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(
                    mascot.getWidth() + 2 * GLASS_PAD + 4,
                    2 * GLASS_PAD + mascot.getHeight()
                            + REFLECTION_GAP + reflection.getHeight() + 4);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            if (g2 == null) {
                return;
            }
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                int mx = (getWidth() - mascot.getWidth()) / 2;
                int my = GLASS_PAD + 2;
                RoundRectangle2D glass = new RoundRectangle2D.Double(
                        mx - GLASS_PAD, my - GLASS_PAD,
                        mascot.getWidth() + 2.0 * GLASS_PAD,
                        mascot.getHeight() + 2.0 * GLASS_PAD,
                        GLASS_ARC, GLASS_ARC);
                // The lit glass surface.
                g2.setPaint(new GradientPaint(0, my - GLASS_PAD,
                        new Color(0xEC, 0xF4, 0xFA),
                        0, my + mascot.getHeight() + GLASS_PAD,
                        new Color(0xB7, 0xCB, 0xDC)));
                g2.fill(glass);
                // The mascot seen through the glass.
                Shape oldClip = g2.getClip();
                g2.clip(glass);
                g2.drawImage(mascot, mx, my, null);
                // A diagonal sheen so the surface reads as reflective glass.
                g2.setComposite(AlphaComposite.getInstance(
                        AlphaComposite.SRC_OVER, 0.28f));
                g2.setPaint(new GradientPaint(mx, my, Color.WHITE,
                        mx + mascot.getWidth(), my + mascot.getHeight(),
                        new Color(255, 255, 255, 0)));
                g2.fill(glass);
                g2.setComposite(AlphaComposite.SrcOver);
                g2.setClip(oldClip);
                // The metallic frame.
                g2.setStroke(new BasicStroke(2f));
                g2.setPaint(new GradientPaint(0, my - GLASS_PAD,
                        new Color(0x8A, 0x9B, 0xA8),
                        0, my + mascot.getHeight() + GLASS_PAD,
                        new Color(0x33, 0x66, 0x99)));
                g2.draw(glass);
                // The mirrored reflection on the surface below the glass.
                g2.drawImage(reflection, mx,
                        my + mascot.getHeight() + GLASS_PAD + REFLECTION_GAP, null);
            } finally {
                g2.dispose();
            }
        }
    }

    /** A bilinear-scaled ARGB copy of {@code src}. */
    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /**
     * A vertically-flipped copy of {@code src} squeezed to {@code height} and
     * faded top-to-bottom, for the mirror reflection.
     */
    private static BufferedImage reflect(BufferedImage src, int height) {
        int w = src.getWidth();
        BufferedImage flipped = new BufferedImage(w, src.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = flipped.createGraphics();
        g.drawImage(src, 0, 0, w, src.getHeight(), 0, src.getHeight(), w, 0, null);
        g.dispose();
        BufferedImage out = scale(flipped, w, height);
        Graphics2D fade = out.createGraphics();
        fade.setComposite(AlphaComposite.DstIn);
        fade.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 0.45f),
                0, height, new Color(0, 0, 0, 0f)));
        fade.fillRect(0, 0, w, height);
        fade.dispose();
        return out;
    }

    // ------------------------------------------------------------------
    // Window assembly (needs a display; no-op when headless)
    // ------------------------------------------------------------------

    /**
     * Shows the splash, centred on screen and above every other window. Safe to
     * call from any thread: the window is realised on the event dispatch thread
     * and, when called off the EDT, this blocks until it is visible so the splash
     * is on screen before the caller queues the (EDT-blocking) desktop build. A
     * no-op when the JVM is headless, so CI and a display-less start never throw.
     */
    public static void show() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        runOnEdtAndWait(new Runnable() {
            @Override
            public void run() {
                if (window != null) {
                    return;   // already showing; never stack a second splash
                }
                JWindow splash = new JWindow();
                splash.setAlwaysOnTop(true);
                // Opaque white behind the content, so the window is never seen
                // as a bare grey peer rectangle, not even for a single frame.
                splash.setBackground(Color.WHITE);
                JPanel content = buildContent();
                splash.getContentPane().add(content, BorderLayout.CENTER);
                splash.pack();
                splash.setLocationRelativeTo(null);
                splash.setVisible(true);
                // Track the window before anything that could throw, so a later
                // dispose() always clears it and a visible splash can never be
                // orphaned on screen.
                window = splash;
                // The desktop build queued straight after this runs on the SAME
                // event dispatch thread and monopolises it for seconds, so a
                // freshly mapped window's normal expose/paint is often not
                // dispatched until the build has finished - leaving only the
                // grey peer background on screen (the reported "grey
                // rectangle"). Force one synchronous paint now, so the product
                // name and version are actually drawn into the peer before the
                // EDT is handed over to the build.
                try {
                    content.paintImmediately(0, 0, content.getWidth(),
                            content.getHeight());
                } catch (Exception e) {
                    logger.log(Level.FINE,
                            "Splash pre-paint failed; the expose pass will draw it", e);
                }
            }
        });
    }

    /**
     * Hides and disposes the splash, if one is showing. Safe from any thread; a
     * no-op when headless or when no splash is up.
     */
    public static void dispose() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        Runnable task = new Runnable() {
            @Override
            public void run() {
                if (window != null) {
                    window.setVisible(false);
                    window.dispose();
                    window = null;
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /** Runs {@code task} on the EDT, immediately if already there, else waits. */
    private static void runOnEdtAndWait(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(task);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.log(Level.FINE, "Could not show the 2D desktop splash", e);
        }
    }
}
