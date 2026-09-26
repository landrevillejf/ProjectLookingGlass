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

/**
 * X11 pointer and keyboard settings for the control center, over {@code xset}.
 *
 * <p>Mirrors the {@link PrinterStatus} seam shape: the parsing and
 * command-building are pure and unit-tested ({@link #parsePointer},
 * {@link #parseAutoRepeat}, {@link #tokenAfter}, {@link #parseNumber},
 * {@link #mouseCommand}, {@link #repeatRateCommand}, {@link #repeatToggleCommand});
 * the probes are thin wrappers over {@code xset q}. {@code xset} only talks to an
 * X server, so on a pure Wayland session (or without X) {@link #available()} is
 * false and every probe degrades to null / {@code false} rather than throwing,
 * letting the panel show the same "changes may not apply" warning the Display
 * panel uses. {@code xset q} reports the live pointer acceleration and threshold
 * and the auto-repeat on/off flag, but not the current repeat rate/delay, so the
 * panel offers those as presets without a pre-selected "current" value.</p>
 */
public final class InputSettings {

    private InputSettings() {
        // no instances
    }

    /**
     * The pointer-control settings reported by {@code xset q}. {@code acceleration}
     * is the multiplier (a fraction such as {@code 3/2} is reduced to a double) and
     * {@code threshold} is the pixels-of-motion before acceleration kicks in; either
     * is {@link Double#NaN} / {@code -1} when it could not be parsed.
     */
    public record Pointer(double acceleration, int threshold) {
    }

    /** True when {@code xset q} succeeds, i.e. an X server is reachable. */
    public static boolean available() {
        return PrinterStatus.exec(new String[] {"xset", "q"}) != null;
    }

    /**
     * Parses the {@code "acceleration:  N    threshold:  M"} line from {@code xset q}
     * output, or null when there is no pointer-control line.
     */
    static Pointer parsePointer(String query) {
        if (query == null) {
            return null;
        }
        for (String raw : query.split("\n")) {
            String line = raw.trim();
            if (line.contains("acceleration:") && line.contains("threshold:")) {
                double accel = parseNumber(tokenAfter(line, "acceleration:"));
                int threshold = (int) parseNumber(tokenAfter(line, "threshold:"));
                return new Pointer(accel, threshold);
            }
        }
        return null;
    }

    /**
     * Parses the keyboard auto-repeat flag from {@code xset q} output: the
     * {@code "auto repeat:  on"} / {@code "auto repeat:  off"} token. Returns null
     * when the line is absent (the {@code "auto repeating keys:"} mask line does not
     * match, since it has no {@code "auto repeat:"} colon).
     */
    static Boolean parseAutoRepeat(String query) {
        if (query == null) {
            return null;
        }
        for (String raw : query.split("\n")) {
            String line = raw.trim();
            if (line.contains("auto repeat:")) {
                String token = tokenAfter(line, "auto repeat:").toLowerCase();
                if (token.startsWith("on")) {
                    return Boolean.TRUE;
                }
                if (token.startsWith("off")) {
                    return Boolean.FALSE;
                }
            }
        }
        return null;
    }

    /**
     * The first whitespace-delimited token after {@code key} in {@code line}, or
     * {@code ""} when the key is absent. Pure so it can be unit-tested.
     */
    static String tokenAfter(String line, String key) {
        if (line == null || key == null) {
            return "";
        }
        int idx = line.indexOf(key);
        if (idx < 0) {
            return "";
        }
        String rest = line.substring(idx + key.length()).trim();
        int end = 0;
        while (end < rest.length() && !Character.isWhitespace(rest.charAt(end))) {
            end++;
        }
        return rest.substring(0, end);
    }

    /**
     * Parses a numeric token that may be a plain decimal or an {@code a/b} fraction
     * (xset prints acceleration either way), returning {@link Double#NaN} when it
     * cannot be read. Pure so it can be unit-tested.
     */
    static double parseNumber(String token) {
        if (token == null) {
            return Double.NaN;
        }
        String t = token.trim();
        if (t.isEmpty()) {
            return Double.NaN;
        }
        int slash = t.indexOf('/');
        try {
            if (slash > 0) {
                double num = Double.parseDouble(t.substring(0, slash).trim());
                double den = Double.parseDouble(t.substring(slash + 1).trim());
                return (den == 0.0) ? Double.NaN : num / den;
            }
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** The live pointer settings, or null when X is unavailable / unparseable. */
    public static Pointer pointer() {
        String q = PrinterStatus.exec(new String[] {"xset", "q"});
        return (q == null) ? null : parsePointer(q);
    }

    /** Whether keyboard auto-repeat is on; false when X is unavailable. */
    public static boolean autoRepeatEnabled() {
        String q = PrinterStatus.exec(new String[] {"xset", "q"});
        Boolean on = (q == null) ? null : parseAutoRepeat(q);
        return on != null && on;
    }

    /** The command that sets pointer acceleration and threshold ({@code xset m}). */
    static String[] mouseCommand(String acceleration, String threshold) {
        return new String[] {"xset", "m", acceleration, threshold};
    }

    /** The command that sets the key-repeat delay (ms) and rate (chars/s). */
    static String[] repeatRateCommand(int delayMillis, int ratePerSecond) {
        return new String[] {"xset", "r", "rate",
            Integer.toString(delayMillis), Integer.toString(ratePerSecond)};
    }

    /** The command that turns keyboard auto-repeat on or off ({@code xset r on/off}). */
    static String[] repeatToggleCommand(boolean on) {
        return new String[] {"xset", "r", on ? "on" : "off"};
    }

    /** Applies pointer acceleration/threshold; false when X is unavailable or a value is blank. */
    public static boolean applyMouse(String acceleration, String threshold) {
        return acceleration != null && !acceleration.isBlank()
                && threshold != null && !threshold.isBlank()
                && PrinterStatus.run(mouseCommand(acceleration, threshold));
    }

    /** Applies the key-repeat rate/delay; false when X is unavailable. */
    public static boolean applyRepeatRate(int delayMillis, int ratePerSecond) {
        return PrinterStatus.run(repeatRateCommand(delayMillis, ratePerSecond));
    }

    /** Turns keyboard auto-repeat on or off; false when X is unavailable. */
    public static boolean setAutoRepeat(boolean on) {
        return PrinterStatus.run(repeatToggleCommand(on));
    }
}
