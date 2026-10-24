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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
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
 *
 * <p>Beyond that read-only state, this class also owns <em>SELinux policy
 * management</em> (inventory §6.11, gap G2): {@code semanage},
 * {@code restorecon}, {@code semodule}, {@code load_policy} and the
 * {@code ausearch -> audit2allow} review pipe. It is a separate, self-contained
 * section ({@link PolicyOperation}, {@link #buildPolicyCommand},
 * {@code runPolicy*}) so the state surface above is untouched. Every policy
 * write is destructive: the UI confirms it with a typed phrase and escalates
 * through {@link PrivilegedRunner}; audit2allow only ever generates a module
 * <em>draft</em> for review and never installs one (§6).</p>
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

    // ==================================================================
    // SELinux policy management (inventory §6.11, gap G2). The read-only
    // state (getenforce / selinuxenabled) lives above; this section adds the
    // policy tools the desktop had no front-end for - semanage, restorecon,
    // semodule, load_policy and the ausearch -> audit2allow review pipe. Every
    // vector is built here (never a shell string); a malformed parameter yields
    // an empty vector rather than a partial command. Reads run unprivileged
    // through ProcessRunner, mutations are confirmed (typed) and escalated
    // through PrivilegedRunner. Nothing here writes /etc: policy changes go
    // through the owning CLI under polkit (§6).

    /** The audit event type the AVC review pipe queries ({@code ausearch -m}). */
    public static final String AVC_EVENT_TYPE = "avc";

    /** A SELinux policy operation, carrying its confirmation class. */
    public enum PolicyOperation {
        /** {@code semanage fcontext -l} - list file-context rules (read-only). */
        SEMANAGE_FCONTEXT_LIST(false),
        /** {@code semanage fcontext -a -t <type> <regex>} - add a rule (destructive). */
        SEMANAGE_FCONTEXT_ADD(true),
        /** {@code semanage fcontext -d <regex>} - delete a rule (destructive). */
        SEMANAGE_FCONTEXT_DEL(true),
        /** {@code semanage boolean -l} - list booleans (read-only). */
        SEMANAGE_BOOLEAN_LIST(false),
        /** {@code semanage boolean -m --on|--off <name>} - set a boolean (destructive). */
        SEMANAGE_BOOLEAN_SET(true),
        /** {@code semanage port -l} - list port contexts (read-only). */
        SEMANAGE_PORT_LIST(false),
        /** {@code semanage port -a -t <type> -p <proto> <port>} - add a context (destructive). */
        SEMANAGE_PORT_ADD(true),
        /** {@code semanage port -d -t <type> -p <proto> <port>} - delete a context (destructive). */
        SEMANAGE_PORT_DEL(true),
        /** {@code restorecon -Rv <path>} - relabel a path to its context (destructive). */
        RESTORECON(true),
        /** {@code semodule -l} - list installed policy modules (read-only). */
        SEMODULE_LIST(false),
        /** {@code semodule -i <module.pp>} - install a policy module (destructive). */
        SEMODULE_INSTALL(true),
        /** {@code semodule -r <name>} - remove a policy module (destructive). */
        SEMODULE_REMOVE(true),
        /** {@code load_policy} - reload the policy into the kernel (destructive). */
        LOAD_POLICY(true),
        /** {@code ausearch -m avc -ts recent} - read recent AVC denials (read-only). */
        AUSEARCH_AVC(false),
        /** {@code audit2allow} on stdin - generate a module draft for review (read-only). */
        AUDIT2ALLOW(false);

        private final boolean mutating;

        PolicyOperation(boolean mutating) {
            this.mutating = mutating;
        }

        /** True if the operation changes policy and needs typed confirmation + polkit. */
        public boolean isMutating() {
            return mutating;
        }
    }

    /** Which SELinux policy tool an operation drives. */
    public enum PolicyTool {
        /** {@code semanage} (the policy-management CLI). */
        SEMANAGE,
        /** {@code restorecon} (the file relabeller). */
        RESTORECON,
        /** {@code semodule} (the policy-module manager). */
        SEMODULE,
        /** {@code load_policy} (the kernel policy reloader). */
        LOAD_POLICY,
        /** {@code ausearch} (the audit-log query). */
        AUSEARCH,
        /** {@code audit2allow} (the denial-to-module generator). */
        AUDIT2ALLOW
    }

    /**
     * The {@code PATH} facts {@link #probePolicy()} gathers for the policy
     * tools. Split out so detection is unit-testable without the real SELinux
     * toolchain present (the dev host has none of it).
     */
    public static final class PolicyProbes {
        private final boolean semanage;
        private final boolean restorecon;
        private final boolean semodule;
        private final boolean loadPolicy;
        private final boolean ausearch;
        private final boolean audit2allow;

        public PolicyProbes(boolean semanage, boolean restorecon, boolean semodule,
                            boolean loadPolicy, boolean ausearch, boolean audit2allow) {
            this.semanage = semanage;
            this.restorecon = restorecon;
            this.semodule = semodule;
            this.loadPolicy = loadPolicy;
            this.ausearch = ausearch;
            this.audit2allow = audit2allow;
        }

        boolean semanagePresent() {
            return semanage;
        }

        boolean restoreconPresent() {
            return restorecon;
        }

        boolean semodulePresent() {
            return semodule;
        }

        boolean loadPolicyPresent() {
            return loadPolicy;
        }

        boolean ausearchPresent() {
            return ausearch;
        }

        boolean audit2allowPresent() {
            return audit2allow;
        }
    }

    /**
     * Live detection of the SELinux policy tools: probes {@code PATH} for each.
     * Never spawns a mutating process; {@link ProcessRunner#isAvailable} only
     * checks the PATH and is cached.
     */
    public static PolicyProbes probePolicy() {
        return new PolicyProbes(
                ProcessRunner.isAvailable("semanage"),
                ProcessRunner.isAvailable("restorecon"),
                ProcessRunner.isAvailable("semodule"),
                ProcessRunner.isAvailable("load_policy"),
                ProcessRunner.isAvailable("ausearch"),
                ProcessRunner.isAvailable("audit2allow"));
    }

    /** True if the given policy tool is present. Pure. */
    public static boolean isPolicyToolPresent(PolicyProbes p, PolicyTool tool) {
        if (p == null || tool == null) {
            return false;
        }
        return switch (tool) {
            case SEMANAGE -> p.semanagePresent();
            case RESTORECON -> p.restoreconPresent();
            case SEMODULE -> p.semodulePresent();
            case LOAD_POLICY -> p.loadPolicyPresent();
            case AUSEARCH -> p.ausearchPresent();
            case AUDIT2ALLOW -> p.audit2allowPresent();
        };
    }

    /** The policy tool an operation drives; null for a null operation. Pure. */
    public static PolicyTool toolFor(PolicyOperation op) {
        if (op == null) {
            return null;
        }
        return switch (op) {
            case SEMANAGE_FCONTEXT_LIST, SEMANAGE_FCONTEXT_ADD, SEMANAGE_FCONTEXT_DEL,
                 SEMANAGE_BOOLEAN_LIST, SEMANAGE_BOOLEAN_SET,
                 SEMANAGE_PORT_LIST, SEMANAGE_PORT_ADD, SEMANAGE_PORT_DEL -> PolicyTool.SEMANAGE;
            case RESTORECON -> PolicyTool.RESTORECON;
            case SEMODULE_LIST, SEMODULE_INSTALL, SEMODULE_REMOVE -> PolicyTool.SEMODULE;
            case LOAD_POLICY -> PolicyTool.LOAD_POLICY;
            case AUSEARCH_AVC -> PolicyTool.AUSEARCH;
            case AUDIT2ALLOW -> PolicyTool.AUDIT2ALLOW;
        };
    }

    /**
     * True if {@code s} is a single safe word (a context type, a boolean or
     * module name, a protocol, a port): non-blank, no whitespace, at most 64
     * characters. Used to reject a malformed parameter before a vector is
     * built. Pure.
     */
    public static boolean isToken(String s) {
        return s != null && !s.isBlank() && s.length() <= 64
                && s.chars().noneMatch(Character::isWhitespace);
    }

    /**
     * True if {@code s} is a safe path / fcontext regex: non-blank, at most
     * 4096 characters and free of control characters (newline, tab, NUL).
     * Spaces are allowed because the value is handed to {@code exec} as one
     * argument-vector element and never through a shell, so a space cannot
     * split it; a control character is rejected as malformed. Pure.
     */
    public static boolean isPath(String s) {
        if (s == null || s.isBlank() || s.length() > 4096) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds the argument vector for a policy operation. Returns an empty list
     * when the operation is null or a parameter is missing / malformed, so a
     * partial command is never handed to a runner. The result never contains
     * shell metacharacters as separate words and is meant to go straight to
     * {@link ProcessRunner} / {@link PrivilegedRunner}.
     *
     * <p>Parameter shapes: {@code SEMANAGE_FCONTEXT_ADD} = (type, pathRegex);
     * {@code SEMANAGE_FCONTEXT_DEL} = (pathRegex); {@code SEMANAGE_BOOLEAN_SET}
     * = (name, on|off); {@code SEMANAGE_PORT_ADD}/{@code _DEL} = (type, proto,
     * port); {@code RESTORECON} = (path); {@code SEMODULE_INSTALL} = (modulePath);
     * {@code SEMODULE_REMOVE} = (name). The list / read / audit2allow ops take
     * none.</p>
     */
    public static List<String> buildPolicyCommand(PolicyOperation op, String... params) {
        if (op == null) {
            return Collections.emptyList();
        }
        String[] a = (params == null) ? new String[0] : params;
        return switch (op) {
            case SEMANAGE_FCONTEXT_LIST -> Arrays.asList("semanage", "fcontext", "-l");
            case SEMANAGE_FCONTEXT_ADD -> (a.length == 2 && isToken(a[0]) && isPath(a[1]))
                    ? Arrays.asList("semanage", "fcontext", "-a", "-t", a[0], a[1])
                    : Collections.emptyList();
            case SEMANAGE_FCONTEXT_DEL -> (a.length == 1 && isPath(a[0]))
                    ? Arrays.asList("semanage", "fcontext", "-d", a[0])
                    : Collections.emptyList();
            case SEMANAGE_BOOLEAN_LIST -> Arrays.asList("semanage", "boolean", "-l");
            case SEMANAGE_BOOLEAN_SET -> booleanSetCommand(a);
            case SEMANAGE_PORT_LIST -> Arrays.asList("semanage", "port", "-l");
            case SEMANAGE_PORT_ADD -> portCommand("-a", a);
            case SEMANAGE_PORT_DEL -> portCommand("-d", a);
            case RESTORECON -> (a.length == 1 && isPath(a[0]))
                    ? Arrays.asList("restorecon", "-Rv", a[0])
                    : Collections.emptyList();
            case SEMODULE_LIST -> Arrays.asList("semodule", "-l");
            case SEMODULE_INSTALL -> (a.length == 1 && isPath(a[0]))
                    ? Arrays.asList("semodule", "-i", a[0])
                    : Collections.emptyList();
            case SEMODULE_REMOVE -> (a.length == 1 && isToken(a[0]))
                    ? Arrays.asList("semodule", "-r", a[0])
                    : Collections.emptyList();
            case LOAD_POLICY -> Collections.singletonList("load_policy");
            case AUSEARCH_AVC -> Arrays.asList("ausearch", "-m", AVC_EVENT_TYPE, "-ts", "recent");
            case AUDIT2ALLOW -> Collections.singletonList("audit2allow");
        };
    }

    private static List<String> booleanSetCommand(String[] a) {
        if (a.length != 2 || !isToken(a[0])) {
            return Collections.emptyList();
        }
        String v = a[1] == null ? "" : a[1].toLowerCase(Locale.ROOT);
        if (!v.equals("on") && !v.equals("off")) {
            return Collections.emptyList();
        }
        return Arrays.asList("semanage", "boolean", "-m", v.equals("on") ? "--on" : "--off", a[0]);
    }

    private static List<String> portCommand(String addOrDel, String[] a) {
        if (a.length != 3 || !isToken(a[0]) || !isToken(a[1]) || !isToken(a[2])) {
            return Collections.emptyList();
        }
        return Arrays.asList("semanage", "port", addOrDel, "-t", a[0], "-p", a[1], a[2]);
    }

    /** A short human label for a policy operation; never null. */
    public static String describePolicy(PolicyOperation op) {
        if (op == null) {
            return "Unknown";
        }
        return switch (op) {
            case SEMANAGE_FCONTEXT_LIST -> "List file-context rules";
            case SEMANAGE_FCONTEXT_ADD -> "Add file-context rule";
            case SEMANAGE_FCONTEXT_DEL -> "Delete file-context rule";
            case SEMANAGE_BOOLEAN_LIST -> "List booleans";
            case SEMANAGE_BOOLEAN_SET -> "Set boolean";
            case SEMANAGE_PORT_LIST -> "List port contexts";
            case SEMANAGE_PORT_ADD -> "Add port context";
            case SEMANAGE_PORT_DEL -> "Delete port context";
            case RESTORECON -> "Relabel path (restorecon)";
            case SEMODULE_LIST -> "List policy modules";
            case SEMODULE_INSTALL -> "Install policy module";
            case SEMODULE_REMOVE -> "Remove policy module";
            case LOAD_POLICY -> "Reload kernel policy";
            case AUSEARCH_AVC -> "Read recent AVC denials";
            case AUDIT2ALLOW -> "Generate module draft (review)";
        };
    }

    /**
     * Counts the AVC denials in {@code ausearch -m avc} output by counting the
     * {@code type=AVC} record lines. Pure and lenient: blank or unrecognised
     * output yields {@code 0}.
     */
    public static int countAvcDenials(String output) {
        if (output == null || output.isBlank()) {
            return 0;
        }
        int denials = 0;
        for (String line : output.split("\\r?\\n")) {
            if (line.toLowerCase(Locale.ROOT).contains("type=avc")) {
                denials++;
            }
        }
        return denials;
    }

    /**
     * Summarises an {@code audit2allow} generated module for review: how many
     * {@code allow} rules and how many tunable/{@code setsebool} hints it
     * drafts. Pure and lenient: blank text yields an empty draft. The full text
     * is shown verbatim by the UI; this is only the one-line summary.
     */
    public static ModuleDraft summarizeAudit2Allow(String moduleText) {
        int allows = 0;
        int tunables = 0;
        if (moduleText != null) {
            for (String line : moduleText.split("\\r?\\n")) {
                String t = line.trim();
                if (t.startsWith("allow ")) {
                    allows++;
                } else if (t.contains("setsebool") || t.startsWith("tunable_policy")) {
                    tunables++;
                }
            }
        }
        return new ModuleDraft(allows, tunables);
    }

    /**
     * Runs a read-only policy operation unprivileged through
     * {@link ProcessRunner}. Returns a not-started result (spawning nothing)
     * when the operation is mutating, null, or its vector is empty (unsupported
     * or malformed parameters).
     */
    public static ProcessRunner.Result runPolicyRead(PolicyOperation op, String... params) {
        if (op == null || op.isMutating()) {
            return new ProcessRunner.Result(false, -1, "",
                    "operation is mutating; use runPolicyMutating");
        }
        List<String> cmd = buildPolicyCommand(op, params);
        if (cmd.isEmpty()) {
            return new ProcessRunner.Result(false, -1, "",
                    "unsupported, unavailable or malformed: " + op);
        }
        return ProcessRunner.run(cmd);
    }

    /**
     * Runs the {@code ausearch -> audit2allow} review pipe without a shell: the
     * AVC denials already captured from {@code ausearch -m avc} are fed to
     * {@code audit2allow} on stdin and the generated module <em>draft</em> is
     * returned for the admin to review. This is read-only - audit2allow only
     * prints a draft, it never installs one; installing goes through
     * {@link PolicyOperation#SEMODULE_INSTALL} under polkit. Returns a
     * not-started result when {@code audit2allow} is absent.
     */
    public static ProcessRunner.Result runAudit2Allow(String avcDenials) {
        if (!ProcessRunner.isAvailable("audit2allow")) {
            return new ProcessRunner.Result(false, -1, "", "audit2allow is not installed");
        }
        return ProcessRunner.run(Collections.singletonList("audit2allow"),
                avcDenials == null ? "" : avcDenials, 30, TimeUnit.SECONDS, null);
    }

    /**
     * Runs a mutating policy operation with polkit escalation through
     * {@link PrivilegedRunner}. The caller MUST have confirmed first - the UI
     * requires a typed confirmation for every policy write. Returns an ERROR
     * result (spawning nothing) when the operation is read-only, null, or its
     * vector is empty.
     */
    public static PrivilegedRunner.PrivilegedResult runPolicyMutating(
            PolicyOperation op, String... params) {
        if (op == null || !op.isMutating()) {
            return new PrivilegedRunner.PrivilegedResult(
                    PrivilegedRunner.Status.ERROR,
                    "operation is not mutating; use runPolicyRead", -1, "");
        }
        List<String> cmd = buildPolicyCommand(op, params);
        if (cmd.isEmpty()) {
            return new PrivilegedRunner.PrivilegedResult(
                    PrivilegedRunner.Status.ERROR,
                    "unsupported, unavailable or malformed: " + op, -1, "");
        }
        return PrivilegedRunner.run(cmd);
    }

    /**
     * A one-line summary of an {@code audit2allow} module draft: how many allow
     * rules and tunable hints it contains.
     */
    public static final class ModuleDraft {
        private final int allowRules;
        private final int tunables;

        public ModuleDraft(int allowRules, int tunables) {
            this.allowRules = Math.max(0, allowRules);
            this.tunables = Math.max(0, tunables);
        }

        /** The number of {@code allow} rules drafted. */
        public int getAllowRules() {
            return allowRules;
        }

        /** The number of tunable / {@code setsebool} hints drafted. */
        public int getTunables() {
            return tunables;
        }

        /** True when audit2allow drafted neither a rule nor a tunable. */
        public boolean isEmpty() {
            return allowRules == 0 && tunables == 0;
        }

        /** A one-line label for the review header. */
        public String describe() {
            if (isEmpty()) {
                return "audit2allow drafted no rules (nothing was denied)";
            }
            String base = allowRules + (allowRules == 1 ? " allow rule" : " allow rules");
            if (tunables == 0) {
                return base;
            }
            return base + ", " + tunables + (tunables == 1 ? " tunable" : " tunables");
        }
    }
}
