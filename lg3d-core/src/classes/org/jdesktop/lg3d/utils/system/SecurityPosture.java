/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.utils.system;

/**
 * The desktop-wide, in-memory holder for the Security Center's last computed
 * letter grade. It mirrors {@link NetworkCut}: the Security Center (lg3d-apps)
 * {@linkplain #publish publishes} the grade after a posture probe, and core-side
 * consumers - the 2D taskbar's privacy shield tooltip - {@linkplain #grade read}
 * it. Publishing through this seam keeps the dependency direction apps → core
 * (core never reaches back into the Security Center to compute a score).
 *
 * <p>Deliberately tiny and side-effect-free apart from one volatile string: it
 * owns no probe, no process and no I/O. The grade is a coarse hint for a tooltip,
 * never an authorization input, so a stale or absent value simply reads as "not
 * assessed yet".</p>
 */
public final class SecurityPosture {

    /** The last published grade letter; empty until the Security Center assesses the host. */
    private static volatile String grade = "";

    private SecurityPosture() {
        // no instances
    }

    /**
     * Publishes the latest grade letter (A-F). A null or blank value clears the
     * grade back to "not assessed yet"; surrounding whitespace is trimmed.
     *
     * @param gradeLetter the grade to publish
     */
    public static void publish(String gradeLetter) {
        grade = (gradeLetter == null) ? "" : gradeLetter.trim();
    }

    /**
     * The last published grade letter, or an empty string when the host has not
     * been assessed yet.
     *
     * @return the grade letter, never null
     */
    public static String grade() {
        return grade;
    }

    /** Clears the published grade (back to "not assessed yet"). */
    public static void clear() {
        grade = "";
    }

    /**
     * A short human phrase for a grade letter, for tooltips; never null. A blank
     * or null grade reads as "not assessed yet" rather than inventing a score.
     *
     * @param gradeLetter the grade letter (may be null / blank)
     * @return the tooltip phrase
     */
    public static String describe(String gradeLetter) {
        String letter = (gradeLetter == null) ? "" : gradeLetter.trim();
        return letter.isEmpty()
                ? "security grade not assessed yet"
                : "security grade " + letter;
    }
}
