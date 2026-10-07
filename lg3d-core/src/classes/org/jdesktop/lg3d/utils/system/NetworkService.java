/*
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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Network-manager abstraction for the LFS/BLFS system managers: it detects which
 * network backend the running system uses (NetworkManager, dhcpcd,
 * systemd-networkd, or a read-only {@code ip} fallback) and maps a logical
 * connection/link operation onto that backend's native command line.
 *
 * <p>This is a thin front-end helper, normative in
 * {@code system-management-contract.md} §4.4. It never re-implements networking
 * logic: {@link #buildCommand} only produces an argument vector (no shell
 * interpolation) that the caller runs through {@link ProcessRunner} (read-only
 * operations, always unprivileged) or {@link PrivilegedRunner} (mutating
 * operations on {@code dhcpcd}/{@code networkd}, escalated with polkit). The
 * detection order mirrors the contract exactly: NetworkManager
 * ({@code nmcli} or {@code NetworkManager} present) &rarr; dhcpcd
 * ({@code dhcpcd} present) &rarr; systemd-networkd ({@code networkctl} present)
 * &rarr; read-only {@code ip}.</p>
 *
 * <p>NetworkManager is special-cased for privileges: {@code nmcli} talks to the
 * NetworkManager daemon over D-Bus and the daemon mediates its own polkit
 * prompt, so {@link #runMutating} runs {@code nmcli} <em>unprivileged</em> and
 * lets NM raise the single prompt (contract §7 item 5, "exactly one polkit
 * prompt"). {@code dhcpcd}/{@code networkd} have no such broker, so their
 * mutating verbs are escalated here via {@link PrivilegedRunner}.</p>
 *
 * <p>The detection, command-building and parsing logic is pure and
 * unit-testable ({@link #detect(Probes)}, {@link #buildCommand},
 * {@link #parseInterfaces}); the live {@link #detect()} probe and the
 * {@code run*}/{@code list*} helpers are thin wrappers that degrade gracefully
 * (an unknown backend or an unsupported operation yields an empty vector rather
 * than throwing).</p>
 */
public final class NetworkService {

    private NetworkService() {
        // no instances
    }

    /** dhcpcd's config file; its interface list is read from here (§3.5). */
    static final String DHCPCD_CONF = "/etc/dhcpcd.conf";

    /** The network backends this desktop knows how to drive. */
    public enum Backend {
        /** NetworkManager ({@code nmcli}). */
        NETWORKMANAGER,
        /** dhcpcd ({@code dhcpcd} / {@code dhcpcdctl} + {@code ip}). */
        DHCPCD,
        /** systemd-networkd ({@code networkctl}). */
        NETWORKD,
        /** No manager: read-only link/address inspection via {@code ip}. */
        IP,
        /** Nothing usable was detected; every operation is unsupported. */
        NONE
    }

    /** A logical network operation, mapped per backend. */
    public enum Operation {
        /** List connections/links (read-only). */
        LIST,
        /** Show link/address state (read-only). */
        STATUS,
        /** Bring a connection/link up (mutating). */
        UP,
        /** Bring a connection/link down (mutating). */
        DOWN
    }

    /**
     * The PATH facts {@link #detect(Probes)} needs. Splitting these out keeps
     * detection unit-testable without a real network manager present.
     */
    public static final class Probes {
        private final boolean nmcli;
        private final boolean networkManagerBin;
        private final boolean dhcpcd;
        private final boolean networkctl;
        private final boolean ip;

        public Probes(boolean nmcli, boolean networkManagerBin,
                      boolean dhcpcd, boolean networkctl, boolean ip) {
            this.nmcli = nmcli;
            this.networkManagerBin = networkManagerBin;
            this.dhcpcd = dhcpcd;
            this.networkctl = networkctl;
            this.ip = ip;
        }

        boolean hasNmcli() {
            return nmcli;
        }

        boolean hasNetworkManagerBin() {
            return networkManagerBin;
        }

        boolean hasDhcpcd() {
            return dhcpcd;
        }

        boolean hasNetworkctl() {
            return networkctl;
        }

        boolean hasIp() {
            return ip;
        }
    }

    /**
     * Pure detection from a set of probes, in the contract's §4.4 order:
     * NetworkManager (either {@code nmcli} or the {@code NetworkManager} daemon
     * binary), then dhcpcd, then systemd-networkd, then a read-only {@code ip}
     * fallback. Returns {@link Backend#NONE} only when even {@code ip} is absent
     * (nothing to show).
     */
    public static Backend detect(Probes p) {
        if (p == null) {
            return Backend.NONE;
        }
        if (p.hasNmcli() || p.hasNetworkManagerBin()) {
            return Backend.NETWORKMANAGER;
        }
        if (p.hasDhcpcd()) {
            return Backend.DHCPCD;
        }
        if (p.hasNetworkctl()) {
            return Backend.NETWORKD;
        }
        if (p.hasIp()) {
            return Backend.IP;
        }
        return Backend.NONE;
    }

    /**
     * Live detection against this host: probes {@code PATH} for each backend
     * tool, then defers to {@link #detect(Probes)}.
     */
    public static Backend detect() {
        Probes p = new Probes(
                ProcessRunner.isAvailable("nmcli"),
                ProcessRunner.isAvailable("NetworkManager"),
                ProcessRunner.isAvailable("dhcpcd"),
                ProcessRunner.isAvailable("networkctl"),
                ProcessRunner.isAvailable("ip"));
        return detect(p);
    }

    /** True if {@code op} changes system state (brings a link up/down). */
    public static boolean isMutating(Operation op) {
        if (op == null) {
            return false;
        }
        switch (op) {
            case UP:
            case DOWN:
                return true;
            case LIST:
            case STATUS:
            default:
                return false;
        }
    }

    /**
     * True if {@code backend} supports {@code op}. {@link Backend#NONE} supports
     * nothing; the read-only {@link Backend#IP} fallback supports the read verbs
     * but never a mutating one.
     */
    public static boolean isSupported(Backend backend, Operation op) {
        if (backend == null || op == null || backend == Backend.NONE) {
            return false;
        }
        if (backend == Backend.IP && isMutating(op)) {
            return false;
        }
        return true;
    }

    /**
     * True if {@code op} needs an interface/connection {@code target} on
     * {@code backend}. {@link Operation#LIST} never does; {@link Operation#STATUS}
     * does for every backend except NetworkManager ({@code nmcli device status}
     * reports all devices at once); up/down always do.
     */
    public static boolean requiresTarget(Backend backend, Operation op) {
        if (backend == null || op == null) {
            return false;
        }
        switch (op) {
            case LIST:
                return false;
            case STATUS:
                return backend != Backend.NETWORKMANAGER;
            case UP:
            case DOWN:
                return true;
            default:
                return false;
        }
    }

    /**
     * True if a mutating {@code op} on {@code backend} must be escalated with
     * polkit here. Only {@code dhcpcd}/{@code networkd} do: NetworkManager
     * mediates its own prompt over D-Bus, so {@code nmcli} runs unprivileged.
     */
    public static boolean needsPrivileges(Backend backend, Operation op) {
        return isMutating(op)
                && (backend == Backend.DHCPCD || backend == Backend.NETWORKD);
    }

    /**
     * Builds the argument vector for {@code op} on {@code backend}, targeting
     * {@code target} (an interface or connection name). Returns an empty list
     * when the operation is unsupported or a required target is missing; the
     * result never contains shell metacharacters and is meant to be handed
     * straight to {@link ProcessRunner} / {@link PrivilegedRunner}.
     *
     * <p>dhcpcd's {@link Operation#LIST} is <em>file-based</em>: the contract
     * sources the interface list from {@code /etc/dhcpcd.conf} (a read-only
     * state file, §3.5) rather than a CLI, so this returns an empty vector for
     * that one case and {@link #listInterfaces} reads the config instead.</p>
     */
    public static List<String> buildCommand(Backend backend, Operation op, String target) {
        if (backend == null || op == null || !isSupported(backend, op)) {
            return Collections.emptyList();
        }
        boolean needTarget = requiresTarget(backend, op);
        String t = needTarget ? trimToNull(target) : null;
        if (needTarget && t == null) {
            return Collections.emptyList();
        }

        List<String> c = new ArrayList<>();
        switch (backend) {
            case NETWORKMANAGER:
                networkManagerCommand(c, op, t);
                break;
            case DHCPCD:
                dhcpcdCommand(c, op, t);
                break;
            case NETWORKD:
                networkdCommand(c, op, t);
                break;
            case IP:
                ipCommand(c, op, t);
                break;
            case NONE:
            default:
                return Collections.emptyList();
        }
        return c;
    }

    private static void networkManagerCommand(List<String> c, Operation op, String t) {
        switch (op) {
            case LIST:
                Collections.addAll(c, "nmcli", "-t", "-f", "NAME,TYPE,DEVICE",
                        "connection", "show");
                break;
            case STATUS:
                Collections.addAll(c, "nmcli", "device", "status");
                break;
            case UP:
                Collections.addAll(c, "nmcli", "connection", "up", "id", t);
                break;
            case DOWN:
                Collections.addAll(c, "nmcli", "connection", "down", "id", t);
                break;
            default:
                break;
        }
    }

    private static void dhcpcdCommand(List<String> c, Operation op, String t) {
        switch (op) {
            case LIST:
                // File-based: /etc/dhcpcd.conf is parsed by listInterfaces(); no
                // CLI vector exists for the dhcpcd interface list.
                break;
            case STATUS:
                Collections.addAll(c, "ip", "addr", "show", t);
                break;
            case UP:
                Collections.addAll(c, "dhcpcd", t);
                break;
            case DOWN:
                Collections.addAll(c, "dhcpcdctl", "-k", t);
                break;
            default:
                break;
        }
    }

    private static void networkdCommand(List<String> c, Operation op, String t) {
        switch (op) {
            case LIST:
                Collections.addAll(c, "networkctl", "list");
                break;
            case STATUS:
                Collections.addAll(c, "networkctl", "status", t);
                break;
            case UP:
                Collections.addAll(c, "networkctl", "up", t);
                break;
            case DOWN:
                Collections.addAll(c, "networkctl", "down", t);
                break;
            default:
                break;
        }
    }

    private static void ipCommand(List<String> c, Operation op, String t) {
        switch (op) {
            case LIST:
                Collections.addAll(c, "ip", "-o", "link", "show");
                break;
            case STATUS:
                Collections.addAll(c, "ip", "addr", "show", t);
                break;
            case UP:
            case DOWN:
            default:
                // Read-only fallback; guarded by isSupported().
                break;
        }
    }

    // ------------------------------------------------------------------
    // Thin execution helpers (kept minimal; the panel owns the UI wiring).

    /**
     * Runs a read-only operation (LIST or STATUS) unprivileged through
     * {@link ProcessRunner} (contract §4.4: "MUST run read-only probes
     * unprivileged"). Returns a not-started {@link ProcessRunner.Result} when the
     * operation is unsupported or mutating.
     */
    public static ProcessRunner.Result runRead(Backend backend, Operation op, String target) {
        if (isMutating(op)) {
            return new ProcessRunner.Result(false, -1, "", "operation is mutating; use runMutating");
        }
        List<String> cmd = buildCommand(backend, op, target);
        if (cmd.isEmpty()) {
            return new ProcessRunner.Result(false, -1, "", "unsupported operation");
        }
        return ProcessRunner.run(cmd);
    }

    /**
     * Runs a mutating operation (UP/DOWN). On {@code dhcpcd}/{@code networkd} it
     * is escalated with polkit through {@link PrivilegedRunner}; on
     * NetworkManager it runs {@code nmcli} unprivileged and lets the daemon raise
     * its own single prompt. Returns an ERROR result when the operation is
     * unsupported or read-only.
     */
    public static PrivilegedRunner.PrivilegedResult runMutating(
            Backend backend, Operation op, String target) {
        if (!isMutating(op)) {
            return new PrivilegedRunner.PrivilegedResult(
                    PrivilegedRunner.Status.ERROR, "operation is not mutating; use runRead", -1, "");
        }
        List<String> cmd = buildCommand(backend, op, target);
        if (cmd.isEmpty()) {
            return new PrivilegedRunner.PrivilegedResult(
                    PrivilegedRunner.Status.ERROR, "unsupported operation", -1, "");
        }
        if (needsPrivileges(backend, op)) {
            return PrivilegedRunner.run(cmd);
        }
        // NetworkManager: run unprivileged, wrap the plain result so the panel
        // has one uniform type to render.
        ProcessRunner.Result r = ProcessRunner.run(cmd);
        PrivilegedRunner.Status st = r.isSuccess()
                ? PrivilegedRunner.Status.SUCCESS
                : PrivilegedRunner.Status.ERROR;
        String msg = r.isSuccess()
                ? ""
                : (r.getStderr() == null || r.getStderr().trim().isEmpty()
                        ? r.getMessage() : r.getStderr().trim());
        String out = r.getStdout() == null ? "" : r.getStdout();
        return new PrivilegedRunner.PrivilegedResult(st, msg, r.getExitCode(), out);
    }

    /**
     * Lists the connections/links for {@code backend}, read-only and
     * unprivileged. For NetworkManager/networkd/ip this runs the LIST command and
     * parses its stdout; for dhcpcd it reads {@code /etc/dhcpcd.conf} (a
     * read-only state file). Returns an empty list when the backend is
     * {@link Backend#NONE}, absent or errors.
     */
    public static List<NetInterface> listInterfaces(Backend backend) {
        if (backend == null || backend == Backend.NONE) {
            return Collections.emptyList();
        }
        if (backend == Backend.DHCPCD) {
            return parseInterfaces(backend, readDhcpcdConf());
        }
        ProcessRunner.Result r = runRead(backend, Operation.LIST, null);
        if (!r.isSuccess()) {
            return Collections.emptyList();
        }
        return parseInterfaces(backend, r.getStdout());
    }

    /** Reads {@code /etc/dhcpcd.conf} read-only; {@code ""} when unreadable. */
    public static String readDhcpcdConf() {
        try {
            return new String(Files.readAllBytes(Paths.get(DHCPCD_CONF)), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Pure output parsing (headless-testable; no process is spawned).

    /**
     * Parses a backend's {@link Operation#LIST} output into {@link NetInterface}
     * values. The exact shape differs by backend:
     * <ul>
     *   <li>NetworkManager {@code nmcli -t -f NAME,TYPE,DEVICE connection show}:
     *       one colon-separated connection per line (the device field is empty
     *       for an inactive connection).</li>
     *   <li>networkd {@code networkctl list}: a columnar table with an
     *       {@code IDX LINK TYPE OPERATIONAL SETUP} header and an
     *       {@code N links listed.} footer, both skipped.</li>
     *   <li>ip {@code ip -o link show}: {@code N: name[@if]: <FLAGS> ... state
     *       S ...}, one link per line.</li>
     *   <li>dhcpcd: {@code out} is the text of {@code /etc/dhcpcd.conf}; the
     *       {@code interface} / {@code allowinterfaces} names are extracted.</li>
     * </ul>
     * Blank and malformed lines are skipped; parsing never throws.
     */
    public static List<NetInterface> parseInterfaces(Backend backend, String out) {
        if (backend == null) {
            return new ArrayList<>();
        }
        switch (backend) {
            case NETWORKMANAGER:
                return parseNmcli(out);
            case NETWORKD:
                return parseNetworkctlList(out);
            case IP:
                return parseIpLink(out);
            case DHCPCD:
                return parseDhcpcdConf(out);
            case NONE:
            default:
                return new ArrayList<>();
        }
    }

    /** Parses {@code nmcli -t -f NAME,TYPE,DEVICE connection show} output. */
    static List<NetInterface> parseNmcli(String nmcliT) {
        List<NetInterface> out = new ArrayList<>();
        if (nmcliT == null) {
            return out;
        }
        for (String raw : nmcliT.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> f = splitFields(line);
            if (f.size() < 3) {
                continue;
            }
            String name = f.get(0);
            if (name.isEmpty()) {
                continue;
            }
            String type = f.get(1);
            String device = f.get(2);
            boolean active = !device.isEmpty();
            out.add(new NetInterface(name, type, active ? device : "inactive", active));
        }
        return out;
    }

    /**
     * Splits a {@code nmcli -t} line on unescaped colons, unescaping a literal
     * colon that {@code nmcli} writes as backslash-colon inside a field so a
     * connection name containing a colon is not mistaken for a separator.
     * (Character-literal scanner; mirrors {@code NetworkConnections.splitFields}.)
     */
    static List<String> splitFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length() && line.charAt(i + 1) == ':') {
                cur.append(':');
                i++;
            } else if (c == ':') {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields;
    }

    /** Matches an {@code ip -o link show} record: idx, name, flags, rest. */
    private static final Pattern IP_LINK =
            Pattern.compile("^(\\d+):\\s+([^:@\\s]+)(?:@\\S+)?:\\s+<([^>]*)>(.*)$");

    /** Matches the {@code state <S>} token inside an {@code ip} link record. */
    private static final Pattern IP_STATE = Pattern.compile("\\bstate\\s+(\\S+)");

    /** Matches {@code networkctl list}'s trailing "N link(s) listed." summary. */
    private static final Pattern NETWORKCTL_FOOTER =
            Pattern.compile("^\\d+\\s+links?\\s+listed", Pattern.CASE_INSENSITIVE);

    /** Parses {@code ip -o link show} output (one link per line). */
    static List<NetInterface> parseIpLink(String out) {
        List<NetInterface> list = new ArrayList<>();
        if (out == null) {
            return list;
        }
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = IP_LINK.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String name = m.group(2);
            String flags = m.group(3) == null ? "" : m.group(3);
            String rest = m.group(4) == null ? "" : m.group(4);
            String state = "";
            Matcher sm = IP_STATE.matcher(rest);
            if (sm.find()) {
                state = sm.group(1);
            }
            boolean active = flags.contains("LOWER_UP");
            if (state.isEmpty()) {
                state = active ? "up" : "down";
            }
            list.add(new NetInterface(name, "link", state, active));
        }
        return list;
    }

    /** Parses {@code networkctl list} output (columnar table + header/footer). */
    static List<NetInterface> parseNetworkctlList(String out) {
        List<NetInterface> list = new ArrayList<>();
        if (out == null) {
            return list;
        }
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            // Skip the trailing "N links listed." summary: it begins with a
            // number, so the IDX check below would otherwise accept it as a row.
            if (NETWORKCTL_FOOTER.matcher(line).find()) {
                continue;
            }
            String[] f = line.split("\\s+");
            // A data row always begins with a numeric IDX; the header ("IDX LINK
            // TYPE OPERATIONAL SETUP") does not.
            if (!isInt(f[0])) {
                continue;
            }
            if (f.length < 2) {
                continue;
            }
            String name = f[1];
            String type = f.length >= 3 ? f[2] : "";
            String operational = f.length >= 4 ? f[3] : "";
            String setup = f.length >= 5 ? f[4] : "";
            String state = setup.isEmpty()
                    ? operational
                    : (operational + " " + setup).trim();
            boolean active = setup.equalsIgnoreCase("configured")
                    || operational.equalsIgnoreCase("routable")
                    || operational.equalsIgnoreCase("degraded");
            list.add(new NetInterface(name, type, state, active));
        }
        return list;
    }

    /**
     * Extracts the interface names configured in {@code /etc/dhcpcd.conf}: the
     * operands of {@code interface} and {@code allowinterfaces} directives, with
     * {@code #} comments stripped and duplicates removed (order preserved).
     */
    static List<NetInterface> parseDhcpcdConf(String conf) {
        List<NetInterface> list = new ArrayList<>();
        if (conf == null) {
            return list;
        }
        Set<String> names = new LinkedHashSet<>();
        for (String raw : conf.split("\n")) {
            String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\\s+");
            String kw = f[0].toLowerCase();
            if (!kw.equals("interface") && !kw.equals("allowinterfaces")) {
                continue;
            }
            for (int i = 1; i < f.length; i++) {
                if (!f[i].isEmpty()) {
                    names.add(f[i]);
                }
            }
        }
        for (String n : names) {
            list.add(new NetInterface(n, "dhcpcd", "", false));
        }
        return list;
    }

    /** Drops a trailing {@code #} comment (dhcpcd.conf syntax). */
    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static boolean isInt(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * One parsed network row: a connection or link {@code name}, its
     * {@code type}, a best-effort {@code state} string (may be empty) and an
     * {@code active} flag. Display text comes from {@link #toString()}.
     */
    public static final class NetInterface {
        private final String name;
        private final String type;
        private final String state;
        private final boolean active;

        public NetInterface(String name, String type, String state, boolean active) {
            this.name = (name == null) ? "" : name;
            this.type = (type == null) ? "" : type;
            this.state = (state == null) ? "" : state;
            this.active = active;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public String getState() {
            return state;
        }

        public boolean isActive() {
            return active;
        }

        /** A one-line label for a list cell: name, type and state/active. */
        @Override
        public String toString() {
            String s = state.isEmpty() ? (active ? "active" : "inactive") : state;
            return name + "  (" + type + ", " + s + ")";
        }
    }
}
