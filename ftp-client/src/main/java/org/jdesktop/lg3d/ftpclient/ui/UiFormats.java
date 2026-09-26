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
package org.jdesktop.lg3d.ftpclient.ui;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Small presentation helpers shared by the transfer queue and the two browsers
 * so sizes and timestamps are formatted identically everywhere. Pure and
 * stateless; kept package-private because it is an implementation detail of the
 * Swing layer.
 */
final class UiFormats {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    private UiFormats() {
    }

    /**
     * Formats a byte count with binary-ish scaling and one decimal place.
     *
     * @param bytes the size; a negative value means "unknown"
     * @return e.g. {@code "1.5 MB"}, or an empty string when unknown
     */
    static String bytes(long bytes) {
        if (bytes < 0) {
            return "";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double v = bytes;
        int unit = 0;
        while (v >= 1024 && unit < UNITS.length - 1) {
            v /= 1024;
            unit++;
        }
        return String.format("%.1f %s", v, UNITS[unit]);
    }

    /**
     * Formats an epoch-millis timestamp as {@code yyyy-MM-dd HH:mm}.
     *
     * @param millis the timestamp; {@code <= 0} means "unknown"
     * @return the formatted local time, or an empty string when unknown
     */
    static String timestamp(long millis) {
        if (millis <= 0) {
            return "";
        }
        // A fresh formatter per call keeps this thread-safe (SimpleDateFormat is
        // not), which matters because the browsers refresh from worker threads.
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(millis));
    }

    /**
     * Formats a completion fraction as a whole percentage.
     *
     * @param fraction a value in {@code [0.0, 1.0]}
     * @return e.g. {@code "42%"}
     */
    static String percent(double fraction) {
        double f = Math.max(0.0, Math.min(1.0, fraction));
        return Math.round(f * 100.0) + "%";
    }
}
