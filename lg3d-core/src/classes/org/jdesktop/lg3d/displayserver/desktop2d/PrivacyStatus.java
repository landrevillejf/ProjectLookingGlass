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
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.awt.Color;
import org.jdesktop.lg3d.utils.system.TorPrivateMode;

/**
 * Private (Tor) mode formatting for the 2D taskbar indicator.
 *
 * <p>Like {@link NetworkStatus}, the classification and formatting are pure
 * ({@link #glyph}, {@link #label}, {@link #visible}) and unit-tested; the live
 * reading is just {@link TorPrivateMode#state()}, which is an in-memory read
 * with no process and no I/O. The indicator hides itself while the mode is
 * off, exactly as the battery indicator hides on a host without a battery, so
 * the cluster only carries the shield when an anonymity guarantee is actually
 * in force - and shows a loud {@code Tor !!} glyph the moment it is cut.</p>
 */
public final class PrivacyStatus {

    /** The alarm colour the cut glyph is rendered in. */
    static final Color CUT_COLOR = new Color(0xE7, 0x4C, 0x3C);

    private PrivacyStatus() {
        // no instances
    }

    /**
     * Compact taskbar glyph: {@code "Tor >>"} while the mode forces traffic
     * through tor, {@code "Tor .."} while enabling, {@code "Tor !!"} while cut
     * (the kill switch), and an empty string when off (the label hides).
     */
    public static String glyph(TorPrivateMode.State state) {
        if (state == null) {
            return "";
        }
        return switch (state) {
            case ENABLING -> "Tor ..";
            case ON -> "Tor >>";
            case CUT -> "Tor !!";
            case OFF -> "";
        };
    }

    /** Detailed tooltip text; never null. */
    public static String label(TorPrivateMode.State state) {
        if (state == null) {
            return "Private (Tor) mode: off";
        }
        return switch (state) {
            case ENABLING -> "Private (Tor) mode: enabling";
            case ON -> "Private (Tor) mode: on - traffic forced through tor";
            case CUT -> "Private (Tor) mode: CUT - tor stopped, network refused";
            case OFF -> "Private (Tor) mode: off";
        };
    }

    /**
     * True when the indicator should be visible: the mode carries a guarantee
     * worth showing (enabling, on, or cut). Off - and a null state - hide it.
     */
    public static boolean visible(TorPrivateMode.State state) {
        return state != null && state != TorPrivateMode.State.OFF;
    }

    /**
     * The glyph's foreground: alarming red while the kill switch has cut the
     * network, {@code null} (inherit the taskbar's colour) otherwise.
     */
    public static Color color(TorPrivateMode.State state) {
        return state == TorPrivateMode.State.CUT ? CUT_COLOR : null;
    }
}
