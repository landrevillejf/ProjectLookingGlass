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
package org.jdesktop.lg3d.apps.filemanager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jdesktop.lg3d.utils.system.PrivilegedRunner;
import org.jdesktop.lg3d.utils.system.ProcessRunner;
import org.jdesktop.lg3d.utils.system.ProcessService;

/**
 * Removable-volume operations for the file manager: enumerate mountable block
 * devices and mount / unmount them, shelling out to the standard Linux tools.
 *
 * <p>Detection reads {@code lsblk}. Mounting prefers {@code udisksctl} (polkit,
 * so the active local user needs no password for removable media) and falls back
 * to {@code pkexec mount} / {@code pkexec umount} through
 * {@link PrivilegedRunner}. Everything degrades gracefully: a missing tool or a
 * dismissed authorization prompt is reported through an {@link OpResult} rather
 * than thrown, so the UI can explain instead of failing.</p>
 *
 * <p>Deliberately free of Swing/AWT imports so the {@code lsblk} parsing and the
 * {@code udisksctl} output handling can be exercised headless.</p>
 */
public final class VolumeOperations {

    private static final Pattern KV = Pattern.compile("([A-Z]+)=\"([^\"]*)\"");
    private static final Pattern MOUNTED_AT =
            Pattern.compile("(?i)\\bat\\s+(/\\S+)");

    private static final String LSBLK_COLS =
            "NAME,PATH,TYPE,SIZE,RM,MOUNTPOINT,MOUNTPOINTS,LABEL,FSTYPE";

    private VolumeOperations() {
        // no instances
    }

    /** A mountable block device / partition discovered by {@code lsblk}. */
    public static final class Volume {
        public final String name;        // e.g. sdb1
        public final String path;        // e.g. /dev/sdb1
        public final String type;        // part, disk, crypt, rom
        public final String fsType;      // ext4, vfat, iso9660, ...
        public final String label;       // volume label, may be empty
        public final String mountPoint;  // current mount point, "" if unmounted
        public final long sizeBytes;
        public final boolean removable;

        Volume(String name, String path, String type, String fsType, String label,
               String mountPoint, long sizeBytes, boolean removable) {
            this.name = name;
            this.path = path;
            this.type = type;
            this.fsType = fsType;
            this.label = (label == null) ? "" : label;
            this.mountPoint = (mountPoint == null) ? "" : mountPoint;
            this.sizeBytes = sizeBytes;
            this.removable = removable;
        }

        /** True when the volume is currently mounted. */
        public boolean isMounted() {
            return !mountPoint.isEmpty();
        }

        /** A one-line human description for the volumes picker. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(path).append("  ");
            String id = label.isEmpty() ? (type + "/" + fsType) : label;
            sb.append(id);
            if (sizeBytes > 0) {
                sb.append("  (").append(ProcessService.formatBytes(sizeBytes)).append(')');
            }
            sb.append(isMounted() ? "  \u2014 mounted at " + mountPoint : "  \u2014 not mounted");
            return sb.toString();
        }

        @Override
        public String toString() {
            return describe();
        }
    }

    /** The outcome of a mount / unmount attempt. */
    public static final class OpResult {
        private final boolean success;
        private final String message;

        OpResult(boolean success, String message) {
            this.success = success;
            this.message = (message == null) ? "" : message;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }

    /**
     * Enumerates mountable volumes (those carrying a real filesystem) by
     * parsing {@code lsblk}. Returns an empty list when lsblk is unavailable.
     */
    public static List<Volume> listVolumes() {
        if (!ProcessRunner.isAvailable("lsblk")) {
            return new ArrayList<>();
        }
        ProcessRunner.Result r = ProcessRunner.run(
                List.of("lsblk", "-b", "-P", "-o", LSBLK_COLS), 15, TimeUnit.SECONDS, null);
        if (!r.isStarted()) {
            return new ArrayList<>();
        }
        return parseLsblk(r.getStdoutLines());
    }

    /**
     * Parses {@code lsblk -P} key/value lines into {@link Volume}s, keeping only
     * entries that carry a mountable filesystem (a non-empty {@code FSTYPE} that
     * is not {@code swap}). Package-private for headless testing.
     */
    static List<Volume> parseLsblk(List<String> lines) {
        List<Volume> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (String line : lines) {
            Map<String, String> f = parseKv(line);
            String name = f.getOrDefault("NAME", "");
            if (name.isEmpty()) {
                continue;
            }
            String fs = lower(f.get("FSTYPE"));
            if (fs.isEmpty() || fs.equals("swap") || fs.equals("linux_raid_member")
                    || fs.equals("lvm2_member") || fs.equals("crypto_luks")) {
                // Not a directly mountable filesystem (bare container / raid / swap).
                // crypto_LUKS is offered by lsblk once unlocked as a mapper child.
                if (!fs.equals("crypto_luks")) {
                    continue;
                }
            }
            String path = f.getOrDefault("PATH", "/dev/" + name);
            String mp = firstNonEmpty(f.get("MOUNTPOINT"), f.get("MOUNTPOINTS"));
            long size = parseLong(f.get("SIZE"));
            boolean rm = "1".equals(f.get("RM"));
            out.add(new Volume(name, path, f.getOrDefault("TYPE", ""), fs,
                    f.getOrDefault("LABEL", ""), mp, size, rm));
        }
        return out;
    }

    /**
     * Mounts a device. Prefers {@code udisksctl mount -b <dev>}; falls back to
     * {@code pkexec mount <dev> <target>} under {@code /media}.
     *
     * @param devicePath the block device, e.g. {@code /dev/sdb1}
     * @param label      volume label used to name the fallback mount point
     */
    public static OpResult mount(String devicePath, String label) {
        if (devicePath == null || devicePath.isBlank()) {
            return new OpResult(false, "No device selected.");
        }
        if (ProcessRunner.isAvailable("udisksctl")) {
            ProcessRunner.Result r = ProcessRunner.run(
                    List.of("udisksctl", "mount", "-b", devicePath), 60, TimeUnit.SECONDS, null);
            if (r.isSuccess()) {
                String at = parseMountedAt(r.getStdout());
                return new OpResult(true, at.isEmpty()
                        ? ("Mounted " + devicePath) : ("Mounted " + devicePath + " at " + at));
            }
            // udisksctl present but failed: fall through to the pkexec path only
            // if it looks like a missing-authorization / unknown-device error.
            if (!r.isStarted()) {
                return new OpResult(false, r.getMessage());
            }
        }
        // Fallback: pkexec mount into /media/<label-or-name>.
        String target = "/media/" + mountName(devicePath, label);
        PrivilegedRunner.PrivilegedResult mkdir =
                PrivilegedRunner.run("mkdir", "-p", target);
        if (mkdir.getStatus() == PrivilegedRunner.Status.CANCELLED
                || mkdir.getStatus() == PrivilegedRunner.Status.UNAVAILABLE) {
            return new OpResult(false, mkdir.getMessage());
        }
        PrivilegedRunner.PrivilegedResult m = PrivilegedRunner.run("mount", devicePath, target);
        switch (m.getStatus()) {
            case SUCCESS:
                return new OpResult(true, "Mounted " + devicePath + " at " + target);
            case CANCELLED:
            case UNAVAILABLE:
                return new OpResult(false, m.getMessage());
            default:
                return new OpResult(false, m.getMessage().isEmpty()
                        ? ("mount failed (exit " + m.getExitCode() + ")") : m.getMessage());
        }
    }

    /**
     * Unmounts a volume. Prefers {@code udisksctl unmount -b <dev>}; falls back
     * to {@code pkexec umount <mountpoint-or-device>}.
     */
    public static OpResult unmount(String devicePath, String mountPoint) {
        if ((devicePath == null || devicePath.isBlank())
                && (mountPoint == null || mountPoint.isBlank())) {
            return new OpResult(false, "No volume selected.");
        }
        if (devicePath != null && !devicePath.isBlank()
                && ProcessRunner.isAvailable("udisksctl")) {
            ProcessRunner.Result r = ProcessRunner.run(
                    List.of("udisksctl", "unmount", "-b", devicePath), 60, TimeUnit.SECONDS, null);
            if (r.isSuccess()) {
                return new OpResult(true, "Unmounted " + devicePath);
            }
            if (!r.isStarted()) {
                return new OpResult(false, r.getMessage());
            }
        }
        String target = (mountPoint != null && !mountPoint.isBlank()) ? mountPoint : devicePath;
        PrivilegedRunner.PrivilegedResult u = PrivilegedRunner.run("umount", target);
        switch (u.getStatus()) {
            case SUCCESS:
                return new OpResult(true, "Unmounted " + target);
            case CANCELLED:
            case UNAVAILABLE:
                return new OpResult(false, u.getMessage());
            default:
                return new OpResult(false, u.getMessage().isEmpty()
                        ? ("umount failed (exit " + u.getExitCode() + ")") : u.getMessage());
        }
    }

    /** True when polkit elevation (pkexec) is available for the fallback path. */
    public static boolean canElevate() {
        return PrivilegedRunner.isAvailable();
    }

    // ------------------------------------------------------------------

    /** Extracts the mount point from {@code udisksctl}'s "Mounted X at Y." line. */
    static String parseMountedAt(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        Matcher m = MOUNTED_AT.matcher(output);
        if (m.find()) {
            String at = m.group(1);
            // Strip a trailing period from the sentence.
            if (at.endsWith(".")) {
                at = at.substring(0, at.length() - 1);
            }
            return at;
        }
        return "";
    }

    private static Map<String, String> parseKv(String line) {
        Map<String, String> map = new LinkedHashMap<>();
        if (line == null) {
            return map;
        }
        Matcher m = KV.matcher(line);
        while (m.find()) {
            map.put(m.group(1), m.group(2));
        }
        return map;
    }

    private static String mountName(String devicePath, String label) {
        if (label != null && !label.isBlank()) {
            return label.replaceAll("[^A-Za-z0-9._-]", "_");
        }
        String n = devicePath;
        int slash = n.lastIndexOf('/');
        if (slash >= 0 && slash < n.length() - 1) {
            n = n.substring(slash + 1);
        }
        return n;
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) {
            // lsblk MOUNTPOINTS can list several lines; take the first.
            int nl = a.indexOf('\n');
            return (nl > 0) ? a.substring(0, nl) : a;
        }
        if (b != null && !b.isEmpty()) {
            int nl = b.indexOf('\n');
            return (nl > 0) ? b.substring(0, nl) : b;
        }
        return "";
    }

    private static String lower(String s) {
        return (s == null) ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
