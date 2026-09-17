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

import gnu.x11.Display;
import gnu.x11.extension.NotFoundException;
import gnu.x11.extension.Shape;
import gnu.x11.extension.XTest;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 0 diagnostic for the X11 compositing integration.
 *
 * <p>Connects to the running X server (via {@code $DISPLAY}, defaulting to
 * {@code :0}) and negotiates every X extension that the lg3d compositor path
 * needs. Prints the client-requested and server-returned version for each, and
 * exits with a non-zero status if any required extension is missing.
 *
 * <p>Extensions verified:
 * <ul>
 *   <li><b>Composite</b> - redirect window rendering into offscreen pixmaps
 *       (via {@link X11CompositeExt}).</li>
 *   <li><b>DAMAGE</b> - notifications when a redirected window repaints
 *       (via {@link X11DamageExt}).</li>
 *   <li><b>XFIXES</b> - cursor-shape notifications and region primitives
 *       (via {@link X11FixesExt}, already in-tree).</li>
 *   <li><b>XTEST</b> - synthetic input injection for forwarding lg3d 3D
 *       picking results back to real X11 apps (via Escher's
 *       {@link XTest}).</li>
 *   <li><b>SHAPE</b> - non-rectangular window support (via Escher's
 *       {@link Shape}).</li>
 *   <li><b>MIT-SHM</b> - shared-memory pixel transfer for fast readback
 *       (via {@link X11ShmExt}).</li>
 * </ul>
 *
 * <p>Invoked by the Gradle task {@code :lg3d-core:verifyX11Extensions}.
 * Requires a running X server; on a headless CI machine the task should be
 * skipped rather than failed.
 */
public final class VerifyX11Extensions {

    /** Human-readable names, in the order they should be probed. */
    private static final String[] REQUIRED = {
        "Composite", "DAMAGE", "XFIXES", "XTEST", "SHAPE", "MIT-SHM"
    };

    private VerifyX11Extensions() {
        // utility class
    }

    public static void main(String[] args) {
        String displayName = (args.length > 0)
                ? args[0]
                : System.getenv("DISPLAY");
        if (displayName == null || displayName.isEmpty()) {
            displayName = ":0";
        }

        System.out.println("lg3d X11 extension verifier");
        System.out.println("  DISPLAY = " + displayName);
        System.out.println();

        Display display;
        try {
            Display.Name name = new Display.Name(displayName);
            display = new Display(name);
        } catch (RuntimeException e) {
            System.err.println("FATAL: cannot open display " + displayName + ": " + e);
            System.err.println("       is X running? is $DISPLAY set correctly?");
            System.exit(2);
            return;
        }

        List<String> missing = new ArrayList<>();
        List<String> present = new ArrayList<>();

        probe(display, "Composite", () -> {
            X11CompositeExt ext = new X11CompositeExt(display);
            return ext.server_major_version + "." + ext.server_minor_version
                 + " (client requested "
                 + X11CompositeExt.CLIENT_MAJOR_VERSION + "."
                 + X11CompositeExt.CLIENT_MINOR_VERSION + ")";
        }, present, missing);

        probe(display, "DAMAGE", () -> {
            X11DamageExt ext = new X11DamageExt(display);
            return ext.server_major_version + "." + ext.server_minor_version
                 + " (client requested "
                 + X11DamageExt.CLIENT_MAJOR_VERSION + "."
                 + X11DamageExt.CLIENT_MINOR_VERSION + ")";
        }, present, missing);

        probe(display, "XFIXES", () -> {
            X11FixesExt ext = new X11FixesExt(display);
            return ext.server_major_version + "." + ext.server_minor_version
                 + " (client requested "
                 + X11FixesExt.CLIENT_MAJOR_VERSION + "."
                 + X11FixesExt.CLIENT_MINOR_VERSION + ")";
        }, present, missing);

        probe(display, "XTEST", () -> {
            XTest ext = new XTest(display);
            return ext.server_major_version + "." + ext.server_minor_version
                 + " (client requested "
                 + XTest.CLIENT_MAJOR_VERSION + "."
                 + XTest.CLIENT_MINOR_VERSION + ")";
        }, present, missing);

        probe(display, "SHAPE", () -> {
            Shape ext = new Shape(display);
            return ext.server_major_version + "." + ext.server_minor_version
                 + " (client requested "
                 + Shape.CLIENT_MAJOR_VERSION + "."
                 + Shape.CLIENT_MINOR_VERSION + ")";
        }, present, missing);

        probe(display, "MIT-SHM", () -> {
            X11ShmExt ext = new X11ShmExt(display);
            return ext.server_major_version + "." + ext.server_minor_version
                 + " (client requested "
                 + X11ShmExt.CLIENT_MAJOR_VERSION + "."
                 + X11ShmExt.CLIENT_MINOR_VERSION + ")"
                 + (ext.shared_pixmaps_supported ? " [shared-pixmaps]" : "");
        }, present, missing);

        try {
            display.close();
        } catch (RuntimeException ignored) {
            // best-effort; the JVM is about to exit anyway
        }

        System.out.println();
        System.out.println("Summary:");
        for (String name : REQUIRED) {
            String status = present.contains(name) ? "OK" : "MISSING";
            System.out.printf("  %-12s %s%n", name, status);
        }

        if (!missing.isEmpty()) {
            System.err.println();
            System.err.println("FATAL: missing required X extensions: " + missing);
            System.err.println("       the lg3d compositor path cannot run on this X server.");
            System.exit(1);
        }

        System.out.println();
        System.out.println("All required X extensions are available. Stage 0 verified.");
    }

    /** Runs one probe and records the outcome. Never throws. */
    private static void probe(Display display, String name, VersionQuery query,
                              List<String> present, List<String> missing) {
        try {
            String version = query.get();
            System.out.printf("  %-12s server version %s%n", name, version);
            present.add(name);
        } catch (NotFoundException e) {
            System.out.printf("  %-12s NOT PRESENT on this X server%n", name);
            missing.add(name);
        } catch (RuntimeException e) {
            System.out.printf("  %-12s ERROR negotiating: %s%n", name, e);
            missing.add(name);
        }
    }

    @FunctionalInterface
    private interface VersionQuery {
        String get() throws NotFoundException;
    }
}
