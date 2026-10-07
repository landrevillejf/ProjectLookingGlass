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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Init-system abstraction for the LFS/BLFS system managers: it detects which
 * service supervisor the running system uses (systemd, openrc, runit, s6 or
 * plain sysvinit) and maps a logical service operation onto that supervisor's
 * native command line.
 *
 * <p>This is a thin front-end helper, normative in
 * {@code system-management-contract.md} §4.1. It never re-implements service
 * logic: {@link #buildCommand} only produces an argument vector (no shell
 * interpolation) that the caller runs through {@link ProcessRunner} (read-only
 * operations) or {@link PrivilegedRunner} (mutating operations, escalated with
 * polkit). The detection and command mapping mirror the builder's
 * {@code lfs/06b-service-management.sh} abstraction layer verbatim.</p>
 *
 * <p>The detection and command-building logic is pure and unit-testable
 * ({@link #detect(Probes)}, {@link #buildCommand}); the live {@link #detect()}
 * probe and the {@code run*} helpers are thin wrappers that degrade gracefully
 * (an unknown init or an unsupported operation yields an empty vector rather
 * than throwing).</p>
 */
public final class InitSystemService {

    private InitSystemService() {
        // no instances
    }

    /** The service supervisors this desktop knows how to drive. */
    public enum InitSystem {
        /** systemd ({@code systemctl}). */
        SYSTEMD,
        /** OpenRC ({@code rc-service} / {@code rc-update}). */
        OPENRC,
        /** runit ({@code sv}). */
        RUNIT,
        /** s6 ({@code s6-svc} / {@code s6-svstat}). */
        S6,
        /** classic SysV init ({@code /etc/init.d/*}). */
        SYSVINIT,
        /** No supervisor could be detected; every operation is unsupported. */
        UNKNOWN
    }

    /** A logical service operation, mapped per init system. */
    public enum Operation {
        /** Start the service (mutating). */
        START,
        /** Stop the service (mutating). */
        STOP,
        /** Restart the service (mutating). */
        RESTART,
        /** Query the service state (read-only). */
        STATUS,
        /** Enable the service at boot (mutating). */
        ENABLE,
        /** Disable the service at boot (mutating). */
        DISABLE,
        /** List the known services (read-only; ignores the service name). */
        LIST
    }

    /**
     * The filesystem/PATH facts {@link #detect(Probes)} needs. Splitting these
     * out keeps detection unit-testable without a real init system present.
     */
    public static final class Probes {
        private final boolean systemctl;
        private final boolean systemdDir;
        private final boolean rcService;
        private final boolean initdDir;
        private final boolean sv;
        private final boolean etcSv;
        private final boolean s6Svscan;
        private final boolean etcS6;

        public Probes(boolean systemctl, boolean systemdDir,
                      boolean rcService, boolean initdDir,
                      boolean sv, boolean etcSv,
                      boolean s6Svscan, boolean etcS6) {
            this.systemctl = systemctl;
            this.systemdDir = systemdDir;
            this.rcService = rcService;
            this.initdDir = initdDir;
            this.sv = sv;
            this.etcSv = etcSv;
            this.s6Svscan = s6Svscan;
            this.etcS6 = etcS6;
        }

        boolean hasSystemctl() {
            return systemctl;
        }

        boolean hasSystemdDir() {
            return systemdDir;
        }

        boolean hasRcService() {
            return rcService;
        }

        boolean hasInitdDir() {
            return initdDir;
        }

        boolean hasSv() {
            return sv;
        }

        boolean hasEtcSv() {
            return etcSv;
        }

        boolean hasS6Svscan() {
            return s6Svscan;
        }

        boolean hasEtcS6() {
            return etcS6;
        }
    }

    /**
     * Pure detection from a set of probes, in the same order as the builder's
     * {@code _detect_init}: systemd, then openrc, then runit, then s6, then a
     * sysvinit fallback. Returns {@link InitSystem#UNKNOWN} only when even the
     * sysvinit {@code /etc/init.d} directory is absent (nothing to drive).
     */
    public static InitSystem detect(Probes p) {
        if (p == null) {
            return InitSystem.UNKNOWN;
        }
        if (p.hasSystemctl() && p.hasSystemdDir()) {
            return InitSystem.SYSTEMD;
        }
        if (p.hasRcService() && p.hasInitdDir()) {
            return InitSystem.OPENRC;
        }
        if (p.hasSv() && p.hasEtcSv()) {
            return InitSystem.RUNIT;
        }
        if (p.hasS6Svscan() && p.hasEtcS6()) {
            return InitSystem.S6;
        }
        if (p.hasInitdDir()) {
            return InitSystem.SYSVINIT;
        }
        return InitSystem.UNKNOWN;
    }

    /**
     * Live detection against this host: probes {@code PATH} for each supervisor
     * tool and the filesystem for its service directory, then defers to
     * {@link #detect(Probes)}.
     */
    public static InitSystem detect() {
        Probes p = new Probes(
                ProcessRunner.isAvailable("systemctl"), isDir("/usr/lib/systemd"),
                ProcessRunner.isAvailable("rc-service"), isDir("/etc/init.d"),
                ProcessRunner.isAvailable("sv"), isDir("/etc/sv"),
                ProcessRunner.isAvailable("s6-svscan"), isDir("/etc/s6"));
        return detect(p);
    }

    private static boolean isDir(String path) {
        File f = new File(path);
        return f.isDirectory();
    }

    /** True if {@code op} changes system state and therefore needs polkit. */
    public static boolean isMutating(Operation op) {
        if (op == null) {
            return false;
        }
        switch (op) {
            case START:
            case STOP:
            case RESTART:
            case ENABLE:
            case DISABLE:
                return true;
            case STATUS:
            case LIST:
            default:
                return false;
        }
    }

    /**
     * True if {@code init} supports {@code op}. SysV init has no enable/disable
     * (the builder reports "not supported"), and {@link InitSystem#UNKNOWN}
     * supports nothing.
     */
    public static boolean isSupported(InitSystem init, Operation op) {
        if (init == null || op == null || init == InitSystem.UNKNOWN) {
            return false;
        }
        if (init == InitSystem.SYSVINIT
                && (op == Operation.ENABLE || op == Operation.DISABLE)) {
            return false;
        }
        return true;
    }

    /**
     * Builds the argument vector for {@code op} on {@code init}, targeting
     * {@code service}. Returns an empty list when the operation is unsupported
     * or the arguments are invalid; the result never contains shell
     * metacharacters and is meant to be handed straight to
     * {@link ProcessRunner} / {@link PrivilegedRunner}.
     *
     * <p>{@link Operation#LIST} ignores {@code service}.</p>
     */
    public static List<String> buildCommand(InitSystem init, Operation op, String service) {
        if (init == null || op == null || !isSupported(init, op)) {
            return Collections.emptyList();
        }
        boolean needsName = op != Operation.LIST;
        if (needsName && (service == null || service.trim().isEmpty())) {
            return Collections.emptyList();
        }
        String svc = needsName ? service.trim() : null;

        List<String> c = new ArrayList<>();
        switch (init) {
            case SYSTEMD:
                systemdCommand(c, op, svc);
                break;
            case OPENRC:
                openrcCommand(c, op, svc);
                break;
            case RUNIT:
                runitCommand(c, op, svc);
                break;
            case S6:
                s6Command(c, op, svc);
                break;
            case SYSVINIT:
                sysvinitCommand(c, op, svc);
                break;
            case UNKNOWN:
            default:
                return Collections.emptyList();
        }
        return c;
    }

    private static void systemdCommand(List<String> c, Operation op, String svc) {
        switch (op) {
            case START:
                Collections.addAll(c, "systemctl", "start", svc);
                break;
            case STOP:
                Collections.addAll(c, "systemctl", "stop", svc);
                break;
            case RESTART:
                Collections.addAll(c, "systemctl", "restart", svc);
                break;
            case STATUS:
                Collections.addAll(c, "systemctl", "status", svc);
                break;
            case ENABLE:
                Collections.addAll(c, "systemctl", "enable", svc);
                break;
            case DISABLE:
                Collections.addAll(c, "systemctl", "disable", svc);
                break;
            case LIST:
                Collections.addAll(c, "systemctl", "list-units", "--type=service",
                        "--all", "--no-legend", "--no-pager");
                break;
            default:
                break;
        }
    }

    private static void openrcCommand(List<String> c, Operation op, String svc) {
        switch (op) {
            case START:
                Collections.addAll(c, "rc-service", svc, "start");
                break;
            case STOP:
                Collections.addAll(c, "rc-service", svc, "stop");
                break;
            case RESTART:
                Collections.addAll(c, "rc-service", svc, "restart");
                break;
            case STATUS:
                Collections.addAll(c, "rc-service", svc, "status");
                break;
            case ENABLE:
                Collections.addAll(c, "rc-update", "add", svc, "default");
                break;
            case DISABLE:
                Collections.addAll(c, "rc-update", "del", svc);
                break;
            case LIST:
                Collections.addAll(c, "rc-status", "--all");
                break;
            default:
                break;
        }
    }

    private static void runitCommand(List<String> c, Operation op, String svc) {
        switch (op) {
            case START:
                Collections.addAll(c, "sv", "up", svc);
                break;
            case STOP:
                Collections.addAll(c, "sv", "down", svc);
                break;
            case RESTART:
                Collections.addAll(c, "sv", "restart", svc);
                break;
            case STATUS:
                Collections.addAll(c, "sv", "status", svc);
                break;
            case ENABLE:
                Collections.addAll(c, "ln", "-sfn", "/etc/sv/" + svc, "/var/service/" + svc);
                break;
            case DISABLE:
                Collections.addAll(c, "rm", "-f", "/var/service/" + svc);
                break;
            case LIST:
                Collections.addAll(c, "ls", "/etc/sv");
                break;
            default:
                break;
        }
    }

    private static void s6Command(List<String> c, Operation op, String svc) {
        switch (op) {
            case START:
                Collections.addAll(c, "s6-svc", "-u", "/etc/s6/sv/" + svc);
                break;
            case STOP:
                Collections.addAll(c, "s6-svc", "-d", "/etc/s6/sv/" + svc);
                break;
            case RESTART:
                Collections.addAll(c, "s6-svc", "-r", "/etc/s6/sv/" + svc);
                break;
            case STATUS:
                Collections.addAll(c, "s6-svstat", "/etc/s6/sv/" + svc);
                break;
            case ENABLE:
                Collections.addAll(c, "ln", "-sfn", "/etc/s6/sv/" + svc, "/etc/s6/current/" + svc);
                break;
            case DISABLE:
                Collections.addAll(c, "rm", "-f", "/etc/s6/current/" + svc);
                break;
            case LIST:
                Collections.addAll(c, "ls", "/etc/s6/sv");
                break;
            default:
                break;
        }
    }

    private static void sysvinitCommand(List<String> c, Operation op, String svc) {
        switch (op) {
            case START:
                Collections.addAll(c, "/etc/init.d/" + svc, "start");
                break;
            case STOP:
                Collections.addAll(c, "/etc/init.d/" + svc, "stop");
                break;
            case RESTART:
                Collections.addAll(c, "/etc/init.d/" + svc, "restart");
                break;
            case STATUS:
                Collections.addAll(c, "/etc/init.d/" + svc, "status");
                break;
            case LIST:
                Collections.addAll(c, "ls", "/etc/init.d");
                break;
            case ENABLE:
            case DISABLE:
            default:
                // Not supported for sysvinit (guarded by isSupported()).
                break;
        }
    }

    // ------------------------------------------------------------------
    // Thin execution helpers (kept minimal; the panel owns the UI wiring).

    /**
     * Runs a read-only operation (STATUS or LIST) unprivileged through
     * {@link ProcessRunner}. Returns a not-started {@link ProcessRunner.Result}
     * when the operation is unsupported or mutating.
     */
    public static ProcessRunner.Result runRead(InitSystem init, Operation op, String service) {
        if (isMutating(op)) {
            return new ProcessRunner.Result(false, -1, "", "operation is mutating; use runMutating");
        }
        List<String> cmd = buildCommand(init, op, service);
        if (cmd.isEmpty()) {
            return new ProcessRunner.Result(false, -1, "", "unsupported operation");
        }
        return ProcessRunner.run(cmd);
    }

    /**
     * Runs a mutating operation (START/STOP/RESTART/ENABLE/DISABLE) with polkit
     * escalation through {@link PrivilegedRunner}. Returns an ERROR result when
     * the operation is unsupported or read-only.
     */
    public static PrivilegedRunner.PrivilegedResult runMutating(
            InitSystem init, Operation op, String service) {
        if (!isMutating(op)) {
            return new PrivilegedRunner.PrivilegedResult(
                    PrivilegedRunner.Status.ERROR, "operation is not mutating; use runRead", -1, "");
        }
        List<String> cmd = buildCommand(init, op, service);
        if (cmd.isEmpty()) {
            return new PrivilegedRunner.PrivilegedResult(
                    PrivilegedRunner.Status.ERROR, "unsupported operation", -1, "");
        }
        return PrivilegedRunner.run(cmd);
    }

    /**
     * Lists the known services for {@code init}, read-only. Returns an empty
     * list when listing is unsupported or the supervisor is absent. For the
     * directory-enumeration backends (runit/s6/sysvinit) this is the trimmed
     * {@code ls} output; for systemd/openrc it is the raw status lines the panel
     * can render or further parse.
     */
    public static List<String> listServices(InitSystem init) {
        ProcessRunner.Result r = runRead(init, Operation.LIST, null);
        if (!r.isSuccess()) {
            return Collections.emptyList();
        }
        return r.getStdoutLines();
    }

    /**
     * Lists the known services for {@code init} and parses each raw output line
     * into a {@link ServiceEntry}. Read-only; returns an empty list when listing
     * is unsupported or the supervisor is absent.
     */
    public static List<ServiceEntry> listServiceEntries(InitSystem init) {
        return parseServiceList(init, listServices(init));
    }

    // ------------------------------------------------------------------
    // Pure output parsing (headless-testable; no process is spawned).

    /**
     * Parses the raw stdout lines of a {@link Operation#LIST} command into
     * {@link ServiceEntry} values, per init system. The exact shape differs by
     * supervisor:
     * <ul>
     *   <li>systemd {@code list-units --no-legend}: {@code UNIT LOAD ACTIVE SUB
     *       DESCRIPTION} - the name is the unit minus its {@code .service}
     *       suffix and the state is {@code ACTIVE SUB}.</li>
     *   <li>openrc {@code rc-status --all}: {@code name | state} - lines without
     *       a {@code |} (runlevel headers) are skipped.</li>
     *   <li>runit / s6 / sysvinit {@code ls}: each line is a bare service name
     *       with no state.</li>
     * </ul>
     * Blank and {@code null} lines are dropped; parsing never throws.
     */
    public static List<ServiceEntry> parseServiceList(InitSystem init, List<String> rawLines) {
        List<ServiceEntry> out = new ArrayList<>();
        if (init == null || rawLines == null) {
            return out;
        }
        for (String line : rawLines) {
            if (line == null) {
                continue;
            }
            String t = stripBullet(line.trim());
            if (t.isEmpty()) {
                continue;
            }
            ServiceEntry e = parseLine(init, t);
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    /** Removes the leading {@code ●} / {@code *} marker systemctl prints. */
    private static String stripBullet(String s) {
        String t = s;
        while (!t.isEmpty()) {
            char c = t.charAt(0);
            if (c == '\u25cf' || c == '*' || c == '\u00b7') {
                t = t.substring(1).trim();
            } else {
                break;
            }
        }
        return t;
    }

    private static ServiceEntry parseLine(InitSystem init, String line) {
        switch (init) {
            case SYSTEMD:
                return parseSystemdUnit(line);
            case OPENRC:
                return parseOpenrcLine(line);
            case RUNIT:
            case S6:
            case SYSVINIT:
                return new ServiceEntry(line, "", line);
            case UNKNOWN:
            default:
                return null;
        }
    }

    private static ServiceEntry parseSystemdUnit(String line) {
        String[] f = line.split("\\s+");
        if (f.length == 0 || f[0].isEmpty()) {
            return null;
        }
        String unit = f[0];
        String name = unit.endsWith(".service")
                ? unit.substring(0, unit.length() - ".service".length())
                : unit;
        // UNIT LOAD ACTIVE SUB DESCRIPTION...
        String state;
        String desc;
        if (f.length >= 4) {
            state = f[2] + " " + f[3];
            desc = String.join(" ", java.util.Arrays.copyOfRange(f, 4, f.length));
        } else if (f.length == 3) {
            state = f[2];
            desc = "";
        } else {
            state = "";
            desc = "";
        }
        return new ServiceEntry(name, state, desc);
    }

    private static ServiceEntry parseOpenrcLine(String line) {
        int bar = line.indexOf('|');
        if (bar < 0) {
            // A runlevel header ("Runlevel: default") or noise; not a service.
            return null;
        }
        String name = line.substring(0, bar).trim();
        String state = line.substring(bar + 1).trim();
        if (name.isEmpty()) {
            return null;
        }
        return new ServiceEntry(name, state, line);
    }

    /**
     * One parsed service row: its name, a best-effort state string (may be empty
     * for the {@code ls}-based backends) and a detail/description for display.
     */
    public static final class ServiceEntry {
        private final String name;
        private final String state;
        private final String detail;

        public ServiceEntry(String name, String state, String detail) {
            this.name = (name == null) ? "" : name;
            this.state = (state == null) ? "" : state;
            this.detail = (detail == null) ? "" : detail;
        }

        public String getName() {
            return name;
        }

        public String getState() {
            return state;
        }

        public String getDetail() {
            return detail;
        }

        /** A one-line label for a list cell: the name plus its state, if any. */
        @Override
        public String toString() {
            return state.isEmpty() ? name : (name + "  \u2014  " + state);
        }
    }
}
