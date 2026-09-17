/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
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

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregate, read-only system information for the control center's
 * System/About panel and the CPU/memory widgets.
 *
 * <p>Everything is read from {@code /proc}, {@code /sys}, {@code /etc/os-release}
 * and {@link File} space queries - no privileges and no external commands
 * required (a couple of environment variables are consulted for the session
 * type). Values that cannot be determined come back as empty/zero rather than
 * throwing.</p>
 */
public final class SystemInfoService {

    /** Pseudo filesystems that should not be listed as disks. */
    private static final Set<String> SKIP_FS = Set.of(
            "proc", "sysfs", "devtmpfs", "devpts", "tmpfs", "cgroup", "cgroup2",
            "securityfs", "pstore", "debugfs", "tracefs", "fusectl", "mqueue",
            "hugetlbfs", "configfs", "binfmt_misc", "autofs", "efivarfs", "ramfs",
            "rpc_pipefs", "nsfs", "bpf", "squashfs");

    /** Mount-point prefixes that are never interesting to a user. */
    private static final String[] SKIP_MOUNT_PREFIX = {
            "/proc", "/sys", "/dev", "/run", "/var/lib/docker", "/snap"
    };

    private SystemInfoService() {
        // no instances
    }

    /** Memory figures in kilobytes. */
    public static final class Memory {
        private final long totalKb;
        private final long usedKb;
        private final long availableKb;
        private final long freeKb;
        private final long swapTotalKb;
        private final long swapUsedKb;

        Memory(long totalKb, long usedKb, long availableKb, long freeKb,
               long swapTotalKb, long swapUsedKb) {
            this.totalKb = totalKb;
            this.usedKb = usedKb;
            this.availableKb = availableKb;
            this.freeKb = freeKb;
            this.swapTotalKb = swapTotalKb;
            this.swapUsedKb = swapUsedKb;
        }

        public long getTotalKb() { return totalKb; }
        public long getUsedKb() { return usedKb; }
        public long getAvailableKb() { return availableKb; }
        public long getFreeKb() { return freeKb; }
        public long getSwapTotalKb() { return swapTotalKb; }
        public long getSwapUsedKb() { return swapUsedKb; }
        public double getUsedPercent() {
            return (totalKb > 0) ? (100.0 * usedKb / totalKb) : 0.0;
        }
        public long getTotalBytes() { return totalKb * 1024L; }
        public long getUsedBytes() { return usedKb * 1024L; }
    }

    /** CPU model and core counts. */
    public static final class Cpu {
        private final String model;
        private final int logicalCores;
        private final String vendor;

        Cpu(String model, int logicalCores, String vendor) {
            this.model = model;
            this.logicalCores = logicalCores;
            this.vendor = vendor;
        }

        public String getModel() { return model; }
        public int getLogicalCores() { return logicalCores; }
        public String getVendor() { return vendor; }
    }

    /** One mounted filesystem and its capacity. */
    public static final class Disk {
        private final String device;
        private final String mountPoint;
        private final String fsType;
        private final long totalBytes;
        private final long usableBytes;

        Disk(String device, String mountPoint, String fsType, long totalBytes, long usableBytes) {
            this.device = device;
            this.mountPoint = mountPoint;
            this.fsType = fsType;
            this.totalBytes = totalBytes;
            this.usableBytes = usableBytes;
        }

        public String getDevice() { return device; }
        public String getMountPoint() { return mountPoint; }
        public String getFsType() { return fsType; }
        public long getTotalBytes() { return totalBytes; }
        public long getUsableBytes() { return usableBytes; }
        public double getUsedPercent() {
            return (totalBytes > 0) ? (100.0 * (totalBytes - usableBytes) / totalBytes) : 0.0;
        }
    }

    // ------------------------------------------------------------------

    public static Memory memory() {
        Proc.MemInfo m = Proc.memInfo();
        long avail = (m.memAvailableKb >= 0) ? m.memAvailableKb : m.memFreeKb;
        return new Memory(m.memTotalKb, m.usedKb(), avail, m.memFreeKb,
                m.swapTotalKb, Math.max(0, m.swapTotalKb - m.swapFreeKb));
    }

    public static Cpu cpu() {
        String model = cpuInfoField("model name");
        if (model == null) {
            model = firstNonNull(cpuInfoField("Model"), cpuInfoField("Hardware"),
                    cpuInfoField("Processor"), "Unknown CPU");
        }
        String vendor = firstNonNull(cpuInfoField("vendor_id"), "Unknown");
        int cores = Runtime.getRuntime().availableProcessors();
        return new Cpu(model.trim(), cores, vendor.trim());
    }

    public static List<Disk> disks() {
        // Preserve /proc/mounts order, dedup by mount point.
        Map<String, Disk> byMount = new LinkedHashMap<>();
        for (String line : Proc.readLines("/proc/mounts")) {
            String[] f = line.split("\\s+");
            if (f.length < 3) {
                continue;
            }
            String device = f[0];
            String mount = unescapeMount(f[1]);
            String fsType = f[2];
            if (SKIP_FS.contains(fsType)) {
                continue;
            }
            if (device.startsWith("/dev/loop")) {
                continue;
            }
            boolean skip = false;
            for (String prefix : SKIP_MOUNT_PREFIX) {
                if (mount.equals(prefix) || mount.startsWith(prefix + "/")) {
                    skip = true;
                    break;
                }
            }
            if (skip || byMount.containsKey(mount)) {
                continue;
            }
            File dir = new File(mount);
            if (!dir.isDirectory()) {
                continue;
            }
            long total = dir.getTotalSpace();
            if (total <= 0) {
                continue;
            }
            byMount.put(mount, new Disk(device, mount, fsType, total, dir.getUsableSpace()));
        }
        return new ArrayList<>(byMount.values());
    }

    public static String kernelVersion() {
        String r = Proc.read("/proc/sys/kernel/osrelease");
        return (r != null && !r.isBlank()) ? r.trim() : System.getProperty("os.version", "");
    }

    public static String distro() {
        for (String line : Proc.readLines("/etc/os-release")) {
            if (line.startsWith("PRETTY_NAME=")) {
                return stripQuotes(line.substring("PRETTY_NAME=".length()).trim());
            }
        }
        return "Linux";
    }

    public static String hostname() {
        String h = Proc.read("/proc/sys/kernel/hostname");
        if (h != null && !h.isBlank()) {
            return h.trim();
        }
        String env = System.getenv("HOSTNAME");
        return (env != null && !env.isBlank()) ? env : "localhost";
    }

    public static String architecture() {
        return System.getProperty("os.arch", "");
    }

    public static double uptimeSeconds() {
        return Proc.uptimeSeconds();
    }

    public static double[] loadAverage() {
        return Proc.loadAverage();
    }

    /** The logged-in user's name. */
    public static String currentUser() {
        String u = System.getProperty("user.name");
        if (u == null || u.isBlank()) {
            u = System.getenv("USER");
        }
        return (u != null) ? u : "unknown";
    }

    /** The graphical session type: "x11", "wayland", or "" if unknown. Useful
     *  for warning that display/user mutations need a bare Xorg session. */
    public static String sessionType() {
        String s = System.getenv("XDG_SESSION_TYPE");
        return (s != null) ? s : "";
    }

    /** The desktop environment, e.g. "GNOME", "KDE", or "" if unknown. */
    public static String currentDesktop() {
        String s = System.getenv("XDG_CURRENT_DESKTOP");
        if (s == null || s.isBlank()) {
            s = System.getenv("DESKTOP_SESSION");
        }
        return (s != null) ? s : "";
    }

    /** True when running under a Wayland compositor (possibly Xwayland), where
     *  xrandr-based display changes and window-manager claims do not apply. */
    public static boolean isWayland() {
        return "wayland".equalsIgnoreCase(sessionType());
    }

    // ------------------------------------------------------------------

    private static String cpuInfoField(String key) {
        for (String line : Proc.readLines("/proc/cpuinfo")) {
            if (line.startsWith(key)) {
                int colon = line.indexOf(':');
                if (colon >= 0) {
                    return line.substring(colon + 1).trim();
                }
            }
        }
        return null;
    }

    private static String unescapeMount(String mount) {
        // /proc/mounts octal-escapes spaces (\040), tabs (\011), etc.
        return mount.replace("\\040", " ").replace("\\011", "\t")
                .replace("\\012", "\n").replace("\\134", "\\");
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
