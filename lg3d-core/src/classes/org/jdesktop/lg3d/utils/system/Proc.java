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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Low-level readers for the Linux {@code /proc} and {@code /sys} pseudo
 * filesystems, plus the two {@code getconf} constants (page size and clock
 * ticks per second) needed to turn raw kernel counters into bytes and CPU
 * seconds.
 *
 * <p>Every read is defensive: a missing or unreadable file yields an empty
 * result rather than an exception, because these files come and go (a process
 * can exit between listing {@code /proc} and reading its stat file) and because
 * the desktop must keep running on kernels/containers that expose only a
 * subset of them.</p>
 *
 * <p>Field indices below refer to the 1-based field numbers documented in
 * {@code proc(5)}.</p>
 */
public final class Proc {
    private static final Logger logger = Logger.getLogger("lg.system");

    private static volatile long cachedPageSize = -1;
    private static volatile long cachedClkTck = -1;
    private static volatile long cachedBtime = -1;

    private Proc() {
        // no instances
    }

    // ------------------------------------------------------------------
    // Generic file readers
    // ------------------------------------------------------------------

    /** Reads a whole (small) pseudo-file; returns null if unreadable. */
    public static String read(String path) {
        try {
            return Files.readString(Paths.get(path), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Reads a pseudo-file into trimmed lines; empty list if unreadable. */
    public static List<String> readLines(String path) {
        try {
            return Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return Collections.emptyList();
        }
    }

    /** True if the path exists and is readable. */
    public static boolean exists(String path) {
        return Files.isReadable(Paths.get(path));
    }

    /** Reads a pseudo-file containing a single integer (e.g. a sysfs temp). */
    public static Long readLong(String path) {
        String s = read(path);
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // System-wide constants and counters
    // ------------------------------------------------------------------

    /** Memory page size in bytes (cached; defaults to 4096). */
    public static long pageSize() {
        long ps = cachedPageSize;
        if (ps <= 0) {
            ps = parseLongFromGetconf("PAGESIZE", 4096L);
            cachedPageSize = ps;
        }
        return ps;
    }

    /** Kernel clock ticks per second, USER_HZ (cached; defaults to 100). */
    public static long clockTicksPerSecond() {
        long hz = cachedClkTck;
        if (hz <= 0) {
            hz = parseLongFromGetconf("CLK_TCK", 100L);
            cachedClkTck = hz;
        }
        return hz;
    }

    private static long parseLongFromGetconf(String name, long fallback) {
        ProcessRunner.Result r = ProcessRunner.run("getconf", name);
        if (r.isSuccess()) {
            try {
                return Long.parseLong(r.getStdout().trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return fallback;
    }

    /** Boot time as seconds since the epoch (from {@code /proc/stat btime}). */
    public static long bootTimeSeconds() {
        long b = cachedBtime;
        if (b <= 0) {
            for (String line : readLines("/proc/stat")) {
                if (line.startsWith("btime ")) {
                    b = parseLong(line.substring(6).trim(), 0L);
                    break;
                }
            }
            if (b > 0) {
                cachedBtime = b;
            }
        }
        return b;
    }

    /** System uptime in seconds (from {@code /proc/uptime}). */
    public static double uptimeSeconds() {
        String s = read("/proc/uptime");
        if (s == null) {
            return 0d;
        }
        String[] parts = s.trim().split("\\s+");
        return (parts.length > 0) ? parseDouble(parts[0], 0d) : 0d;
    }

    /** The 1/5/15 minute load averages (from {@code /proc/loadavg}). */
    public static double[] loadAverage() {
        String s = read("/proc/loadavg");
        double[] out = new double[] { 0d, 0d, 0d };
        if (s == null) {
            return out;
        }
        String[] parts = s.trim().split("\\s+");
        for (int i = 0; i < out.length && i < parts.length; i++) {
            out[i] = parseDouble(parts[i], 0d);
        }
        return out;
    }

    /** Aggregate CPU jiffies from the {@code cpu } line of {@code /proc/stat}.
     *  Order: user, nice, system, idle, iowait, irq, softirq, steal. */
    public static long[] cpuTimes() {
        for (String line : readLines("/proc/stat")) {
            if (line.startsWith("cpu ")) {
                String[] parts = line.trim().split("\\s+");
                long[] out = new long[Math.max(0, parts.length - 1)];
                for (int i = 1; i < parts.length; i++) {
                    out[i - 1] = parseLong(parts[i], 0L);
                }
                return out;
            }
        }
        return new long[0];
    }

    /** Parsed {@code /proc/meminfo} values, all in kilobytes. */
    public static final class MemInfo {
        public long memTotalKb;
        public long memFreeKb;
        public long memAvailableKb = -1;
        public long buffersKb;
        public long cachedKb;
        public long swapTotalKb;
        public long swapFreeKb;

        /** Used memory estimate: total - available (falling back to a
         *  total - free - buffers - cached calculation on old kernels). */
        public long usedKb() {
            if (memAvailableKb >= 0) {
                return Math.max(0, memTotalKb - memAvailableKb);
            }
            return Math.max(0, memTotalKb - memFreeKb - buffersKb - cachedKb);
        }
    }

    /** Reads {@code /proc/meminfo}. */
    public static MemInfo memInfo() {
        MemInfo m = new MemInfo();
        for (String line : readLines("/proc/meminfo")) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String rest = line.substring(colon + 1).trim();
            String[] parts = rest.split("\\s+");
            long value = (parts.length > 0) ? parseLong(parts[0], 0L) : 0L;
            switch (key) {
                case "MemTotal":     m.memTotalKb = value; break;
                case "MemFree":      m.memFreeKb = value; break;
                case "MemAvailable": m.memAvailableKb = value; break;
                case "Buffers":      m.buffersKb = value; break;
                case "Cached":       m.cachedKb = value; break;
                case "SwapTotal":    m.swapTotalKb = value; break;
                case "SwapFree":     m.swapFreeKb = value; break;
                default: break;
            }
        }
        return m;
    }

    // ------------------------------------------------------------------
    // Per-process readers
    // ------------------------------------------------------------------

    /** Parsed interesting fields from {@code /proc/<pid>/stat}. */
    public static final class Stat {
        public String comm = "";
        public char state = '?';
        public long utime;      // field 14, clock ticks
        public long stime;      // field 15, clock ticks
        public long startTime;  // field 22, clock ticks since boot
        public boolean valid;
    }

    /**
     * Parses {@code /proc/<pid>/stat}. The comm field (2) is wrapped in
     * parentheses and may itself contain spaces and parentheses, so fields are
     * located relative to the <em>last</em> {@code )}.
     */
    public static Stat stat(long pid) {
        Stat s = new Stat();
        String content = read("/proc/" + pid + "/stat");
        if (content == null) {
            return s;
        }
        int open = content.indexOf('(');
        int close = content.lastIndexOf(')');
        if (open < 0 || close < 0 || close <= open) {
            return s;
        }
        s.comm = content.substring(open + 1, close);
        String[] f = content.substring(close + 1).trim().split("\\s+");
        // f[0] = field 3 (state); field N -> index N-3.
        s.valid = true;
        if (f.length >= 1 && !f[0].isEmpty()) {
            s.state = f[0].charAt(0);
        }
        if (f.length > 11) s.utime = parseLong(f[11], 0L);       // field 14
        if (f.length > 12) s.stime = parseLong(f[12], 0L);       // field 15
        if (f.length > 19) s.startTime = parseLong(f[19], 0L);   // field 22
        return s;
    }

    /** Resident set size in pages from {@code /proc/<pid>/statm} (field 2). */
    public static long rssPages(long pid) {
        String content = read("/proc/" + pid + "/statm");
        if (content == null) {
            return 0L;
        }
        String[] f = content.trim().split("\\s+");
        return (f.length > 1) ? parseLong(f[1], 0L) : 0L;
    }

    /** The full command line from {@code /proc/<pid>/cmdline} (NUL-separated),
     *  or null for kernel threads / vanished processes. */
    public static String cmdline(long pid) {
        try {
            byte[] bytes = Files.readAllBytes(Paths.get("/proc/" + pid + "/cmdline"));
            if (bytes.length == 0) {
                return null;
            }
            String s = new String(bytes, StandardCharsets.UTF_8);
            return s.replace('\0', ' ').trim();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Reads a {@code Key:\tvalue} field from {@code /proc/<pid>/status}. */
    public static String statusValue(long pid, String key) {
        for (String line : readLines("/proc/" + pid + "/status")) {
            if (line.startsWith(key + ":")) {
                return line.substring(key.length() + 1).trim();
            }
        }
        return null;
    }

    /** The numeric UID from {@code /proc/<pid>/status}, or -1. */
    public static long uid(long pid) {
        String v = statusValue(pid, "Uid");
        if (v == null) {
            return -1L;
        }
        String[] parts = v.split("\\s+");
        return (parts.length > 0) ? parseLong(parts[0], -1L) : -1L;
    }

    /** The number of threads from {@code /proc/<pid>/status}, or 0. */
    public static long threads(long pid) {
        String v = statusValue(pid, "Threads");
        return (v == null) ? 0L : parseLong(v, 0L);
    }

    /** The process working directory (best effort; may be unreadable for other
     *  users' processes). */
    public static String cwd(long pid) {
        try {
            Path link = Paths.get("/proc/" + pid + "/cwd");
            if (Files.exists(link)) {
                return Files.readSymbolicLink(link).toString();
            }
        } catch (IOException | RuntimeException e) {
            logger.log(Level.FINE, "cwd unreadable for pid " + pid, e);
        }
        return null;
    }

    /** Lists all numeric PIDs currently in {@code /proc}. */
    public static List<Long> pids() {
        java.util.List<Long> out = new java.util.ArrayList<>();
        try (var stream = Files.list(Paths.get("/proc"))) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (!name.isEmpty() && Character.isDigit(name.charAt(0))) {
                    try {
                        out.add(Long.parseLong(name));
                    } catch (NumberFormatException ignored) {
                        // not a pid directory
                    }
                }
            });
        } catch (IOException | RuntimeException e) {
            logger.log(Level.FINE, "Could not list /proc", e);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Small parsing helpers
    // ------------------------------------------------------------------

    public static long parseLong(String s, long fallback) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    public static double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }
}
