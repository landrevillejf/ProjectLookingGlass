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
import java.util.Locale;
import java.util.Map;

/**
 * LUKS front-end for the LFS/BLFS storage manager, normative in
 * {@code system-management-contract.md} §4.5. It builds the exact
 * {@code cryptsetup} / {@code lfs-encrypt-disk} argument vectors and parses
 * {@code cryptsetup status}; it never re-implements encryption logic and never
 * writes {@code /etc/crypttab} directly (mutations go through the owning CLI,
 * §6).
 *
 * <p>Operation classes drive the UI's confirmation level:
 * <ul>
 *   <li>{@code status} is read-only and runs unprivileged via
 *       {@link ProcessRunner};</li>
 *   <li>{@code luksOpen}/{@code luksClose}/{@code luksAddKey} are
 *       <em>mutating</em> - confirmed, then escalated via
 *       {@link PrivilegedRunner} (polkit);</li>
 *   <li>{@code luksFormat} and {@code lfs-encrypt-disk} are <em>destructive</em>
 *       (they erase all data) - the UI MUST require a typed confirmation in
 *       addition to the polkit prompt.</li>
 * </ul>
 * Passphrases are fed through {@link PrivilegedRunner#run(List, String) stdin}
 * only, never as command-line arguments (§6).</p>
 *
 * <p>The command-building and status parsing are pure and unit-testable; the
 * {@code run*} helpers are thin wrappers over {@link ProcessRunner} /
 * {@link PrivilegedRunner}.</p>
 */
public final class LuksService {

    /** The installer helper the builder provides for full-disk encryption. */
    static final String ENCRYPT_DISK_HELPER = "/usr/sbin/lfs-encrypt-disk";

    private LuksService() {
        // no instances
    }

    /** A LUKS operation, carrying its confirmation class. */
    public enum Operation {
        /** {@code cryptsetup status <name>} - read-only. */
        STATUS(false, false),
        /** {@code cryptsetup luksOpen <device> <name>} - mutating. */
        OPEN(true, false),
        /** {@code cryptsetup luksClose <name>} - mutating. */
        CLOSE(true, false),
        /** {@code cryptsetup luksAddKey <device>} - mutating. */
        ADD_KEY(true, false),
        /** {@code cryptsetup luksFormat --type luks2 <device>} - destructive. */
        FORMAT(true, true),
        /** {@code /usr/sbin/lfs-encrypt-disk <device> [name]} - destructive. */
        ENCRYPT_DISK(true, true);

        private final boolean mutating;
        private final boolean destructive;

        Operation(boolean mutating, boolean destructive) {
            this.mutating = mutating;
            this.destructive = destructive;
        }

        /** True if the operation changes state and needs polkit escalation. */
        public boolean isMutating() {
            return mutating;
        }

        /** True if the operation erases data and needs a typed confirmation. */
        public boolean isDestructive() {
            return destructive;
        }
    }

    /** True if {@code cryptsetup} is present on this host. */
    public static boolean isAvailable() {
        return ProcessRunner.isAvailable("cryptsetup");
    }

    /** True if the destructive installer helper {@code lfs-encrypt-disk} exists. */
    public static boolean isEncryptDiskHelperAvailable() {
        return ProcessRunner.isAvailable(ENCRYPT_DISK_HELPER);
    }

    /**
     * Builds the argument vector for {@code op}. Returns an empty list when the
     * required arguments are blank or the helper for {@link Operation#ENCRYPT_DISK}
     * is {@code null}. The vectors are exactly those in §4.5 - no extra flags are
     * invented.
     *
     * @param op     the operation
     * @param device the block device (for OPEN/FORMAT/ADD_KEY/ENCRYPT_DISK)
     * @param name   the LUKS/mapper name (for STATUS/OPEN/CLOSE, optional for
     *               ENCRYPT_DISK)
     */
    public static List<String> buildCommand(Operation op, String device, String name) {
        if (op == null) {
            return Collections.emptyList();
        }
        String dev = trimToNull(device);
        String nm = trimToNull(name);
        List<String> c = new ArrayList<>();
        switch (op) {
            case STATUS:
                if (nm == null) {
                    return Collections.emptyList();
                }
                c.addAll(Arrays.asList("cryptsetup", "status", nm));
                return c;
            case OPEN:
                if (dev == null || nm == null) {
                    return Collections.emptyList();
                }
                c.addAll(Arrays.asList("cryptsetup", "luksOpen", dev, nm));
                return c;
            case CLOSE:
                if (nm == null) {
                    return Collections.emptyList();
                }
                c.addAll(Arrays.asList("cryptsetup", "luksClose", nm));
                return c;
            case ADD_KEY:
                if (dev == null) {
                    return Collections.emptyList();
                }
                c.addAll(Arrays.asList("cryptsetup", "luksAddKey", dev));
                return c;
            case FORMAT:
                if (dev == null) {
                    return Collections.emptyList();
                }
                c.addAll(Arrays.asList("cryptsetup", "luksFormat", "--type", "luks2", dev));
                return c;
            case ENCRYPT_DISK:
                if (dev == null) {
                    return Collections.emptyList();
                }
                c.add(ENCRYPT_DISK_HELPER);
                c.add(dev);
                if (nm != null) {
                    c.add(nm);
                }
                return c;
            default:
                return Collections.emptyList();
        }
    }

    /**
     * Parses {@code cryptsetup status} output into a case-insensitive field map.
     * The tool prints tab/space-indented {@code key: value} lines (e.g.
     * {@code cipher: aes-xts-plain64}); unrecognised lines are ignored. Pure and
     * lenient.
     */
    public static Map<String, String> parseStatus(String rawOutput) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (rawOutput == null || rawOutput.isEmpty()) {
            return fields;
        }
        for (String line : rawOutput.split("\\r?\\n")) {
            String t = line.trim();
            int colon = t.indexOf(':');
            if (colon <= 0 || colon == t.length() - 1) {
                continue;
            }
            String key = t.substring(0, colon).trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            String value = t.substring(colon + 1).trim();
            if (!key.isEmpty()) {
                fields.put(key, value);
            }
        }
        return fields;
    }

    // ------------------------------------------------------------------
    // Thin execution helpers.

    /**
     * Runs a read-only {@code cryptsetup status <name>} unprivileged. Returns a
     * not-started result for a blank name.
     */
    public static ProcessRunner.Result runStatus(String name) {
        List<String> cmd = buildCommand(Operation.STATUS, null, name);
        if (cmd.isEmpty()) {
            return new ProcessRunner.Result(false, -1, "", "a LUKS/mapper name is required");
        }
        return ProcessRunner.run(cmd);
    }

    /**
     * Runs a mutating/destructive operation with polkit escalation, feeding
     * {@code passphrase} to the command's stdin (never as an argument). The
     * caller MUST have confirmed first (and, for a destructive op, obtained a
     * typed confirmation). Returns an ERROR result for an invalid vector or a
     * read-only operation.
     */
    public static PrivilegedRunner.PrivilegedResult runMutating(
            Operation op, String device, String name, String passphrase) {
        if (op == null || !op.isMutating()) {
            return new PrivilegedRunner.PrivilegedResult(
                    PrivilegedRunner.Status.ERROR, "not a mutating operation", -1, "");
        }
        List<String> cmd = buildCommand(op, device, name);
        if (cmd.isEmpty()) {
            return new PrivilegedRunner.PrivilegedResult(
                    PrivilegedRunner.Status.ERROR, "invalid arguments for " + op, -1, "");
        }
        return PrivilegedRunner.run(cmd, passphrase);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
