/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
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
package org.jdesktop.lg3d.utils.system;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads hardware temperature sensors.
 *
 * <p>Three sources are tried in order, so the temperature widget works across
 * distros and kernel configurations:</p>
 * <ol>
 *   <li>{@code /sys/class/thermal/thermal_zone*} - the generic thermal framework
 *       ({@code type} + {@code temp} in millidegrees Celsius).</li>
 *   <li>{@code /sys/class/hwmon} chip directories - the hardware monitoring
 *       subsystem (chip {@code name} plus per-input {@code tempN_label}).</li>
 *   <li>{@code sensors} (lm-sensors) output, parsed as a last resort.</li>
 * </ol>
 *
 * <p>If none are available (common in VMs and some containers) {@link #read()}
 * returns an empty list and {@link #isAvailable()} is false; callers show
 * "n/a" rather than failing.</p>
 */
public final class ThermalService {
    private static final Logger logger = Logger.getLogger("lg.system");

    private static final Pattern SENSORS_TEMP =
            Pattern.compile("([^:]+):\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*°?C");

    private ThermalService() {
        // no instances
    }

    /** One temperature sensor reading. */
    public static final class Sensor {
        private final String label;
        private final double celsius;
        private final Double critical;
        private final String source;

        public Sensor(String label, double celsius, Double critical, String source) {
            this.label = label;
            this.celsius = celsius;
            this.critical = critical;
            this.source = source;
        }

        public String getLabel() { return label; }
        public double getCelsius() { return celsius; }
        /** Critical trip point in C, or null if unknown. */
        public Double getCritical() { return critical; }
        public String getSource() { return source; }

        @Override
        public String toString() {
            return label + ": " + String.format("%.1f", celsius) + "C";
        }
    }

    /** True if at least one sensor can be read. */
    public static boolean isAvailable() {
        return !read().isEmpty();
    }

    /** Reads all discoverable temperature sensors (may be empty). */
    public static List<Sensor> read() {
        List<Sensor> sensors = new ArrayList<>();
        readThermalZones(sensors);
        if (sensors.isEmpty()) {
            readHwmon(sensors);
        }
        if (sensors.isEmpty() && ProcessRunner.isAvailable("sensors")) {
            readSensorsCommand(sensors);
        }
        return sensors;
    }

    /**
     * The most representative CPU/package temperature: prefers a sensor whose
     * label mentions package/core/cpu/soc, otherwise the hottest reading,
     * otherwise null when nothing is available.
     */
    public static Sensor cpuTemperature() {
        List<Sensor> sensors = read();
        if (sensors.isEmpty()) {
            return null;
        }
        Sensor best = null;
        for (Sensor s : sensors) {
            String l = s.getLabel().toLowerCase();
            if (l.contains("package") || l.contains("core") || l.contains("cpu")
                    || l.contains("soc") || l.contains("x86_pkg") || l.contains("tdie")) {
                if (best == null || s.getCelsius() > best.getCelsius()) {
                    best = s;
                }
            }
        }
        if (best != null) {
            return best;
        }
        // No obvious CPU sensor: return the hottest one.
        Sensor hottest = sensors.get(0);
        for (Sensor s : sensors) {
            if (s.getCelsius() > hottest.getCelsius()) {
                hottest = s;
            }
        }
        return hottest;
    }

    // ------------------------------------------------------------------

    private static void readThermalZones(List<Sensor> out) {
        Path root = Paths.get("/sys/class/thermal");
        if (!Files.isDirectory(root)) {
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root, "thermal_zone*")) {
            List<Path> zones = new ArrayList<>();
            ds.forEach(zones::add);
            zones.sort(Path::compareTo);
            for (Path zone : zones) {
                Long tempMilli = Proc.readLong(zone.resolve("temp").toString());
                if (tempMilli == null) {
                    continue;
                }
                String type = Proc.read(zone.resolve("type").toString());
                String label = (type != null && !type.isBlank()) ? type.trim()
                        : zone.getFileName().toString();
                Double crit = null;
                Long critMilli = Proc.readLong(zone.resolve("trip_point_0_temp").toString());
                if (critMilli != null && critMilli > 0) {
                    crit = critMilli / 1000.0;
                }
                out.add(new Sensor(label, tempMilli / 1000.0, crit, zone.toString()));
            }
        } catch (IOException | RuntimeException e) {
            logger.log(Level.FINE, "Could not read thermal zones", e);
        }
    }

    private static void readHwmon(List<Sensor> out) {
        Path root = Paths.get("/sys/class/hwmon");
        if (!Files.isDirectory(root)) {
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root, "hwmon*")) {
            List<Path> chips = new ArrayList<>();
            ds.forEach(chips::add);
            chips.sort(Path::compareTo);
            for (Path chip : chips) {
                String chipName = Proc.read(chip.resolve("name").toString());
                if (chipName == null || chipName.isBlank()) {
                    chipName = chip.getFileName().toString();
                }
                chipName = chipName.trim();
                try (DirectoryStream<Path> inputs = Files.newDirectoryStream(chip, "temp*_input")) {
                    List<Path> files = new ArrayList<>();
                    inputs.forEach(files::add);
                    files.sort(Path::compareTo);
                    for (Path input : files) {
                        Long milli = Proc.readLong(input.toString());
                        if (milli == null) {
                            continue;
                        }
                        String fname = input.getFileName().toString();      // tempN_input
                        String prefix = fname.substring(0, fname.length() - "_input".length());
                        String label = Proc.read(chip.resolve(prefix + "_label").toString());
                        if (label == null || label.isBlank()) {
                            label = chipName + " " + prefix;
                        }
                        Double crit = null;
                        Long critMilli = Proc.readLong(chip.resolve(prefix + "_crit").toString());
                        if (critMilli != null && critMilli > 0) {
                            crit = critMilli / 1000.0;
                        }
                        out.add(new Sensor(label.trim(), milli / 1000.0, crit, input.toString()));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.log(Level.FINE, "Could not read hwmon", e);
        }
    }

    private static void readSensorsCommand(List<Sensor> out) {
        ProcessRunner.Result r = ProcessRunner.run("sensors");
        if (!r.isSuccess()) {
            return;
        }
        for (String line : r.getStdoutLines()) {
            Matcher m = SENSORS_TEMP.matcher(line);
            if (m.find()) {
                String label = m.group(1).trim();
                double c = Proc.parseDouble(m.group(2), Double.NaN);
                if (!Double.isNaN(c)) {
                    out.add(new Sensor(label, c, null, "sensors"));
                }
            }
        }
    }
}
