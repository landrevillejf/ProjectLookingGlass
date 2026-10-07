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
package org.jdesktop.lg3d.utils.system;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only block-device discovery for the LFS/BLFS storage manager, normative
 * in {@code system-management-contract.md} §4.5. It builds the {@code lsblk}
 * argument vector and parses its {@code -P} key/value output; it never
 * re-implements device discovery and never mutates anything (LUKS mutations live
 * in {@link LuksService}).
 *
 * <p>The command-building and parsing are pure and unit-testable
 * ({@link #buildListCommand()}, {@link #parseDevices(List)},
 * {@link #buildUuidCommand(String)}); {@link #listDevices()} is a thin wrapper
 * over {@link ProcessRunner}. State files ({@code /etc/crypttab},
 * {@code /etc/fstab}) are read-only for the GUI (§3.5); writes go through
 * {@code cryptsetup}/the installer helper, never direct edits (§6).</p>
 */
public final class StorageService {

    private StorageService() {
        // no instances
    }

    /** The {@code lsblk -P} columns the storage view needs (§4.5). */
    static final String LSBLK_COLUMNS = "NAME,SIZE,TYPE,FSTYPE,MOUNTPOINT";

    /** An {@code lsblk -P} {@code KEY="value"} pair. */
    private static final Pattern KV = Pattern.compile("([A-Z]+)=\"([^\"]*)\"");

    /** True if {@code lsblk} is present on this host. */
    public static boolean isAvailable() {
        return ProcessRunner.isAvailable("lsblk");
    }

    /**
     * Builds the read-only list vector: {@code lsblk -P -o <columns>}. The
     * {@code -P} key/value form is stable to parse and needs no {@code --no-color}
     * (lsblk does not colourise a pipe).
     */
    public static List<String> buildListCommand() {
        return Arrays.asList("lsblk", "-P", "-o", LSBLK_COLUMNS);
    }

    /**
     * Builds the read-only UUID probe vector for a device:
     * {@code blkid -s UUID -o value <device>}. Returns an empty list for a
     * blank device.
     */
    public static List<String> buildUuidCommand(String device) {
        if (device == null || device.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList("blkid", "-s", "UUID", "-o", "value", device.trim());
    }

    /**
     * Parses {@code lsblk -P} output lines into {@link BlockDevice} values. Each
     * line is a set of {@code KEY="value"} pairs; blank lines are dropped and
     * parsing never throws.
     */
    public static List<BlockDevice> parseDevices(List<String> lines) {
        List<BlockDevice> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            Map<String, String> f = parseKv(line);
            String name = f.get("NAME");
            if (name == null || name.isEmpty()) {
                continue;
            }
            out.add(new BlockDevice(name,
                    f.getOrDefault("SIZE", ""),
                    f.getOrDefault("TYPE", ""),
                    f.getOrDefault("FSTYPE", ""),
                    f.getOrDefault("MOUNTPOINT", "")));
        }
        return out;
    }

    private static Map<String, String> parseKv(String line) {
        Map<String, String> f = new LinkedHashMap<>();
        Matcher m = KV.matcher(line);
        while (m.find()) {
            f.put(m.group(1), m.group(2));
        }
        return f;
    }

    /**
     * Lists the block devices, read-only. Returns an empty list when
     * {@code lsblk} is absent (a non-Linux/dev host) rather than throwing.
     */
    public static List<BlockDevice> listDevices() {
        if (!isAvailable()) {
            return Collections.emptyList();
        }
        ProcessRunner.Result r = ProcessRunner.run(buildListCommand());
        if (!r.isSuccess()) {
            return Collections.emptyList();
        }
        return parseDevices(r.getStdoutLines());
    }

    /**
     * Reads a device's UUID, read-only. Returns an empty string when
     * {@code blkid} is absent or the device has no UUID.
     */
    public static String readUuid(String device) {
        List<String> cmd = buildUuidCommand(device);
        if (cmd.isEmpty() || !ProcessRunner.isAvailable("blkid")) {
            return "";
        }
        ProcessRunner.Result r = ProcessRunner.run(cmd);
        if (!r.isSuccess()) {
            return "";
        }
        List<String> lines = r.getStdoutLines();
        return lines.isEmpty() ? "" : lines.get(0).trim();
    }

    /**
     * One block device / partition reported by {@code lsblk -P}. Values are the
     * raw strings from the tool; {@code size} is a human string (e.g. "1.8T")
     * because {@code lsblk} without {@code -b} prints it human-readable.
     */
    public static final class BlockDevice {
        private final String name;
        private final String size;
        private final String type;
        private final String fstype;
        private final String mountpoint;

        public BlockDevice(String name, String size, String type, String fstype, String mountpoint) {
            this.name = (name == null) ? "" : name;
            this.size = (size == null) ? "" : size;
            this.type = (type == null) ? "" : type;
            this.fstype = (fstype == null) ? "" : fstype;
            this.mountpoint = (mountpoint == null) ? "" : mountpoint;
        }

        public String getName() {
            return name;
        }

        public String getSize() {
            return size;
        }

        public String getType() {
            return type;
        }

        public String getFsType() {
            return fstype;
        }

        public String getMountpoint() {
            return mountpoint;
        }

        /** The {@code /dev} path for this device name. */
        public String getPath() {
            return name.startsWith("/dev/") ? name : ("/dev/" + name);
        }

        /** True if the filesystem type indicates a LUKS container. */
        public boolean isLuks() {
            return fstype.equalsIgnoreCase("crypto_LUKS");
        }

        /** A one-line label for a list cell. */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(name);
            if (!size.isEmpty()) {
                sb.append("  ").append(size);
            }
            if (!type.isEmpty()) {
                sb.append("  [").append(type).append(']');
            }
            if (!fstype.isEmpty()) {
                sb.append("  ").append(fstype);
            }
            if (!mountpoint.isEmpty()) {
                sb.append("  \u2192 ").append(mountpoint);
            }
            return sb.toString();
        }
    }
}
