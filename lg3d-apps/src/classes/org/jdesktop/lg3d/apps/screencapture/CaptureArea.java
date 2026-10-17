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

/**
 * The three areas the Screen Snapshot app can capture when its delayed
 * snapshot fires. The user picks exactly one in the config frame; the capture
 * engine then grabs that area:
 *
 * <ul>
 *   <li>{@link #FULL_SCREEN} &mdash; the whole screen composited into a single
 *       image.</li>
 *   <li>{@link #ALL_WINDOWS} &mdash; every open application window written to
 *       its own image.</li>
 *   <li>{@link #SELECTION} &mdash; a region the user drags out on a
 *       full-screen overlay, captured as a single cropped image.</li>
 * </ul>
 *
 * <p>This enum is pure (no AWT window, no scene graph) so it is constructed and
 * asserted freely under {@code java.awt.headless}.</p>
 */
public enum CaptureArea {

    /** The whole screen, as one image. */
    FULL_SCREEN("Whole screen"),

    /** Every open window, one image each. */
    ALL_WINDOWS("All windows"),

    /** A region the user drags out on screen. */
    SELECTION("A region I select");

    private final String label;

    CaptureArea(String label) {
        this.label = label;
    }

    /**
     * The human-readable label shown on the config frame's radio button for
     * this area.
     *
     * @return the button label (never null)
     */
    public String label() {
        return label;
    }
}
