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

import java.util.ArrayList;
import java.util.List;

/**
 * System date/time-zone discovery and management for the control center.
 *
 * <p>Mirrors the {@link PrinterStatus} seam shape: the parsing, formatting and
 * command-building are pure and unit-tested ({@link #parseProperty},
 * {@link #parseBoolean}, {@link #parseTimezone}, {@link #parseNtpEnabled},
 * {@link #parseLocalTime}, {@link #parseTimezones}, {@link #setTimezoneCommand},
 * {@link #setNtpCommand}); the probes are thin wrappers over {@code timedatectl}.
 * On a host without systemd every probe degrades to an empty result /
 * {@code false} rather than throwing, so the panel shows a read-only note
 * instead of failing. Setting the zone or NTP normally needs administrator
 * privileges (polkit); a denied call simply returns {@code false}.</p>
 */
public final class TimeZoneStatus {

    private TimeZoneStatus() {
        // no instances
    }

    /** True when the {@code timedatectl} tool is present on this host. */
    public static boolean available() {
        return PrinterStatus.exec(new String[] {"timedatectl", "--version"}) != null;
    }

    /**
     * Parses one {@code Key=value} property from {@code timedatectl show} output,
     * returning the trimmed value or {@code ""} when the key is absent.
     */
    static String parseProperty(String showOutput, String key) {
        if (showOutput == null || key == null) {
            return "";
        }
        String prefix = key + "=";
        for (String raw : showOutput.split("\n")) {
            String line = raw.trim();
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    /** Interprets a systemd boolean property ({@code yes}/{@code true}/{@code 1}). */
    static boolean parseBoolean(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toLowerCase();
        return v.equals("yes") || v.equals("true") || v.equals("1") || v.equals("active");
    }

    /** The current time-zone id from {@code timedatectl show} output; {@code ""} when absent. */
    static String parseTimezone(String showOutput) {
        return parseProperty(showOutput, "Timezone");
    }

    /** Whether NTP is enabled, from {@code timedatectl show} output. */
    static boolean parseNtpEnabled(String showOutput) {
        return parseBoolean(parseProperty(showOutput, "NTP"));
    }

    /**
     * The local-time string from {@code timedatectl show} output; {@code ""} when
     * absent. Older systemd exposes it as {@code LocalTime}; newer builds report
     * the same wall-clock value as {@code TimeUSec}, so fall back to that.
     */
    static String parseLocalTime(String showOutput) {
        String local = parseProperty(showOutput, "LocalTime");
        return local.isEmpty() ? parseProperty(showOutput, "TimeUSec") : local;
    }

    /** Parses {@code timedatectl list-timezones} output into zone ids, skipping blanks. */
    static List<String> parseTimezones(String listOutput) {
        List<String> out = new ArrayList<>();
        if (listOutput == null) {
            return out;
        }
        for (String raw : listOutput.split("\n")) {
            String line = raw.trim();
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    /** The raw {@code timedatectl show} output, or null when unavailable. */
    private static String show() {
        return PrinterStatus.exec(new String[] {"timedatectl", "show"});
    }

    /** The current time-zone id, or {@code ""} when timedatectl is absent. */
    public static String currentTimezone() {
        String s = show();
        return (s == null) ? "" : parseTimezone(s);
    }

    /** The current local-time string, or {@code ""} when timedatectl is absent. */
    public static String currentLocalTime() {
        String s = show();
        return (s == null) ? "" : parseLocalTime(s);
    }

    /** Whether NTP is enabled; false when timedatectl is absent. */
    public static boolean isNtpEnabled() {
        String s = show();
        return (s != null) && parseNtpEnabled(s);
    }

    /** The known time zones, empty when timedatectl is absent. */
    public static List<String> listTimezones() {
        String out = PrinterStatus.exec(new String[] {"timedatectl", "list-timezones"});
        return (out == null) ? new ArrayList<>() : parseTimezones(out);
    }

    /** The command that sets the system time zone. */
    static String[] setTimezoneCommand(String zone) {
        return new String[] {"timedatectl", "set-timezone", zone};
    }

    /** The command that enables or disables NTP time synchronization. */
    static String[] setNtpCommand(boolean on) {
        return new String[] {"timedatectl", "set-ntp", on ? "true" : "false"};
    }

    /** Sets the system time zone; false when unavailable or the zone is blank. */
    public static boolean setTimezone(String zone) {
        return zone != null && !zone.isBlank() && PrinterStatus.run(setTimezoneCommand(zone));
    }

    /** Enables or disables NTP; false when timedatectl is unavailable. */
    public static boolean setNtp(boolean on) {
        return PrinterStatus.run(setNtpCommand(on));
    }
}
