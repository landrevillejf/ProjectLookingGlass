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
 * Host-security front-end for the LFS/BLFS security manager, normative in
 * {@code system-management-contract.md} §4.6. It detects which firewall /
 * mandatory-access-control / secure-shell backends are installed, builds the
 * exact {@code nft} / {@code apparmor_status} / {@code getenforce} /
 * {@code sshd} argument vectors and parses their read-only output; it never
 * re-implements firewall or MAC logic and never writes {@code /etc} directly
 * (mutations go through the owning CLI, §6).
 *
 * <p>Operation classes drive the UI's confirmation level:
 * <ul>
 *   <li>{@link Operation#NFT_LIST}, {@link Operation#APPARMOR_STATUS},
 *       {@link Operation#SELINUX_MODE}, {@link Operation#SELINUX_ENABLED},
 *       {@link Operation#SSHD_STATUS} and {@link Operation#SSHD_CONFIG_TEST}
 *       are <em>read-only</em> and run unprivileged via {@link ProcessRunner};</li>
 *   <li>{@link Operation#NFT_APPLY}, {@link Operation#SSHD_START} and
 *       {@link Operation#SSHD_STOP} are <em>mutating</em> - confirmed, then
 *       escalated via {@link PrivilegedRunner} (polkit).</li>
 * </ul>
 * The sshd lifecycle is delegated to {@link InitSystemService} (§4.1) with
 * {@code sshd} as the service name, so no init logic is duplicated here.</p>
 *
 * <p>The detection, command-building and output parsing are pure and
 * unit-testable ({@link #apparmorTool(Probes)}, {@link #buildCommand},
 * {@code parse*}, {@code summarize*}); the live {@link #probe()} and the
 * {@code run*} helpers are thin wrappers that degrade gracefully - an absent
 * tool or an unsupported operation yields an empty vector / a not-started
 * result rather than throwing.</p>
 */
public final class SecurityService {

    /** The nftables config the GUI may reload (read-only for the GUI, §4.6). */
    public static final String NFTABLES_CONF = "/etc/nftables.conf";

    /** The secure-shell service name driven through the init abstraction (§4.1). */
    public static final String SSHD_SERVICE = "sshd";

    private static final Pattern APPARMOR_ENFORCE =
            Pattern.compile("(\\d+)\\s+processes?\\s+are\\s+in\\s+enforce", Pattern.CASE_INSENSITIVE);
    private static final Pattern APPARMOR_COMPLAIN =
            Pattern.compile("(\\d+)\\s+processes?\\s+are\\s+in\\s+complain", Pattern.CASE_INSENSITIVE);
    private static final Pattern NFT_TABLE = Pattern.compile("^\\s*table\\b");
    private static final Pattern NFT_CHAIN = Pattern.compile("^\\s*chain\\b");

    private SecurityService() {
        // no instances
    }

    /** A §4.6 security operation, carrying its confirmation class. */
    public enum Operation {
        /** {@code nft list ruleset} - read-only. */
        NFT_LIST(false),
        /** {@code nft -f /etc/nftables.conf} - mutating (apply/reload). */
        NFT_APPLY(true),
        /** {@code apparmor_status} / {@code aa-status} - read-only. */
        APPARMOR_STATUS(false),
        /** {@code getenforce} - read-only. */
        SELINUX_MODE(false),
        /** {@code selinuxenabled} - read-only (exit code = enabled?). */
        SELINUX_ENABLED(false),
        /** init-system {@code status sshd} (§4.1) - read-only. */
        SSHD_STATUS(false),
        /** {@code sshd -t} configuration test - read-only. */
        SSHD_CONFIG_TEST(false),
        /** init-system {@code start sshd} (§4.1) - mutating. */
        SSHD_START(true),
        /** init-system {@code stop sshd} (§4.1) - mutating. */
        SSHD_STOP(true);

        private final boolean mutating;

        Operation(boolean mutating) {
            this.mutating = mutating;
        }

        /** True if the operation changes state and needs polkit escalation. */
        public boolean isMutating() {
            return mutating;
        }
    }

    /** Which AppArmor status tool is installed (they are interchangeable). */
    public enum AppArmorTool {
        /** {@code apparmor_status} (the AppArmor-utils name). */
        APPARMOR_STATUS,
        /** {@code aa-status} (the newer symlink name). */
        AA_STATUS,
        /** Neither tool is installed; AppArmor status is unavailable. */
        NONE
    }

    /** The SELinux enforcement mode reported by {@code getenforce}. */
    public enum SelinuxMode {
        /** Policy is actively enforced. */
        ENFORCING,
        /** Policy violations are logged but allowed. */
        PERMISSIVE,
        /** SELinux is turned off. */
        DISABLED,
        /** The mode could not be determined. */
        UNKNOWN
    }

    /** The secure-shell daemon running state. */
    public enum SshdState {
        /** The daemon is running. */
        RUNNING,
        /** The daemon is installed but not running. */
        STOPPED,
        /** The state could not be determined. */
        UNKNOWN
    }

    /**
     * The filesystem/PATH facts {@link #probe()} gathers and the pure predicates
     * consume. Splitting these out keeps detection unit-testable without the
     * real security tools present.
     */
    public static final class Probes {
        private final boolean nft;
        private final boolean apparmorStatus;
        private final boolean aaStatus;
        private final boolean getenforce;
        private final boolean selinuxEnabled;
        private final boolean sshd;

        public Probes(boolean nft, boolean apparmorStatus, boolean aaStatus,
                      boolean getenforce, boolean selinuxEnabled, boolean sshd) {
            this.nft = nft;
            this.apparmorStatus = apparmorStatus;
            this.aaStatus = aaStatus;
            this.getenforce = getenforce;
            this.selinuxEnabled = selinuxEnabled;
            this.sshd = sshd;
        }

        boolean hasNft() {
            return nft;
        }

        boolean hasApparmorStatus() {
            return apparmorStatus;
        }

        boolean hasAaStatus() {
            return aaStatus;
        }

        boolean hasGetenforce() {
            return getenforce;
        }

        boolean hasSelinuxEnabled() {
            return selinuxEnabled;
        }

        boolean hasSshd() {
            return sshd;
        }
    }

    // ------------------------------------------------------------------
    // Detection (pure predicates over Probes + a live probe()).

    /**
     * Live detection against this host: probes {@code PATH} for each security
     * tool. Never spawns a mutating process; {@link ProcessRunner#isAvailable}
     * only checks the PATH and is cached.
     */
    public static Probes probe() {
        return new Probes(
                ProcessRunner.isAvailable("nft"),
                ProcessRunner.isAvailable("apparmor_status"),
                ProcessRunner.isAvailable("aa-status"),
                ProcessRunner.isAvailable("getenforce"),
                ProcessRunner.isAvailable("selinuxenabled"),
                ProcessRunner.isAvailable("sshd"));
    }

    /** True if {@code nft} is present (the firewall backend §4.6 drives). */
    public static boolean isNftPresent(Probes p) {
        return p != null && p.hasNft();
    }

    /** True if either SELinux probe ({@code getenforce} / {@code selinuxenabled}) is present. */
    public static boolean isSelinuxPresent(Probes p) {
        return p != null && (p.hasGetenforce() || p.hasSelinuxEnabled());
    }

    /** True if the {@code sshd} binary is present (for the {@code sshd -t} config test). */
    public static boolean isSshdPresent(Probes p) {
        return p != null && p.hasSshd();
    }

    /**
     * Picks the AppArmor status tool: {@code apparmor_status} is preferred, then
     * {@code aa-status}, else {@link AppArmorTool#NONE}. Pure.
     */
    public static AppArmorTool apparmorTool(Probes p) {
        if (p == null) {
            return AppArmorTool.NONE;
        }
        if (p.hasApparmorStatus()) {
            return AppArmorTool.APPARMOR_STATUS;
        }
        if (p.hasAaStatus()) {
            return AppArmorTool.AA_STATUS;
        }
        return AppArmorTool.NONE;
    }

    /** Live convenience: is {@code nft} on this host? */
    public static boolean isNftAvailable() {
        return isNftPresent(probe());
    }

    /** Live convenience: is SELinux tooling on this host? */
    public static boolean isSelinuxAvailable() {
        return isSelinuxPresent(probe());
    }

    /** Live convenience: is the {@code sshd} binary on this host? */
    public static boolean isSshdAvailable() {
        return isSshdPresent(probe());
    }

    /** Live convenience: which AppArmor status tool is on this host? */
    public static AppArmorTool detectAppArmorTool() {
        return apparmorTool(probe());
    }

    // ------------------------------------------------------------------
    // Command building (pure; no shell interpolation).

    /**
     * Builds the argument vector for {@code op}, resolving the AppArmor tool and
     * init system from {@code aaTool} / {@code init}. Returns an empty list when
     * the operation is unsupported, the tool is absent, or the init system is
     * unknown; the result never contains shell metacharacters and is meant to be
     * handed straight to {@link ProcessRunner} / {@link PrivilegedRunner}.
     *
     * @param op     the operation
     * @param aaTool the resolved AppArmor tool (only used by {@link Operation#APPARMOR_STATUS})
     * @param init   the detected init system (only used by the sshd lifecycle ops)
     */
    public static List<String> buildCommand(
            Operation op, AppArmorTool aaTool, InitSystemService.InitSystem init) {
        if (op == null) {
            return Collections.emptyList();
        }
        switch (op) {
            case NFT_LIST:
            case NFT_APPLY:
                return nftCommand(op);
            case APPARMOR_STATUS:
                return apparmorCommand(aaTool);
            case SELINUX_MODE:
            case SELINUX_ENABLED:
                return selinuxCommand(op);
            case SSHD_STATUS:
            case SSHD_START:
            case SSHD_STOP:
            case SSHD_CONFIG_TEST:
                return sshdCommand(op, init);
            default:
                return Collections.emptyList();
        }
    }

    /**
     * Builds the nftables vector: {@code nft list ruleset} (read) or
     * {@code nft -f /etc/nftables.conf} (apply/reload). Empty for any other op.
     */
    public static List<String> nftCommand(Operation op) {
        if (op == Operation.NFT_LIST) {
            return Arrays.asList("nft", "list", "ruleset");
        }
        if (op == Operation.NFT_APPLY) {
            return Arrays.asList("nft", "-f", NFTABLES_CONF);
        }
        return Collections.emptyList();
    }

    /** Builds the AppArmor status vector for the resolved tool; empty for {@link AppArmorTool#NONE}. */
    public static List<String> apparmorCommand(AppArmorTool tool) {
        if (tool == AppArmorTool.APPARMOR_STATUS) {
            return Collections.singletonList("apparmor_status");
        }
        if (tool == AppArmorTool.AA_STATUS) {
            return Collections.singletonList("aa-status");
        }
        return Collections.emptyList();
    }

    /** Builds the SELinux vector: {@code getenforce} or {@code selinuxenabled}; empty otherwise. */
    public static List<String> selinuxCommand(Operation op) {
        if (op == Operation.SELINUX_MODE) {
            return Collections.singletonList("getenforce");
        }
        if (op == Operation.SELINUX_ENABLED) {
            return Collections.singletonList("selinuxenabled");
        }
        return Collections.emptyList();
    }

    /**
     * Builds the sshd vector. {@link Operation#SSHD_CONFIG_TEST} is the bare
     * {@code sshd -t}; the status/start/stop operations delegate to
     * {@link InitSystemService#buildCommand} with {@value #SSHD_SERVICE} as the
     * service name (§4.1), so an unknown init yields an empty vector.
     */
    public static List<String> sshdCommand(Operation op, InitSystemService.InitSystem init) {
        if (op == Operation.SSHD_CONFIG_TEST) {
            return Arrays.asList("sshd", "-t");
        }
        if (op == Operation.SSHD_STATUS) {
            return InitSystemService.buildCommand(init, InitSystemService.Operation.STATUS, SSHD_SERVICE);
        }
        if (op == Operation.SSHD_START) {
            return InitSystemService.buildCommand(init, InitSystemService.Operation.START, SSHD_SERVICE);
        }
        if (op == Operation.SSHD_STOP) {
            return InitSystemService.buildCommand(init, InitSystemService.Operation.STOP, SSHD_SERVICE);
        }
        return Collections.emptyList();
    }

    // ------------------------------------------------------------------
    // Pure output parsing (headless-testable; no process is spawned).

    /**
     * Parses {@code getenforce} output into a {@link SelinuxMode}. Blank or
     * unrecognised text yields {@link SelinuxMode#UNKNOWN}; an absent tool is
     * reported by the caller (never a silent "secure").
     */
    public static SelinuxMode parseSelinuxMode(String output) {
        if (output == null || output.isBlank()) {
            return SelinuxMode.UNKNOWN;
        }
        String mode = output.trim().toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "ENFORCING" -> SelinuxMode.ENFORCING;
            case "PERMISSIVE" -> SelinuxMode.PERMISSIVE;
            case "DISABLED" -> SelinuxMode.DISABLED;
            default -> SelinuxMode.UNKNOWN;
        };
    }

    /**
     * Interprets the {@code selinuxenabled} exit code: {@code 0} means SELinux
     * is enabled, any non-zero means it is not (or is absent).
     */
    public static boolean parseSelinuxEnabled(int exitCode) {
        return exitCode == 0;
    }

    /**
     * Parses {@code apparmor_status} / {@code aa-status} output. Detects whether
     * the module is loaded and counts the processes in enforce / complain mode
     * from the "{@code N processes are in <mode> mode}" lines. Pure and lenient:
     * blank output yields a not-loaded, zero-count status.
     */
    public static AppArmorStatus parseAppArmor(String output) {
        if (output == null || output.isBlank()) {
            return new AppArmorStatus(false, 0, 0);
        }
        String text = output.toLowerCase(Locale.ROOT);
        boolean loaded = text.contains("module is loaded") && !text.contains("module is not loaded");
        int enforce = firstGroupAsInt(APPARMOR_ENFORCE, output);
        int complain = firstGroupAsInt(APPARMOR_COMPLAIN, output);
        return new AppArmorStatus(loaded, enforce, complain);
    }

    /**
     * Summarises an {@code nft list ruleset} dump by counting the {@code table}
     * and {@code chain} declaration lines. The full ruleset text is shown
     * verbatim by the UI; this is only the one-line summary. Pure and lenient.
     */
    public static NftSummary summarizeNft(String output) {
        int tables = 0;
        int chains = 0;
        if (output != null) {
            for (String line : output.split("\\r?\\n")) {
                if (NFT_TABLE.matcher(line).find()) {
                    tables++;
                } else if (NFT_CHAIN.matcher(line).find()) {
                    chains++;
                }
            }
        }
        return new NftSummary(tables, chains);
    }

    /**
     * Parses an init-system {@code status sshd} result into a {@link SshdState}.
     * Recognises the systemd ({@code active (running)} / {@code inactive (dead)}),
     * runit ({@code run:} / {@code down:}) and openrc ({@code status: started} /
     * {@code status: stopped}) shapes; negative phrases are matched first so
     * "{@code not running}" never reads as running. With no usable text the exit
     * code decides ({@code 0} = running for a status command).
     */
    public static SshdState parseSshdState(int exitCode, String output) {
        String t = (output == null) ? "" : output.toLowerCase(Locale.ROOT);
        if (t.contains("inactive") || t.contains("dead") || t.contains("stopped")
                || t.contains("down:") || t.contains("not running") || t.contains("failed")) {
            return SshdState.STOPPED;
        }
        if (t.contains("active (running)") || t.contains("run:") || t.contains("running")
                || t.contains("status: started") || t.contains("is running")) {
            return SshdState.RUNNING;
        }
        return (exitCode == 0) ? SshdState.RUNNING : SshdState.UNKNOWN;
    }

    /** Interprets the {@code sshd -t} config test exit code: {@code 0} = valid config. */
    public static boolean isSshdConfigOk(int exitCode) {
        return exitCode == 0;
    }

    /** A short human label for a SELinux mode; never null. */
    public static String describeSelinux(SelinuxMode mode) {
        if (mode == null) {
            return "Unknown";
        }
        return switch (mode) {
            case ENFORCING -> "Enforcing";
            case PERMISSIVE -> "Permissive";
            case DISABLED -> "Disabled";
            case UNKNOWN -> "Unknown";
        };
    }

    /** A short human label for an sshd state; never null. */
    public static String describeSshd(SshdState state) {
        if (state == null) {
            return "Unknown";
        }
        return switch (state) {
            case RUNNING -> "Running";
            case STOPPED -> "Stopped";
            case UNKNOWN -> "Unknown";
        };
    }

    private static int firstGroupAsInt(Pattern p, String text) {
        Matcher m = p.matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (RuntimeException e) {
                return 0;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // Thin execution helpers (kept minimal; the app owns the UI wiring).

    /**
     * Runs a read-only operation unprivileged through {@link ProcessRunner},
     * resolving the AppArmor tool and init system live. Returns a not-started
     * {@link ProcessRunner.Result} when the operation is mutating, unsupported,
     * or its tool is absent.
     */
    public static ProcessRunner.Result runRead(Operation op) {
        if (op == null || op.isMutating()) {
            return new ProcessRunner.Result(false, -1, "", "operation is mutating; use runMutating");
        }
        List<String> cmd = buildCommand(op, detectAppArmorTool(), InitSystemService.detect());
        if (cmd.isEmpty()) {
            return new ProcessRunner.Result(false, -1, "", "unsupported or unavailable: " + op);
        }
        return ProcessRunner.run(cmd);
    }

    /**
     * Runs a mutating operation with polkit escalation through
     * {@link PrivilegedRunner}. The sshd lifecycle delegates to
     * {@link InitSystemService#runMutating} (§4.1). Returns an ERROR result when
     * the operation is read-only, unsupported, or its tool is absent. The caller
     * MUST have confirmed first.
     */
    public static PrivilegedRunner.PrivilegedResult runMutating(Operation op) {
        if (op == null || !op.isMutating()) {
            return new PrivilegedRunner.PrivilegedResult(
                    PrivilegedRunner.Status.ERROR, "operation is not mutating; use runRead", -1, "");
        }
        if (op == Operation.SSHD_START || op == Operation.SSHD_STOP) {
            InitSystemService.Operation initOp = (op == Operation.SSHD_START)
                    ? InitSystemService.Operation.START
                    : InitSystemService.Operation.STOP;
            return InitSystemService.runMutating(InitSystemService.detect(), initOp, SSHD_SERVICE);
        }
        List<String> cmd = buildCommand(op, AppArmorTool.NONE, InitSystemService.InitSystem.UNKNOWN);
        if (cmd.isEmpty()) {
            return new PrivilegedRunner.PrivilegedResult(
                    PrivilegedRunner.Status.ERROR, "unsupported or unavailable: " + op, -1, "");
        }
        return PrivilegedRunner.run(cmd);
    }

    // ------------------------------------------------------------------
    // Parsed value objects (immutable).

    /**
     * The parsed AppArmor posture: whether the module is loaded and how many
     * processes are in enforce / complain mode.
     */
    public static final class AppArmorStatus {
        private final boolean moduleLoaded;
        private final int enforce;
        private final int complain;

        public AppArmorStatus(boolean moduleLoaded, int enforce, int complain) {
            this.moduleLoaded = moduleLoaded;
            this.enforce = Math.max(0, enforce);
            this.complain = Math.max(0, complain);
        }

        /** True if the AppArmor module is loaded. */
        public boolean isModuleLoaded() {
            return moduleLoaded;
        }

        /** The number of processes in enforce mode. */
        public int getEnforceCount() {
            return enforce;
        }

        /** The number of processes in complain mode. */
        public int getComplainCount() {
            return complain;
        }

        /** A one-line label for the overview card. */
        public String describe() {
            if (!moduleLoaded) {
                return "Not loaded";
            }
            return "Loaded (" + enforce + " enforce, " + complain + " complain)";
        }
    }

    /**
     * A one-line summary of an {@code nft list ruleset} dump: how many tables
     * and chains it declares.
     */
    public static final class NftSummary {
        private final int tables;
        private final int chains;

        public NftSummary(int tables, int chains) {
            this.tables = Math.max(0, tables);
            this.chains = Math.max(0, chains);
        }

        /** The number of {@code table} declarations. */
        public int getTables() {
            return tables;
        }

        /** The number of {@code chain} declarations. */
        public int getChains() {
            return chains;
        }

        /** True when the ruleset declares no tables and no chains. */
        public boolean isEmpty() {
            return tables == 0 && chains == 0;
        }

        /** A one-line label for the firewall header. */
        public String describe() {
            if (isEmpty()) {
                return "empty ruleset";
            }
            return tables + (tables == 1 ? " table" : " tables")
                    + ", " + chains + (chains == 1 ? " chain" : " chains");
        }
    }
}
