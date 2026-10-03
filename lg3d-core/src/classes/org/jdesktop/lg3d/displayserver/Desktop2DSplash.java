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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
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
 * assembled. This gives it an equivalent - but deliberately plain - cue: an
 * undecorated, always-on-top window carrying only the product name and the
 * resolved build version, exactly as requested. It never loads the artwork
 * splash and never touches the 3D code path.</p>
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
                splash.getContentPane().add(buildContent(), BorderLayout.CENTER);
                splash.pack();
                splash.setLocationRelativeTo(null);
                splash.setVisible(true);
                window = splash;
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
