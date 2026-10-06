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

/**
 * The pure decision layer for bringing Java 3D up on the target GL stack
 * (Phase F). On the LFS/bare-Xorg host the compositor must confirm that Jogamp
 * obtains a <em>hardware</em> GLX context with DRI3 on the real GPU DDX, and
 * that lg3d's own {@code Canvas3D} window is exempted from Composite
 * redirection (else the physical screen is black). Those two facts are probed
 * live; this class turns the probe results into a single verdict plus the
 * operator-facing remediation, with no {@code gnu.x11.Display}, no GL context
 * and no reflection, so the bring-up decision is unit-testable headlessly.
 *
 * <p>It codifies the acceptance criteria in {@code docs/lfs-x11-contract.md} §5
 * (GLX/DRI3 hardware rendering) and the {@code lg3d.x11.ownwindowid} fallback
 * documented on {@link X11Compositor#exemptOwnWindow()}.</p>
 *
 * @see X11Compositor#exemptOwnWindow()
 */
public final class GlBringUpPlanner {

    private GlBringUpPlanner() {
        // pure static utility
    }

    /** The outcome of a target GL/DRI3 bring-up probe. */
    public enum Verdict {
        /** Hardware GLX + DRI3 and lg3d's own window resolved: good to go. */
        READY,
        /**
         * GL is fine but lg3d's own {@code Canvas3D} window id is unresolved;
         * the operator must pin {@code -Dlg3d.x11.ownwindowid} (or enable the
         * {@code sun.awt} export for auto-discovery) or the screen is black.
         */
        PIN_OWN_WINDOW_ID,
        /** No hardware acceleration (software rasteriser): runs, but slow. */
        SOFTWARE_ONLY,
        /** No GLX at all: Java 3D cannot obtain a context; do not start. */
        ABORT
    }

    /**
     * The immutable probe inputs the planner decides over. A live bring-up fills
     * these from {@code glxinfo} / the GLX extension query and the own-window
     * resolution; tests fill them directly.
     */
    public static final class Probe {

        private final boolean glxPresent;
        private final boolean directRendering;
        private final boolean dri3Present;
        private final long ownWindowId;

        /**
         * @param glxPresent      the GLX extension is present on the display
         * @param directRendering GLX reports direct (hardware) rendering, not
         *                        indirect/software
         * @param dri3Present     the DRI3 extension is present (real GPU DDX path)
         * @param ownWindowId     lg3d's resolved own {@code Canvas3D} window id,
         *                        or a value &lt;= 0 when unresolved
         */
        public Probe(boolean glxPresent, boolean directRendering,
                boolean dri3Present, long ownWindowId) {
            this.glxPresent = glxPresent;
            this.directRendering = directRendering;
            this.dri3Present = dri3Present;
            this.ownWindowId = ownWindowId;
        }

        /** Whether the GLX extension is present. */
        public boolean glxPresent() {
            return glxPresent;
        }

        /** Whether GLX reports direct (hardware) rendering. */
        public boolean directRendering() {
            return directRendering;
        }

        /** Whether the DRI3 extension is present. */
        public boolean dri3Present() {
            return dri3Present;
        }

        /** lg3d's resolved own window id, or &lt;= 0 when unresolved. */
        public long ownWindowId() {
            return ownWindowId;
        }

        /** True when the probe shows a hardware-accelerated GL stack. */
        public boolean hardwareAccelerated() {
            return glxPresent && directRendering && dri3Present;
        }
    }

    /**
     * Decides the bring-up verdict. Precedence: no GLX aborts; an unresolved own
     * window id must be pinned (a hard display blocker even when GL is fine);
     * otherwise a hardware stack is {@link Verdict#READY} and a software one is
     * {@link Verdict#SOFTWARE_ONLY}.
     *
     * @param probe the probe results (null is treated as no GLX)
     * @return the verdict; never null
     */
    public static Verdict plan(Probe probe) {
        if (probe == null || !probe.glxPresent()) {
            return Verdict.ABORT;
        }
        if (probe.ownWindowId() <= 0L) {
            return Verdict.PIN_OWN_WINDOW_ID;
        }
        return probe.hardwareAccelerated() ? Verdict.READY : Verdict.SOFTWARE_ONLY;
    }

    /**
     * The operator-facing remediation for a verdict (empty for
     * {@link Verdict#READY}).
     *
     * @param verdict the verdict to explain
     * @return a one-line remediation string; never null
     */
    public static String remediation(Verdict verdict) {
        if (verdict == null) {
            return "";
        }
        switch (verdict) {
            case ABORT:
                return "No GLX: install Mesa GLX and a DRI3-capable GPU driver; "
                    + "Java 3D cannot obtain a GL context.";
            case PIN_OWN_WINDOW_ID:
                return "Set -Dlg3d.x11.ownwindowid=<id>, or run with "
                    + "--add-exports java.desktop/sun.awt=ALL-UNNAMED so lg3d's "
                    + "Canvas3D window is exempted from redirection (else the "
                    + "screen is black).";
            case SOFTWARE_ONLY:
                return "Software GL only (no DRI3/direct rendering): the desktop "
                    + "runs unaccelerated; install a hardware DRI3 driver.";
            case READY:
            default:
                return "";
        }
    }

    /**
     * Resolves lg3d's own X window id from an optional override string and a
     * reflectively-discovered id — the pure decision behind
     * {@link X11Compositor#exemptOwnWindow()}. The override (decimal or
     * {@code 0x} hex) wins when it parses to a positive id; otherwise the
     * discovered id is used.
     *
     * @param override     the {@code lg3d.x11.ownwindowid} property value (may be
     *                     null/blank/invalid)
     * @param discoveredId the id discovered from the AWT peer, or &lt;= 0
     * @return the resolved id, or -1 when neither yields a positive id
     */
    public static long resolveOwnWindowId(String override, long discoveredId) {
        if (override != null && !override.trim().isEmpty()) {
            try {
                long v = Long.decode(override.trim());
                if (v > 0L) {
                    return v;
                }
            } catch (NumberFormatException e) {
                // Invalid override: fall through to the discovered id.
            }
        }
        return (discoveredId > 0L) ? discoveredId : -1L;
    }
}
