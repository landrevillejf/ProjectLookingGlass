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
package org.jdesktop.lg3d.displayserver;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decides whether the desktop starts as the 3D scene-graph desktop or as the
 * conventional-Swing 2D desktop ({@code org.jdesktop.lg3d.displayserver.desktop2d}).
 *
 * <p>The decision is driven by the {@code lg.fws.mode} system property and by a
 * capability probe:</p>
 * <ul>
 *  <li>{@code lg.fws.mode=2d} - force the MDI 2D desktop (applications in
 *      internal frames inside the desktop window; manual opt-in, no prompt).</li>
 *  <li>{@code lg.fws.mode=swing} - force the conventional Swing desktop
 *      ({@link org.jdesktop.lg3d.displayserver.desktop2d.DesktopSwing}: the same
 *      MDI shell as {@code 2d} - applications in internal frames inside the
 *      desktop's {@code JDesktopPane} - under the Metal look and feel; manual
 *      opt-in, no prompt).</li>
 *  <li>{@code lg.fws.mode=3d} - force the 3D desktop and fail loudly if 3D is
 *      unavailable (the pre-2D-mode behaviour).</li>
 *  <li>any other value ({@code dev}, {@code x11}, unset) - start 3D when the
 *      probe says it can work, otherwise fall back to the MDI 2D desktop after
 *      asking the user to confirm.</li>
 * </ul>
 *
 * <p>The two probe steps mirror what boot needs: that Java 3D is on the
 * classpath and recent enough (the {@code pickfast} package, present since the
 * 1.5 build 2 release the desktop has always required), and that a graphics
 * configuration able to render 3D exists on this display. Both are queried
 * reflectively so that this class - and {@link Main}, which loads it before any
 * 3D class - stays loadable on a JVM with no Java 3D at all.</p>
 */
public final class DesktopMode {

    private static final Logger logger = Logger.getLogger("lg.displayserver");

    /** The system property selecting the desktop mode. */
    public static final String MODE_PROPERTY = "lg.fws.mode";

    /** Property value that forces the MDI conventional-Swing desktop. */
    public static final String MODE_2D = "2d";

    /**
     * Property value that forces the conventional Swing desktop
     * ({@link org.jdesktop.lg3d.displayserver.desktop2d.DesktopSwing}): the MDI
     * shell - applications in internal frames inside the desktop's
     * {@code JDesktopPane} - under the Metal look and feel.
     */
    public static final String MODE_SWING = "swing";

    /** Property value that forces the 3D desktop (and fails loudly without 3D). */
    public static final String MODE_3D = "3d";

    /**
     * Test/diagnostic property: when {@code true}, the probe reports Java 3D as
     * absent so the fallback path (prompt + 2D desktop) can be exercised on a
     * machine that does have working 3D.
     */
    public static final String SIMULATE_NO_3D_PROPERTY = "lg.2d.simulateNo3D";

    /** Class whose presence marks a Java 3D new enough for this desktop. */
    private static final String PICKFAST_CLASS =
            "org.jogamp.java3d.utils.pickfast.PickCanvas";

    /** Java 3D universe helper used to ask for a 3D-capable graphics config. */
    private static final String SIMPLE_UNIVERSE_CLASS =
            "org.jogamp.java3d.utils.universe.SimpleUniverse";

    /** The package whose {@link Package} entry distinguishes "missing" from "too old". */
    private static final String J3D_PACKAGE = "org.jogamp.java3d";

    /** Which desktop to start. */
    public enum Mode {
        /** The MDI conventional-Swing desktop (JDesktopPane + internal frames). */
        TWO_D,
        /**
         * The conventional Swing desktop: the MDI shell (applications in
         * internal frames inside the desktop's {@code JDesktopPane}) under the
         * Metal look and feel.
         */
        SWING,
        /** The Java 3D scene-graph desktop. */
        THREE_D
    }

    /**
     * What the probe found out about this machine's 3D support.
     *
     * <p>{@link #getReason()} is non-null exactly when 3D is unavailable and
     * carries a human-readable explanation for the prompt / error dialog.</p>
     */
    public static final class Capability {
        private final boolean java3dPresent;
        private final boolean glInitOk;
        private final String reason;

        public Capability(boolean java3dPresent, boolean glInitOk, String reason) {
            this.java3dPresent = java3dPresent;
            this.glInitOk = glInitOk;
            this.reason = reason;
        }

        /** True if a new-enough Java 3D is on the classpath. */
        public boolean isJava3dPresent() {
            return java3dPresent;
        }

        /** True if a 3D-capable graphics configuration was found. */
        public boolean isGlInitOk() {
            return glInitOk;
        }

        /** True if the desktop can render in 3D. */
        public boolean is3dAvailable() {
            return java3dPresent && glInitOk;
        }

        /** Why 3D is unavailable, or null when it is available. */
        public String getReason() {
            return reason;
        }
    }

    private DesktopMode() {
        // no instances
    }

    /**
     * Probes this JVM/display for 3D support. Never throws: any failure is
     * reported as an unavailable capability with a reason.
     */
    public static Capability probe() {
        // Test/diagnostic hook: pretend Java 3D is missing.
        if (Boolean.getBoolean(SIMULATE_NO_3D_PROPERTY)) {
            return new Capability(false, false,
                    "Java 3D support was reported as unavailable"
                    + " (" + SIMULATE_NO_3D_PROPERTY + "=true).");
        }

        try {
            Class.forName(PICKFAST_CLASS);
        } catch (Throwable t) {
            Package j3dPackage = Package.getPackage(J3D_PACKAGE);
            String reason = (j3dPackage == null)
                    ? "Project Looking Glass requires Java 3D 1.5 build 2 or later,\n"
                        + "but no Java 3D runtime was found on this system."
                    : "Project Looking Glass requires Java 3D 1.5 build 2 or later,\n"
                        + "but version " + j3dPackage.getImplementationVersion()
                        + " is installed.";
            logger.log(Level.INFO, "Java 3D unavailable: {0}", reason);
            return new Capability(false, false, reason);
        }

        try {
            Class<?> universe = Class.forName(SIMPLE_UNIVERSE_CLASS);
            Object config = universe.getMethod("getPreferredConfiguration").invoke(null);
            if (config == null) {
                String reason = "No graphics configuration on this display supports 3D"
                        + " rendering (no usable OpenGL context).";
                logger.info(reason);
                return new Capability(true, false, reason);
            }
            return new Capability(true, true, null);
        } catch (Throwable t) {
            String reason = "The 3D rendering pipeline could not be initialised: " + t;
            logger.log(Level.INFO, reason, t);
            return new Capability(true, false, reason);
        }
    }

    /**
     * Resolves the desktop to start from the mode property and a probe result.
     *
     * @param modeProperty    the raw {@code lg.fws.mode} value (may be null)
     * @param java3dPresent   a new-enough Java 3D is on the classpath
     * @param glInitOk        a 3D-capable graphics configuration was found
     */
    public static Mode resolve(String modeProperty, boolean java3dPresent,
                               boolean glInitOk) {
        if (MODE_2D.equalsIgnoreCase(trim(modeProperty))) {
            return Mode.TWO_D;
        }
        if (MODE_SWING.equalsIgnoreCase(trim(modeProperty))) {
            return Mode.SWING;
        }
        if (MODE_3D.equalsIgnoreCase(trim(modeProperty))) {
            return Mode.THREE_D;
        }
        return (java3dPresent && glInitOk) ? Mode.THREE_D : Mode.TWO_D;
    }

    /** Convenience overload taking a probe result. */
    public static Mode resolve(String modeProperty, Capability capability) {
        return resolve(modeProperty, capability.isJava3dPresent(),
                capability.isGlInitOk());
    }

    /**
     * True when the resolved 2D mode is a fallback rather than an explicit
     * choice, i.e. when the user should be asked to confirm before the desktop
     * switches to 2D.
     */
    public static boolean requiresConfirmation(String modeProperty,
                                              boolean java3dPresent,
                                              boolean glInitOk) {
        return resolve(modeProperty, java3dPresent, glInitOk) == Mode.TWO_D
                && !MODE_2D.equalsIgnoreCase(trim(modeProperty));
    }

    /** Convenience overload taking a probe result. */
    public static boolean requiresConfirmation(String modeProperty,
                                              Capability capability) {
        return requiresConfirmation(modeProperty, capability.isJava3dPresent(),
                capability.isGlInitOk());
    }

    private static String trim(String s) {
        return (s == null) ? null : s.trim();
    }
}
