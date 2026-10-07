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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whole-system update front-end for the LFS/BLFS target, normative in
 * {@code system-management-contract.md} §4.2. It drives the builder-installed
 * {@code /usr/bin/lfs-update} ({@code check|status|upgrade}) and never
 * re-implements the updater: {@code lfs-update upgrade} already backs up
 * {@code /etc}+{@code /boot}, runs {@code lpm update-db}/{@code lpm upgrade},
 * detects a kernel change and rebuilds the kernel + GRUB. This class only
 * builds argument vectors and parses/normalises the tool's output.
 *
 * <p>This is the <em>third</em> layer of the update model and MUST NOT be
 * conflated with the other two: {@code update-manager} auto-updates the lg3d
 * bundle itself (version.json, PGP-verified); {@code lpm} manages individual
 * packages ({@code lpm-console}); {@code lfs-update} updates the whole system.</p>
 *
 * <p>The command-building, ANSI-stripping and status/check parsing are pure and
 * unit-testable ({@link #buildCommand}, {@link #stripAnsi},
 * {@link #parseStatus}, {@link #interpretCheck}); the {@code run*} helpers are
 * thin wrappers over {@link ProcessRunner} (read-only {@code check}/{@code
 * status}) and {@link PrivilegedRunner} (the mutating, polkit-escalated {@code
 * upgrade}). {@code lfs-update} emits ANSI colour with no disable switch, so all
 * output is passed through {@link #stripAnsi} (§3.1).</p>
 */
public final class LfsUpdateService {

    private LfsUpdateService() {
        // no instances
    }

    /** The {@code lfs-update} sub-commands. */
    public enum Action {
        /** {@code lfs-update check} - read-only; exit 1 means "up to date". */
        CHECK,
        /** {@code lfs-update status} - read-only version / package counts. */
        STATUS,
        /** {@code lfs-update upgrade} - mutating, escalated, confirmed. */
        UPGRADE
    }

    /** The verdict of a {@code lfs-update check}, derived from its exit code. */
    public enum CheckVerdict {
        /** No updates pending (check exits 1, §3.2). */
        UP_TO_DATE,
        /** Updates are available (check exits 0). */
        UPDATES_AVAILABLE,
        /** The check itself failed (any other exit code). */
        ERROR
    }

    /** CSI/OSC escape-sequence matcher used by {@link #stripAnsi}. */
    private static final Pattern ANSI =
            Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)");

    /** {@code KEY=value} status line. */
    private static final Pattern KV_EQUALS = Pattern.compile("^([A-Za-z0-9_.-]+)\\s*=\\s*(.*)$");

    /** {@code key: value} status line. */
    private static final Pattern KV_COLON = Pattern.compile("^([A-Za-z0-9_ .-]+?):\\s+(.*)$");

    /** A leading integer in a value ("12 packages" -> 12). */
    private static final Pattern LEADING_INT = Pattern.compile("^(-?\\d+)");

    /** True if {@code /usr/bin/lfs-update} is present on this host. */
    public static boolean isAvailable() {
        return ProcessRunner.isAvailable("lfs-update");
    }

    /** True if {@code action} changes system state and therefore needs polkit. */
    public static boolean isMutating(Action action) {
        return action == Action.UPGRADE;
    }

    /**
     * Builds the argument vector for {@code action}: {@code lfs-update <sub>}.
     * Returns an empty list for {@code null}. The result is meant to be handed
     * straight to {@link ProcessRunner} / {@link PrivilegedRunner}.
     */
    public static List<String> buildCommand(Action action) {
        if (action == null) {
            return Collections.emptyList();
        }
        List<String> c = new ArrayList<>(2);
        c.add("lfs-update");
        switch (action) {
            case CHECK:
                c.add("check");
                break;
            case STATUS:
                c.add("status");
                break;
            case UPGRADE:
                c.add("upgrade");
                break;
            default:
                return Collections.emptyList();
        }
        return c;
    }

    /**
     * Strips ANSI escape sequences (colour, cursor moves, OSC titles) from
     * {@code text}. {@code lfs-update} colourises with no disable switch, so the
     * contract (§3.1, §6) requires stripping rather than parsing the escapes.
     */
    public static String stripAnsi(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return ANSI.matcher(text).replaceAll("");
    }

    /**
     * Interprets a {@code lfs-update check} exit code as a verdict: 1 = up to
     * date (§3.2 uses the exit code as state), 0 = updates available, anything
     * else = the check errored.
     */
    public static CheckVerdict interpretCheck(int exitCode) {
        if (exitCode == 1) {
            return CheckVerdict.UP_TO_DATE;
        }
        if (exitCode == 0) {
            return CheckVerdict.UPDATES_AVAILABLE;
        }
        return CheckVerdict.ERROR;
    }

    /**
     * Parses {@code lfs-update status} output into a {@link StatusSnapshot}.
     * ANSI is stripped first, then both {@code KEY=value} and {@code key: value}
     * lines are captured into a case-insensitive field map; unrecognised lines
     * are ignored. Pure and lenient: an unknown format yields an empty snapshot
     * rather than throwing.
     */
    public static StatusSnapshot parseStatus(String rawOutput) {
        Map<String, String> fields = new LinkedHashMap<>();
        String clean = stripAnsi(rawOutput);
        if (!clean.isEmpty()) {
            for (String line : clean.split("\\r?\\n")) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                Matcher eq = KV_EQUALS.matcher(t);
                if (eq.matches()) {
                    fields.put(eq.group(1).toLowerCase(java.util.Locale.ROOT), eq.group(2).trim());
                    continue;
                }
                Matcher co = KV_COLON.matcher(t);
                if (co.matches()) {
                    fields.put(co.group(1).trim().toLowerCase(java.util.Locale.ROOT)
                            .replace(' ', '_'), co.group(2).trim());
                }
            }
        }
        return new StatusSnapshot(fields, clean);
    }

    /**
     * Best-effort read of {@code /etc/lfs-version} (read-only, §3.5). Returns an
     * empty string when the file is absent (as on a non-LFS dev host).
     */
    public static String readLfsVersion() {
        return readFirstLine("/etc/lfs-version");
    }

    private static String readFirstLine(String path) {
        try {
            Path p = Path.of(path);
            if (!Files.isReadable(p)) {
                return "";
            }
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (String l : lines) {
                String t = l.trim();
                if (!t.isEmpty()) {
                    return t;
                }
            }
        } catch (IOException | RuntimeException e) {
            // Degrade to "unknown" rather than propagate; the panel shows "".
        }
        return "";
    }

    // ------------------------------------------------------------------
    // Thin execution helpers.

    /**
     * Runs a read-only action ({@code check} or {@code status}) unprivileged
     * through {@link ProcessRunner}. Returns a not-started result for the
     * mutating {@code upgrade} action or an unsupported/null action.
     */
    public static ProcessRunner.Result runRead(Action action) {
        if (isMutating(action)) {
            return new ProcessRunner.Result(false, -1, "", "upgrade is mutating; use runUpgrade");
        }
        List<String> cmd = buildCommand(action);
        if (cmd.isEmpty()) {
            return new ProcessRunner.Result(false, -1, "", "unsupported action");
        }
        return ProcessRunner.run(cmd);
    }

    /**
     * Runs {@code lfs-update upgrade} with polkit escalation through
     * {@link PrivilegedRunner}. The caller is responsible for confirming with the
     * user, running this off the EDT and streaming/progress-reporting (§3.6).
     * Returns an ERROR result for a non-mutating action.
     */
    public static PrivilegedRunner.PrivilegedResult runUpgrade() {
        List<String> cmd = buildCommand(Action.UPGRADE);
        return PrivilegedRunner.run(cmd);
    }

    /**
     * The parsed result of {@code lfs-update status}: a case-insensitive field
     * map plus the ANSI-stripped raw text. Convenience accessors pull the
     * version and the installed/upgradable package counts when present.
     */
    public static final class StatusSnapshot {
        private final Map<String, String> fields;
        private final String raw;

        StatusSnapshot(Map<String, String> fields, String raw) {
            this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            this.raw = (raw == null) ? "" : raw;
        }

        /** The parsed fields, keyed lowercase (e.g. {@code lfs_version}). */
        public Map<String, String> fields() {
            return fields;
        }

        /** The ANSI-stripped raw status text, for verbatim display. */
        public String raw() {
            return raw;
        }

        public boolean isEmpty() {
            return fields.isEmpty();
        }

        /** A field value by (case-insensitive) key, or null when absent. */
        public String get(String key) {
            return (key == null) ? null : fields.get(key.toLowerCase(java.util.Locale.ROOT));
        }

        /** The first present of the given keys, or null. */
        private String first(String... keys) {
            for (String k : keys) {
                String v = fields.get(k);
                if (v != null && !v.isEmpty()) {
                    return v;
                }
            }
            return null;
        }

        /** The reported system version, or null when the status omits it. */
        public String version() {
            return first("lfs_version", "version", "system_version", "system");
        }

        /** The installed-package count, or null when not reported/parseable. */
        public Integer installed() {
            return leadingInt(first("installed", "installed_packages", "packages_installed"));
        }

        /** The upgradable-package count, or null when not reported/parseable. */
        public Integer upgradable() {
            return leadingInt(first("upgradable", "upgradable_packages", "upgrades",
                    "updates", "available", "pending"));
        }

        private static Integer leadingInt(String value) {
            if (value == null) {
                return null;
            }
            Matcher m = LEADING_INT.matcher(value.trim());
            return m.find() ? Integer.valueOf(m.group(1)) : null;
        }
    }
}
