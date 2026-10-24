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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Host-intrusion-detection front-end for the LFS/BLFS security manager,
 * normative in {@code system-management-contract.md} §4.10 and inventoried in
 * {@code lg3d-agent-package-inventory.md} §6.11 (runtime HIDS). It detects which
 * of the shipped HIDS tools are installed - {@code fail2ban} (intrusion
 * prevention), {@code rkhunter} (rootkit scan), {@code lynis} (security audit)
 * and {@code auditd} (kernel audit trail) - builds the exact CLI argument
 * vectors and parses their read-only output. It never re-implements a scanner,
 * a jail engine or the audit subsystem, and never writes {@code /etc} (§6):
 * every mutation goes through the owning tool under polkit.
 *
 * <p>Operation classes drive the UI's confirmation level:
 * <ul>
 *   <li>{@link Operation#FAIL2BAN_STATUS}, {@link Operation#FAIL2BAN_JAIL_STATUS},
 *       {@link Operation#AUDITCTL_STATUS} and {@link Operation#AUSEARCH} are
 *       <em>read-only</em> and run unprivileged via {@link ProcessRunner};</li>
 *   <li>{@link Operation#FAIL2BAN_BAN}, {@link Operation#FAIL2BAN_UNBAN},
 *       {@link Operation#RKHUNTER_CHECK}, {@link Operation#RKHUNTER_PROPUPD} and
 *       {@link Operation#LYNIS_AUDIT} are <em>mutating</em> or long-running -
 *       confirmed, then escalated via {@link PrivilegedRunner} (polkit), run off
 *       the EDT by the caller.</li>
 * </ul>
 *
 * <p>The detection, command-building and output parsing are pure and
 * unit-testable ({@link #installedTools(Probes)}, {@link #buildCommand},
 * {@code parse*}); the live {@link #probe()} and the {@code run*} helpers are
 * thin wrappers that degrade gracefully - an absent tool or a missing parameter
 * yields an empty vector / a not-started result rather than throwing.</p>
 */
public final class HidsService {

    /** The fail2ban control client. */
    public static final String FAIL2BAN_CLIENT = "fail2ban-client";
    /** The rkhunter rootkit scanner. */
    public static final String RKHUNTER = "rkhunter";
    /** The lynis audit scanner. */
    public static final String LYNIS = "lynis";
    /** The auditd control utility. */
    public static final String AUDITCTL = "auditctl";
    /** The auditd search utility. */
    public static final String AUSEARCH = "ausearch";
    /** The rkhunter log the GUI may show read-only. */
    public static final String RKHUNTER_LOG = "/var/log/rkhunter.log";

    private static final Pattern F2B_JAIL_COUNT =
            Pattern.compile("Number of jails:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern F2B_JAIL_LIST =
            Pattern.compile("Jail list:\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern F2B_JAIL_NAME =
            Pattern.compile("jail:\\s*(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern F2B_CURRENTLY_BANNED =
            Pattern.compile("Currently banned:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern F2B_TOTAL_BANNED =
            Pattern.compile("Total banned:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern F2B_BANNED_LIST =
            Pattern.compile("Banned IP list:\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern LYNIS_INDEX =
            Pattern.compile("Hardening index\\s*:?\\s*\\[?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LYNIS_SUGGESTIONS =
            Pattern.compile("Suggestions:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LYNIS_WARNINGS =
            Pattern.compile("Warnings:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WARNING_LINE =
            Pattern.compile("\\bwarning\\b", Pattern.CASE_INSENSITIVE);

    private HidsService() {
        // no instances
    }

    /** A §4.10 HIDS operation, carrying its confirmation class. */
    public enum Operation {
        /** {@code fail2ban-client status} - read-only. */
        FAIL2BAN_STATUS(false),
        /** {@code fail2ban-client status <jail>} - read-only. */
        FAIL2BAN_JAIL_STATUS(false),
        /** {@code fail2ban-client set <jail> banip <ip>} - mutating. */
        FAIL2BAN_BAN(true),
        /** {@code fail2ban-client set <jail> unbanip <ip>} - mutating. */
        FAIL2BAN_UNBAN(true),
        /** {@code rkhunter --check --sk --nocolors} - long, escalated. */
        RKHUNTER_CHECK(true),
        /** {@code rkhunter --propupd} - mutating (rebaselines file properties). */
        RKHUNTER_PROPUPD(true),
        /** {@code lynis audit system --no-colors --quick} - long, escalated. */
        LYNIS_AUDIT(true),
        /** {@code auditctl -s} - read-only. */
        AUDITCTL_STATUS(false),
        /** {@code ausearch -m <type> -ts recent} - read-only. */
        AUSEARCH(false);

        private final boolean mutating;

        Operation(boolean mutating) {
            this.mutating = mutating;
        }

        /** True if the operation changes state or is a long privileged scan. */
        public boolean isMutating() {
            return mutating;
        }
    }

    /** The HIDS tools the desktop knows how to surface. */
    public enum Tool {
        /** fail2ban (intrusion prevention). */
        FAIL2BAN,
        /** rkhunter (rootkit scanner). */
        RKHUNTER,
        /** lynis (audit scanner). */
        LYNIS,
        /** auditd (kernel audit trail). */
        AUDITD
    }

    /**
     * The PATH facts {@link #probe()} gathers and the pure predicates consume.
     * Splitting these out keeps detection unit-testable without the real HIDS
     * tools installed. {@code auditd} is present when either {@code auditctl} or
     * {@code ausearch} is on the PATH.
     */
    public static final class Probes {
        private final boolean fail2banClient;
        private final boolean rkhunter;
        private final boolean lynis;
        private final boolean auditctl;
        private final boolean ausearch;

        public Probes(boolean fail2banClient, boolean rkhunter, boolean lynis,
                      boolean auditctl, boolean ausearch) {
            this.fail2banClient = fail2banClient;
            this.rkhunter = rkhunter;
            this.lynis = lynis;
            this.auditctl = auditctl;
            this.ausearch = ausearch;
        }

        boolean hasFail2banClient() {
            return fail2banClient;
        }

        boolean hasRkhunter() {
            return rkhunter;
        }

        boolean hasLynis() {
            return lynis;
        }

        boolean hasAuditctl() {
            return auditctl;
        }

        boolean hasAusearch() {
            return ausearch;
        }
    }

    // ------------------------------------------------------------------
    // Detection (pure predicates over Probes + a live probe()).

    /**
     * Live detection against this host: probes {@code PATH} for each HIDS tool.
     * Never spawns a process; {@link ProcessRunner#isAvailable} only checks the
     * PATH and is cached.
     */
    public static Probes probe() {
        return new Probes(
                ProcessRunner.isAvailable(FAIL2BAN_CLIENT),
                ProcessRunner.isAvailable(RKHUNTER),
                ProcessRunner.isAvailable(LYNIS),
                ProcessRunner.isAvailable(AUDITCTL),
                ProcessRunner.isAvailable(AUSEARCH));
    }

    /** True if {@code tool} is installed on the probed host. Pure. */
    public static boolean isPresent(Probes p, Tool tool) {
        if (p == null || tool == null) {
            return false;
        }
        return switch (tool) {
            case FAIL2BAN -> p.hasFail2banClient();
            case RKHUNTER -> p.hasRkhunter();
            case LYNIS -> p.hasLynis();
            case AUDITD -> p.hasAuditctl() || p.hasAusearch();
        };
    }

    /** The installed tools in a stable display order. Pure; never null. */
    public static List<Tool> installedTools(Probes p) {
        List<Tool> out = new ArrayList<>();
        for (Tool t : Tool.values()) {
            if (isPresent(p, t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** A human label for a tool; never null. */
    public static String describeTool(Tool tool) {
        if (tool == null) {
            return "Unknown";
        }
        return switch (tool) {
            case FAIL2BAN -> "fail2ban (intrusion prevention)";
            case RKHUNTER -> "rkhunter (rootkit scan)";
            case LYNIS -> "lynis (security audit)";
            case AUDITD -> "auditd (kernel audit trail)";
        };
    }

    // ------------------------------------------------------------------
    // Command building (pure; no shell interpolation).

    /**
     * Builds the argument vector for {@code op}, taking its parameters from
     * {@code params} in order (jail, then ip / message-type as applicable).
     * Returns an empty list when the operation is unknown or a required
     * parameter is missing or malformed; the result never contains shell
     * metacharacters and is meant to be handed straight to
     * {@link ProcessRunner} / {@link PrivilegedRunner}.
     *
     * @param op     the operation
     * @param params operation parameters (see the individual builders)
     */
    public static List<String> buildCommand(Operation op, String... params) {
        if (op == null) {
            return Collections.emptyList();
        }
        String jail = at(params, 0);
        String second = at(params, 1);
        return switch (op) {
            case FAIL2BAN_STATUS -> fail2banStatusCommand();
            case FAIL2BAN_JAIL_STATUS -> fail2banJailStatusCommand(jail);
            case FAIL2BAN_BAN -> fail2banBanCommand(jail, second);
            case FAIL2BAN_UNBAN -> fail2banUnbanCommand(jail, second);
            case RKHUNTER_CHECK -> rkhunterCheckCommand();
            case RKHUNTER_PROPUPD -> rkhunterPropupdCommand();
            case LYNIS_AUDIT -> lynisAuditCommand();
            case AUDITCTL_STATUS -> auditctlStatusCommand();
            case AUSEARCH -> ausearchCommand(jail);
        };
    }

    /** {@code fail2ban-client status} - lists the jails. */
    public static List<String> fail2banStatusCommand() {
        return Arrays.asList(FAIL2BAN_CLIENT, "status");
    }

    /** {@code fail2ban-client status <jail>}; empty when {@code jail} is malformed. */
    public static List<String> fail2banJailStatusCommand(String jail) {
        if (!isToken(jail)) {
            return Collections.emptyList();
        }
        return Arrays.asList(FAIL2BAN_CLIENT, "status", jail);
    }

    /** {@code fail2ban-client set <jail> banip <ip>}; empty on a malformed argument. */
    public static List<String> fail2banBanCommand(String jail, String ip) {
        if (!isToken(jail) || !isToken(ip)) {
            return Collections.emptyList();
        }
        return Arrays.asList(FAIL2BAN_CLIENT, "set", jail, "banip", ip);
    }

    /** {@code fail2ban-client set <jail> unbanip <ip>}; empty on a malformed argument. */
    public static List<String> fail2banUnbanCommand(String jail, String ip) {
        if (!isToken(jail) || !isToken(ip)) {
            return Collections.emptyList();
        }
        return Arrays.asList(FAIL2BAN_CLIENT, "set", jail, "unbanip", ip);
    }

    /** {@code rkhunter --check --sk --nocolors} - a non-interactive rootkit scan. */
    public static List<String> rkhunterCheckCommand() {
        return Arrays.asList(RKHUNTER, "--check", "--sk", "--nocolors");
    }

    /** {@code rkhunter --propupd} - rebaselines the file-properties database. */
    public static List<String> rkhunterPropupdCommand() {
        return Arrays.asList(RKHUNTER, "--propupd");
    }

    /** {@code lynis audit system --no-colors --quick} - a non-interactive audit. */
    public static List<String> lynisAuditCommand() {
        return Arrays.asList(LYNIS, "audit", "system", "--no-colors", "--quick");
    }

    /** {@code auditctl -s} - the audit subsystem status. */
    public static List<String> auditctlStatusCommand() {
        return Arrays.asList(AUDITCTL, "-s");
    }

    /**
     * {@code ausearch -m <type> -ts recent} - recent events of a message type
     * (e.g. {@code USER_AUTH}, {@code avc}); empty when {@code messageType} is
     * malformed. Read-only.
     */
    public static List<String> ausearchCommand(String messageType) {
        if (!isToken(messageType)) {
            return Collections.emptyList();
        }
        return Arrays.asList(AUSEARCH, "-m", messageType, "-ts", "recent");
    }

    /**
     * A parameter is usable when it is non-blank, holds no whitespace (a single
     * argv element) and is not absurdly long. Because the result is an argument
     * vector, never a shell string, no metacharacter escaping is required.
     */
    static boolean isToken(String s) {
        if (s == null || s.isBlank() || s.length() > 64) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String at(String[] params, int i) {
        return (params != null && i < params.length) ? params[i] : null;
    }

    // ------------------------------------------------------------------
    // Pure output parsing (headless-testable; no process is spawned).

    /**
     * Parses {@code fail2ban-client status}: the running state, the jail count
     * and the jail names. Lenient - blank output yields a not-running status.
     */
    public static Fail2BanStatus parseFail2BanStatus(String output) {
        if (output == null || output.isBlank()) {
            return new Fail2BanStatus(false, Collections.emptyList());
        }
        List<String> jails = new ArrayList<>();
        Matcher list = F2B_JAIL_LIST.matcher(output);
        if (list.find()) {
            for (String name : list.group(1).split(",")) {
                String t = name.trim();
                if (!t.isEmpty()) {
                    jails.add(t);
                }
            }
        }
        int count = firstGroupAsInt(F2B_JAIL_COUNT, output);
        boolean running = output.toLowerCase(Locale.ROOT).contains("status")
                && (count > 0 || !jails.isEmpty()
                        || output.toLowerCase(Locale.ROOT).contains("number of jails"));
        return new Fail2BanStatus(running, jails);
    }

    /**
     * Parses {@code fail2ban-client status <jail>}: the jail name, the current
     * and total ban counts and the banned addresses. Lenient.
     */
    public static JailStatus parseFail2BanJail(String output) {
        if (output == null || output.isBlank()) {
            return new JailStatus("", 0, 0, Collections.emptyList());
        }
        String name = "";
        Matcher nm = F2B_JAIL_NAME.matcher(output);
        if (nm.find()) {
            name = nm.group(1).trim();
        }
        List<String> ips = new ArrayList<>();
        Matcher bl = F2B_BANNED_LIST.matcher(output);
        if (bl.find()) {
            for (String ip : bl.group(1).trim().split("\\s+")) {
                if (!ip.isEmpty()) {
                    ips.add(ip);
                }
            }
        }
        return new JailStatus(name,
                firstGroupAsInt(F2B_CURRENTLY_BANNED, output),
                firstGroupAsInt(F2B_TOTAL_BANNED, output),
                ips);
    }

    /**
     * Parses an {@code rkhunter --check} run by counting the {@code Warning:}
     * lines. The full output is shown verbatim by the UI; this is only the
     * one-line summary. Pure and lenient.
     */
    public static RkhunterResult parseRkhunter(String output) {
        int warnings = 0;
        boolean ran = false;
        if (output != null) {
            for (String line : output.split("\\r?\\n")) {
                if (WARNING_LINE.matcher(line).find()) {
                    warnings++;
                }
                if (line.contains("System checks summary") || line.contains("Checking")) {
                    ran = true;
                }
            }
        }
        return new RkhunterResult(ran, warnings);
    }

    /**
     * Parses a {@code lynis audit system} run: the hardening index and the
     * suggestion / warning counts. Pure and lenient - a missing field is
     * reported as {@code -1}.
     */
    public static LynisResult parseLynis(String output) {
        int index = firstGroupAsIntOrNegative(LYNIS_INDEX, output);
        int suggestions = firstGroupAsIntOrNegative(LYNIS_SUGGESTIONS, output);
        int warnings = firstGroupAsIntOrNegative(LYNIS_WARNINGS, output);
        return new LynisResult(index, suggestions, warnings);
    }

    /**
     * Parses {@code auditctl -s}. Understands both the single-line
     * {@code AUDIT_STATUS: enabled=1 pid=...} form and the older {@code key
     * value} line form. Pure and lenient.
     */
    public static AuditdStatus parseAuditdStatus(String output) {
        AuditdStatus s = new AuditdStatus();
        if (output == null || output.isBlank()) {
            return s;
        }
        for (String raw : output.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            // Single-line form: "enabled=1 flag=1 pid=1000 backlog_limit=320 lost=0".
            for (String pair : line.split("\\s+")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    s.set(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
            // Line form: "enabled 1" / "pid 1000".
            String[] kv = line.split("\\s+");
            if (kv.length == 2 && kv[0].matches("[a-z_]+")) {
                s.set(kv[0], kv[1]);
            }
        }
        return s;
    }

    /**
     * Counts the events in an {@code ausearch} dump: the {@code ----} record
     * separators, or one when there are {@code type=} lines but no separator, or
     * zero for {@code <no events>} / blank output. Pure.
     */
    public static int countAusearchEvents(String output) {
        if (output == null || output.isBlank()
                || output.toLowerCase(Locale.ROOT).contains("<no events>")) {
            return 0;
        }
        int separators = 0;
        boolean sawType = false;
        for (String raw : output.split("\\r?\\n")) {
            String line = raw.trim();
            if ("----".equals(line)) {
                separators++;
            } else if (line.startsWith("type=")) {
                sawType = true;
            }
        }
        if (separators > 0) {
            return separators;
        }
        return sawType ? 1 : 0;
    }

    private static int firstGroupAsInt(Pattern p, String text) {
        return Math.max(0, firstGroupAsIntOrNegative(p, text));
    }

    private static int firstGroupAsIntOrNegative(Pattern p, String text) {
        if (text == null) {
            return -1;
        }
        Matcher m = p.matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (RuntimeException e) {
                return -1;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Thin execution helpers (the app owns the UI wiring).

    /**
     * Runs a read-only HIDS query unprivileged through {@link ProcessRunner}.
     * Returns a not-started {@link ProcessRunner.Result} when the operation is
     * mutating, unsupported, or its vector could not be built.
     *
     * @param op     the read-only operation
     * @param params operation parameters (see {@link #buildCommand})
     */
    public static ProcessRunner.Result runRead(Operation op, String... params) {
        if (op == null || op.isMutating()) {
            return new ProcessRunner.Result(false, -1, "",
                    "operation is mutating; use runMutating");
        }
        List<String> cmd = buildCommand(op, params);
        if (cmd.isEmpty()) {
            return new ProcessRunner.Result(false, -1, "",
                    "unsupported or missing parameter: " + op);
        }
        return ProcessRunner.run(cmd);
    }

    /**
     * Runs a mutating / long HIDS operation with polkit escalation through
     * {@link PrivilegedRunner}. Returns an ERROR result when the operation is
     * read-only or its vector could not be built. The caller MUST have confirmed
     * first and MUST run this off the EDT.
     *
     * @param op     the mutating operation
     * @param params operation parameters (see {@link #buildCommand})
     */
    public static PrivilegedRunner.PrivilegedResult runMutating(Operation op, String... params) {
        if (op == null || !op.isMutating()) {
            return new PrivilegedRunner.PrivilegedResult(
                    PrivilegedRunner.Status.ERROR,
                    "operation is not mutating; use runRead", -1, "");
        }
        List<String> cmd = buildCommand(op, params);
        if (cmd.isEmpty()) {
            return new PrivilegedRunner.PrivilegedResult(
                    PrivilegedRunner.Status.ERROR,
                    "unsupported or missing parameter: " + op, -1, "");
        }
        return PrivilegedRunner.run(cmd);
    }

    // ------------------------------------------------------------------
    // Parsed value objects (immutable).

    /** The fail2ban server status: running, and the jails it manages. */
    public static final class Fail2BanStatus {
        private final boolean running;
        private final List<String> jails;

        public Fail2BanStatus(boolean running, List<String> jails) {
            this.running = running;
            this.jails = (jails == null)
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(jails));
        }

        /** True if the fail2ban server answered. */
        public boolean isRunning() {
            return running;
        }

        /** The managed jail names; never null. */
        public List<String> getJails() {
            return jails;
        }

        /** A one-line label for the UI. */
        public String describe() {
            if (!running) {
                return "not running";
            }
            return jails.size() + (jails.size() == 1 ? " jail" : " jails")
                    + (jails.isEmpty() ? "" : ": " + String.join(", ", jails));
        }
    }

    /** One fail2ban jail's ban state. */
    public static final class JailStatus {
        private final String jail;
        private final int currentlyBanned;
        private final int totalBanned;
        private final List<String> bannedIps;

        public JailStatus(String jail, int currentlyBanned, int totalBanned, List<String> bannedIps) {
            this.jail = (jail == null) ? "" : jail;
            this.currentlyBanned = Math.max(0, currentlyBanned);
            this.totalBanned = Math.max(0, totalBanned);
            this.bannedIps = (bannedIps == null)
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(bannedIps));
        }

        /** The jail name; never null. */
        public String getJail() {
            return jail;
        }

        /** Addresses banned right now. */
        public int getCurrentlyBanned() {
            return currentlyBanned;
        }

        /** Addresses banned since the jail started. */
        public int getTotalBanned() {
            return totalBanned;
        }

        /** The currently banned addresses; never null. */
        public List<String> getBannedIps() {
            return bannedIps;
        }

        /** A one-line label for the UI. */
        public String describe() {
            return (jail.isEmpty() ? "jail" : jail) + ": " + currentlyBanned
                    + " banned now, " + totalBanned + " total";
        }
    }

    /** An rkhunter scan summary. */
    public static final class RkhunterResult {
        private final boolean ran;
        private final int warnings;

        public RkhunterResult(boolean ran, int warnings) {
            this.ran = ran;
            this.warnings = Math.max(0, warnings);
        }

        /** True if the scan produced recognisable output. */
        public boolean didRun() {
            return ran;
        }

        /** The number of {@code Warning:} lines. */
        public int getWarnings() {
            return warnings;
        }

        /** A one-line label for the UI. */
        public String describe() {
            if (!ran) {
                return "no scan output";
            }
            return warnings == 0 ? "no warnings"
                    : warnings + (warnings == 1 ? " warning" : " warnings");
        }
    }

    /** A lynis audit summary. */
    public static final class LynisResult {
        private final int hardeningIndex;
        private final int suggestions;
        private final int warnings;

        public LynisResult(int hardeningIndex, int suggestions, int warnings) {
            this.hardeningIndex = hardeningIndex;
            this.suggestions = suggestions;
            this.warnings = warnings;
        }

        /** The hardening index (0-100), or {@code -1} when not reported. */
        public int getHardeningIndex() {
            return hardeningIndex;
        }

        /** The suggestion count, or {@code -1} when not reported. */
        public int getSuggestions() {
            return suggestions;
        }

        /** The warning count, or {@code -1} when not reported. */
        public int getWarnings() {
            return warnings;
        }

        /** A one-line label for the UI. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (hardeningIndex >= 0) {
                sb.append("hardening index ").append(hardeningIndex);
            }
            if (warnings >= 0) {
                sb.append(sb.length() == 0 ? "" : ", ").append(warnings).append(" warning(s)");
            }
            if (suggestions >= 0) {
                sb.append(sb.length() == 0 ? "" : ", ")
                        .append(suggestions).append(" suggestion(s)");
            }
            return sb.length() == 0 ? "no summary reported" : sb.toString();
        }
    }

    /** The auditd subsystem status parsed from {@code auditctl -s}. */
    public static final class AuditdStatus {
        private int enabled = -1;
        private int pid = -1;
        private int backlogLimit = -1;
        private int lost = -1;
        private int backlog = -1;

        void set(String key, String value) {
            int v = parseIntOr(value, -1);
            switch (key == null ? "" : key.toLowerCase(Locale.ROOT)) {
                case "enabled":
                    enabled = v;
                    break;
                case "pid":
                    pid = v;
                    break;
                case "backlog_limit":
                    backlogLimit = v;
                    break;
                case "lost":
                    lost = v;
                    break;
                case "backlog":
                    backlog = v;
                    break;
                default:
                    break;
            }
        }

        private static int parseIntOr(String s, int fallback) {
            try {
                return Integer.parseInt(s.trim());
            } catch (RuntimeException e) {
                return fallback;
            }
        }

        /** The {@code enabled} flag (1 = enabled, 0 = disabled, -1 = unknown). */
        public int getEnabled() {
            return enabled;
        }

        /** The auditd pid, or {@code -1}. */
        public int getPid() {
            return pid;
        }

        /** The backlog limit, or {@code -1}. */
        public int getBacklogLimit() {
            return backlogLimit;
        }

        /** The number of lost records, or {@code -1}. */
        public int getLost() {
            return lost;
        }

        /** The current backlog, or {@code -1}. */
        public int getBacklog() {
            return backlog;
        }

        /** True when the audit subsystem reports itself enabled. */
        public boolean isEnabled() {
            return enabled == 1;
        }

        /** A one-line label for the UI. */
        public String describe() {
            if (enabled < 0) {
                return "unknown";
            }
            String head = isEnabled() ? "enabled" : "disabled";
            if (pid >= 0) {
                head = head + ", pid " + pid;
            }
            if (lost >= 0) {
                head = head + ", " + lost + " lost";
            }
            return head;
        }
    }
}
