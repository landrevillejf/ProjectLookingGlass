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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Screen backlight brightness for the 2D taskbar indicator.
 *
 * <p>The parsing, scaling and formatting are pure ({@link #parse},
 * {@link #percentFromRaw}, {@link #rawFromPercent}, {@link #glyph},
 * {@link #label}) and unit-tested; {@link #read()} / {@link #setBrightness(int)}
 * are the thin platform probe over the Linux {@code /sys/class/backlight/*}
 * sysfs node. When no backlight device exists (a desktop with no controllable
 * panel, a non-Linux host, headless CI) {@link #read()} returns
 * {@link Optional#empty()} and {@link #setBrightness(int)} is a no-op, so the
 * indicator hides itself rather than showing garbage — mirroring
 * {@link VolumeStatus} and {@link BatteryStatus}.</p>
 */
final class BrightnessStatus {

    /** Base of the Linux backlight sysfs tree. */
    private static final String BACKLIGHT_DIR = "/sys/class/backlight";

    /** How long a privileged write may run before it is abandoned. */
    private static final long WRITE_TIMEOUT_SECONDS = 10L;

    private BrightnessStatus() {
        // no instances
    }

    /** A brightness reading: backlight level as a percentage (0-100). */
    record Level(int percent) {
    }

    /** Clamps a percentage into 0-100. */
    static int clamp(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    /**
     * Converts a raw backlight value onto a 0-100 percentage, linear across
     * {@code 0..max}. Pure so it can be unit-tested without a backlight device;
     * a non-positive {@code max} yields 0.
     */
    static int percentFromRaw(int value, int max) {
        if (max <= 0) {
            return 0;
        }
        int clamped = Math.max(0, Math.min(max, value));
        return (int) Math.round(clamped * 100.0 / max);
    }

    /** The inverse of {@link #percentFromRaw}: a 0-100 percentage to a raw value. */
    static int rawFromPercent(int percent, int max) {
        if (max <= 0) {
            return 0;
        }
        return (int) Math.round(clamp(percent) / 100.0 * max);
    }

    /**
     * Parses the raw sysfs {@code brightness} and {@code max_brightness}
     * strings into a {@link Level}. Empty/unparseable values or a non-positive
     * maximum yield {@link Optional#empty()}.
     */
    static Optional<Level> parse(String brightness, String maxBrightness) {
        Integer value = parseInt(brightness);
        Integer max = parseInt(maxBrightness);
        if (value == null || max == null || max <= 0) {
            return Optional.empty();
        }
        return Optional.of(new Level(clamp(percentFromRaw(value, max))));
    }

    /** Reads the first backlight device's level, or empty when there is none. */
    static Optional<Level> read() {
        try {
            Optional<Path> device = findDevice();
            if (device.isEmpty()) {
                return Optional.empty();
            }
            Path dir = device.get();
            return parse(readFirstLine(dir.resolve("brightness")),
                    readFirstLine(dir.resolve("max_brightness")));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Sets the backlight to {@code percent} (0-100); a no-op when there is no
     * controllable device. The write is best-effort: it first tries the sysfs
     * node directly (writable on many setups via udev rules) and, only if that
     * fails, escalates once through {@code pkexec}. Any failure is swallowed so
     * dragging the slider can never throw on the EDT.
     */
    static void setBrightness(int percent) {
        try {
            Optional<Path> device = findDevice();
            if (device.isEmpty()) {
                return;
            }
            Path dir = device.get();
            Integer max = parseInt(readFirstLine(dir.resolve("max_brightness")));
            if (max == null || max <= 0) {
                return;
            }
            String raw = Integer.toString(rawFromPercent(percent, max));
            Path target = dir.resolve("brightness");
            if (!writeDirect(target, raw)) {
                writePrivileged(target, raw);
            }
        } catch (RuntimeException e) {
            // best effort: a host with no writable backlight just ignores this
        }
    }

    /** Compact taskbar text: {@code "Bri 60%"}, or {@code "Bri --"} when unknown. */
    static String glyph(Level level) {
        if (level == null) {
            return "Bri --";
        }
        return "Bri " + level.percent() + "%";
    }

    /** Detailed tooltip text. */
    static String label(Level level) {
        if (level == null) {
            return "Brightness: unavailable";
        }
        return "Brightness: " + level.percent() + "%";
    }

    private static Optional<Path> findDevice() {
        Path base = Paths.get(BACKLIGHT_DIR);
        if (!Files.isDirectory(base)) {
            return Optional.empty();
        }
        try (Stream<Path> entries = Files.list(base)) {
            return entries
                    .filter(p -> Files.isReadable(p.resolve("brightness"))
                            && Files.isReadable(p.resolve("max_brightness")))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static boolean writeDirect(Path target, String raw) {
        try {
            if (!Files.isWritable(target)) {
                return false;
            }
            Files.write(target, raw.getBytes(StandardCharsets.US_ASCII));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void writePrivileged(Path target, String raw) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "pkexec", "tee", target.toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            process.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
            process.getOutputStream().close();
            process.waitFor(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (process.isAlive()) {
                process.destroy();
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // best effort: no polkit agent, or the user cancelled — ignore
        }
    }

    private static Integer parseInt(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readFirstLine(Path file) {
        try {
            if (!Files.isReadable(file)) {
                return null;
            }
            List<String> lines = Files.readAllLines(file);
            return lines.isEmpty() ? null : lines.get(0);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
