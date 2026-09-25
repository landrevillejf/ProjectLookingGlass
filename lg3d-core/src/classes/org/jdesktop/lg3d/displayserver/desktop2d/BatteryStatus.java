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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Battery charge for the 2D taskbar indicator.
 *
 * <p>The parsing and formatting are pure ({@link #parse}, {@link #glyph},
 * {@link #label}) and unit-tested; {@link #read()} is the thin platform probe
 * that reads the Linux {@code /sys/class/power_supply/BAT*} sysfs node and
 * returns {@link Optional#empty()} on any other OS, a desktop with no battery,
 * or an unreadable/malformed value, so the indicator simply hides itself rather
 * than showing garbage.</p>
 */
public final class BatteryStatus {

    /** Base of the Linux power-supply sysfs tree. */
    private static final String POWER_SUPPLY_DIR = "/sys/class/power_supply";

    /** Gauge colour while the pack is charging. */
    private static final Color CHARGING = new Color(0x2E, 0xCC, 0x71);
    /** Gauge colour for a comfortable charge. */
    private static final Color HEALTHY = new Color(0x27, 0xAE, 0x60);
    /** Gauge colour once the charge is getting low. */
    private static final Color LOW = new Color(0xE6, 0x7E, 0x22);
    /** Gauge colour for a critically low charge. */
    private static final Color CRITICAL = new Color(0xC0, 0x39, 0x2B);
    /** Gauge colour when there is no battery at all. */
    private static final Color ABSENT = new Color(0x7F, 0x8C, 0x8D);

    private BatteryStatus() {
        // no instances
    }

    /** A battery reading: charge percentage and whether it is charging. */
    public record Level(int percent, boolean charging) {
    }

    /**
     * Parses the raw sysfs {@code capacity} (0-100) and {@code status} strings.
     * Empty/unparseable/out-of-range capacity yields {@link Optional#empty()};
     * {@code charging} is true only when status is exactly "Charging"
     * (case-insensitive).
     */
    static Optional<Level> parse(String capacity, String status) {
        if (capacity == null) {
            return Optional.empty();
        }
        String trimmed = capacity.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        int percent;
        try {
            percent = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (percent < 0 || percent > 100) {
            return Optional.empty();
        }
        boolean charging = status != null && "charging".equalsIgnoreCase(status.trim());
        return Optional.of(new Level(percent, charging));
    }

    /** Reads the first battery's charge, or empty when there is none. */
    public static Optional<Level> read() {
        try {
            Path base = Paths.get(POWER_SUPPLY_DIR);
            if (!Files.isDirectory(base)) {
                return Optional.empty();
            }
            try (Stream<Path> entries = Files.list(base)) {
                Optional<Path> battery = entries
                        .filter(p -> p.getFileName().toString().startsWith("BAT"))
                        .findFirst();
                if (battery.isEmpty()) {
                    return Optional.empty();
                }
                Path dir = battery.get();
                return parse(readFirstLine(dir.resolve("capacity")),
                        readFirstLine(dir.resolve("status")));
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Compact taskbar text, e.g. {@code "Bat 85%"} / {@code "Bat 85% +"} (charging). */
    public static String glyph(Level level) {
        if (level == null) {
            return "";
        }
        return "Bat " + level.percent() + "%" + (level.charging() ? " +" : "");
    }

    /** Detailed tooltip text. */
    public static String label(Level level) {
        if (level == null) {
            return "No battery";
        }
        return "Battery " + level.percent() + "% ("
                + (level.charging() ? "charging" : "on battery") + ")";
    }

    /**
     * The gauge colour for a reading: a charging pack is highlighted, otherwise
     * the colour grades from green to red as the charge drains, so the taskbar
     * gauge reads at a glance without parsing the percentage.
     */
    public static Color color(Level level) {
        if (level == null) {
            return ABSENT;
        }
        if (level.charging()) {
            return CHARGING;
        }
        if (level.percent() <= 15) {
            return CRITICAL;
        }
        if (level.percent() <= 35) {
            return LOW;
        }
        return HEALTHY;
    }

    private static String readFirstLine(Path file) {
        try {
            if (!Files.isReadable(file)) {
                return null;
            }
            List<String> lines = Files.readAllLines(file);
            return lines.isEmpty() ? null : lines.get(0);
        } catch (Exception e) {
            return null;
        }
    }
}
