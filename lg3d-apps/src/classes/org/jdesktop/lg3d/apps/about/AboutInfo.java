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
package org.jdesktop.lg3d.apps.about;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jdesktop.lg3d.displayserver.DesktopMode;

/**
 * The headless model behind {@link AboutPanel}: the product identity, the
 * resolved build version, a snapshot of the host runtime (Java, platform,
 * Java 3D) and the attribution / licence text shown in the About window.
 *
 * <p>Deliberately free of Swing/AWT imports so it can be exercised headless
 * (the same split {@code CalculatorEngine} uses). Every accessor is a pure
 * static read of system properties or classpath metadata, and the version /
 * Java 3D resolution is factored into package-private helpers that take their
 * inputs explicitly, so the fallbacks are unit-testable without mutating the
 * JVM's global state.</p>
 *
 * <p>The build version is <em>not</em> hardcoded here. It is resolved from the
 * {@code lg.version} system property (which the Gradle {@code run} task sets to
 * the canonical {@code project.version}), falling back to the jar manifest's
 * {@code Implementation-Version} and finally to {@link #UNKNOWN}. This keeps a
 * single source of truth for the version instead of adding a fifth literal
 * copy that a version bump could miss.</p>
 */
public final class AboutInfo {

    /** System property carrying the canonical build version at runtime. */
    public static final String VERSION_PROPERTY = "lg.version";

    /** Shown when no version can be resolved from any source. */
    public static final String UNKNOWN = "unknown";

    /** The product name as it appears in the window title and heading. */
    public static final String PRODUCT_NAME = "Project Looking Glass";

    /** One-line tagline rendered under the product name. */
    public static final String TAGLINE = "A 3D desktop environment for the Java platform";

    /** Short blurb describing what the project is. */
    public static final String DESCRIPTION =
            "Project Looking Glass (lg3d) is an immersive 3D desktop built on "
            + "Java 3D. Applications live as textured panels in a navigable "
            + "3D space that can be moved, resized, rotated and parked on a "
            + "bookshelf, alongside a conventional 2D/Swing desktop for hosts "
            + "without 3D acceleration.";

    /** The modernization port edition line. */
    public static final String EDITION =
            "Gradle 8 / JDK 21 modernization port";

    /** Attribution shown in the credits block. */
    public static final String CREDITS =
            "Modernization port, fixes and additional applications by "
            + "Jean-Francois Landreville (2026), based on the original "
            + "Project Looking Glass by Sun Microsystems, Inc. (2004-2006) "
            + "and its community contributors. Java 3D provided by the "
            + "Jogamp project.";

    /** Licence summary line. */
    public static final String LICENSE =
            "Distributed under the GNU General Public License, Version 2.";

    // ------------------------------------------------------------------
    // Conventional 2D/Swing desktop variants.
    //
    // The 2D desktop is not the immersive 3D environment, so its About box
    // must not describe it as one: these variants drop every "3D" claim and
    // the Java 3D runtime row, and credit the author without the ported-base
    // lineage line. They are selected when the desktop is started in the
    // conventional 2D/Swing mode (lg.fws.mode=2d or swing).
    // ------------------------------------------------------------------

    /** One-line tagline for the conventional 2D/Swing desktop. */
    public static final String TAGLINE_2D =
            "A desktop environment for the Java platform";

    /** Short blurb describing the conventional 2D/Swing desktop. */
    public static final String DESCRIPTION_2D =
            "Project Looking Glass is a desktop environment for the Java "
            + "platform. Applications open as windows on a conventional Swing "
            + "desktop, with a taskbar, a start menu, workspaces and a system "
            + "tray.";

    /** Attribution for the conventional 2D/Swing desktop. */
    public static final String CREDITS_2D =
            "Created by Jean-Francois Landreville.";

    private AboutInfo() {
        // no instances
    }

    /**
     * True when the desktop is running as the conventional 2D/Swing desktop,
     * i.e. when {@code lg.fws.mode} selects the MDI 2D shell. Reads the mode
     * property only (no 3D probe), so it is cheap and headless-safe.
     */
    public static boolean is2D() {
        return is2DMode(System.getProperty(DesktopMode.MODE_PROPERTY));
    }

    /**
     * Pure helper: true when {@code modeProperty} selects the conventional
     * 2D/Swing desktop ({@code 2d} or {@code swing}, case-insensitive). Split
     * out so both branches are unit-testable without mutating global state.
     */
    static boolean is2DMode(String modeProperty) {
        String mode = (modeProperty == null) ? null : modeProperty.trim();
        return DesktopMode.MODE_2D.equalsIgnoreCase(mode)
                || DesktopMode.MODE_SWING.equalsIgnoreCase(mode);
    }

    /** The tagline for the current desktop mode. */
    public static String tagline() {
        return tagline(is2D());
    }

    /** Pure selector: the 2D tagline when {@code twoD}, else the 3D one. */
    static String tagline(boolean twoD) {
        return twoD ? TAGLINE_2D : TAGLINE;
    }

    /** The description for the current desktop mode. */
    public static String description() {
        return description(is2D());
    }

    /** Pure selector: the 2D description when {@code twoD}, else the 3D one. */
    static String description(boolean twoD) {
        return twoD ? DESCRIPTION_2D : DESCRIPTION;
    }

    /** The attribution text for the current desktop mode. */
    public static String credits() {
        return credits(is2D());
    }

    /** Pure selector: the 2D credits when {@code twoD}, else the full ones. */
    static String credits(boolean twoD) {
        return twoD ? CREDITS_2D : CREDITS;
    }

    /** An immutable label/value row shown in the system-information grid. */
    public static final class Field {
        private final String label;
        private final String value;

        public Field(String label, String value) {
            this.label = label;
            this.value = value;
        }

        public String getLabel() {
            return label;
        }

        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return label + ": " + value;
        }
    }

    /**
     * Resolves the build version. Reads {@value #VERSION_PROPERTY} from the
     * system properties, then the jar manifest {@code Implementation-Version},
     * and finally {@link #UNKNOWN}.
     */
    public static String getVersion() {
        return resolveVersion(
                System.getProperty(VERSION_PROPERTY),
                manifestVersion());
    }

    /**
     * Pure version-resolution helper: the first non-blank of {@code sysProp}
     * and {@code manifest}, else {@link #UNKNOWN}. Split out so the fallback
     * chain is unit-testable without touching the global system properties.
     */
    static String resolveVersion(String sysProp, String manifest) {
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp.trim();
        }
        if (manifest != null && !manifest.isBlank()) {
            return manifest.trim();
        }
        return UNKNOWN;
    }

    /** The {@code Implementation-Version} of this jar's manifest, else null. */
    private static String manifestVersion() {
        return AboutInfo.class.getPackage().getImplementationVersion();
    }

    /**
     * Resolves the Java 3D provider line. Reflectively reads the Jogamp
     * {@code org.jogamp.java3d} package implementation version; on any failure
     * (Java 3D absent from the classpath, as in a pure-2D host) it degrades to
     * a plain provider name rather than throwing.
     */
    public static String getJava3D() {
        return resolveJava3D(java3dPackageVersion());
    }

    /** The Jogamp Java 3D package implementation version, else null. */
    private static String java3dPackageVersion() {
        try {
            Class<?> universe = Class.forName("org.jogamp.java3d.VirtualUniverse");
            return universe.getPackage().getImplementationVersion();
        } catch (Throwable t) {
            // Java 3D is optional at runtime (the 2D desktop never loads it).
            return null;
        }
    }

    /**
     * Pure Java 3D-line helper: {@code "Jogamp Java 3D <version>"} when a
     * version is known, else the bare provider name. Split out for headless
     * testing of both branches.
     */
    static String resolveJava3D(String version) {
        if (version != null && !version.isBlank()) {
            return "Jogamp Java 3D " + version.trim();
        }
        return "Jogamp Java 3D";
    }

    /** The ordered system-information rows shown in the About window. */
    public static List<Field> getFields() {
        return getFields(is2D());
    }

    /**
     * The ordered system-information rows for a given desktop mode. The
     * conventional 2D/Swing desktop omits the Java 3D row, since it never
     * loads the scene graph and its About box must not advertise 3D.
     */
    public static List<Field> getFields(boolean twoD) {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("Version", getVersion()));
        fields.add(new Field("Edition", EDITION));
        if (!twoD) {
            fields.add(new Field("Java 3D", getJava3D()));
        }
        fields.add(new Field("Java",
                System.getProperty("java.version", UNKNOWN) + " ("
                + System.getProperty("java.vendor", UNKNOWN) + ")"));
        fields.add(new Field("Platform",
                System.getProperty("os.name", UNKNOWN) + " "
                + System.getProperty("os.version", "") + " ("
                + System.getProperty("os.arch", UNKNOWN) + ")"));
        return Collections.unmodifiableList(fields);
    }
}
