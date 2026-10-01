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
package org.jdesktop.lg3d.apps.vpn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The AWT-free VPN seam. The desktop ships no tunnel stack of its own, so - exactly
 * like the Security Center handing scanning to ClamAV and the recorder handing
 * capture to {@code ffmpeg} - this backend honestly delegates the tunnel to an
 * installed tool. It resolves which tool to drive ({@code nmcli} for
 * NetworkManager-managed connections, preferred because it already holds the
 * credentials and integrates with the desktop; {@code openvpn} / {@code wg-quick}
 * for a config file the user imported), builds the precise command lines, and
 * parses their terse output into {@link VpnProfile} / {@link VpnStatus} values.
 *
 * <p>Every method here is pure and side-effect free - no process is started - so
 * the whole command / parser table is unit-testable headless against recorded
 * {@code nmcli} output. The thin, guarded {@link ProcessBuilder} launch lives in
 * {@link VpnPanel} and is only ever reached from a user action. A missing tool, or
 * a connect that needs privilege the session does not have, is surfaced by the
 * panel as honest guidance, never as a fake "connected".</p>
 */
public final class VpnBackend {

    /**
     * Tunnel tools this backend knows how to drive, in preference order.
     * {@code nmcli} leads (it reuses NetworkManager's stored VPN connections and
     * secrets and needs no separate config); {@code openvpn} and {@code wg-quick}
     * are the standalone fallbacks for an imported config file.
     */
    public static final List<String> KNOWN_BACKENDS = List.of("nmcli", "openvpn", "wg-quick");

    /** The NetworkManager CLI. */
    public static final String NMCLI = "nmcli";
    /** The standalone OpenVPN client. */
    public static final String OPENVPN = "openvpn";
    /** The WireGuard quick-up tool. */
    public static final String WG_QUICK = "wg-quick";

    private VpnBackend() {
        // no instances
    }

    // ------------------------------------------------------------------
    // Backend resolution
    // ------------------------------------------------------------------

    /**
     * The first candidate tool {@code available} reports present, in
     * {@link #KNOWN_BACKENDS} order.
     *
     * @param available a probe (typically a PATH lookup) - may be null
     * @return the first available backend, or empty when none is
     */
    public static Optional<String> firstAvailableBackend(Predicate<String> available) {
        if (available == null) {
            return Optional.empty();
        }
        for (String backend : KNOWN_BACKENDS) {
            if (available.test(backend)) {
                return Optional.of(backend);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves the backend to use: the {@code preferred} one when it is set and
     * available, otherwise the first available known backend.
     *
     * @param preferred a user-chosen backend (may be null/blank for auto)
     * @param available a probe for whether an executable is on the PATH
     * @return the backend to drive, or empty when none is available
     */
    public static Optional<String> resolveBackend(String preferred, Predicate<String> available) {
        if (preferred != null && !preferred.isBlank()
                && available != null && available.test(preferred.trim())) {
            return Optional.of(preferred.trim());
        }
        return firstAvailableBackend(available);
    }

    // ------------------------------------------------------------------
    // Command builders
    // ------------------------------------------------------------------

    /**
     * The command that lists every NetworkManager connection in terse form
     * ({@code NAME:UUID:TYPE}), so {@link #parseConnections} can keep just the VPN
     * ones.
     *
     * @return the argument list, never null
     */
    public static List<String> listConnectionsCommand() {
        return List.of(NMCLI, "-t", "-f", "NAME,UUID,TYPE", "connection", "show");
    }

    /**
     * The command that lists the currently <em>active</em> connections in terse
     * form ({@code NAME:TYPE:DEVICE}), so {@link #parseStatus} can tell whether a
     * VPN tunnel is up.
     *
     * @return the argument list, never null
     */
    public static List<String> activeConnectionsCommand() {
        return List.of(NMCLI, "-t", "-f", "NAME,TYPE,DEVICE", "connection", "show", "--active");
    }

    /**
     * Builds the connect command for a profile under the given backend.
     * <ul>
     *   <li>{@code nmcli}: {@code nmcli connection up uuid <uuid>} (or by name
     *       when there is no UUID);</li>
     *   <li>{@code openvpn}: {@code openvpn --config <path> --daemon} (needs
     *       privilege, which the panel surfaces);</li>
     *   <li>{@code wg-quick}: {@code wg-quick up <name>} (needs privilege).</li>
     * </ul>
     *
     * @param backend the tool to drive
     * @param profile the profile to connect
     * @return the argument list, never null; empty when a required input is missing
     */
    public static List<String> connectCommand(String backend, VpnProfile profile) {
        if (backend == null || profile == null) {
            return List.of();
        }
        String tool = backend.trim();
        switch (tool) {
            case NMCLI -> {
                String target = profile.getUuid().isBlank()
                        ? profile.getName() : profile.getUuid();
                if (target.isBlank()) {
                    return List.of();
                }
                if (profile.getUuid().isBlank()) {
                    return List.of(NMCLI, "connection", "up", target);
                }
                return List.of(NMCLI, "connection", "up", "uuid", target);
            }
            case OPENVPN -> {
                String path = profile.getConfigPath();
                if (path.isBlank()) {
                    return List.of();
                }
                return List.of(OPENVPN, "--config", path, "--daemon");
            }
            case WG_QUICK -> {
                String name = interfaceName(profile);
                if (name.isBlank()) {
                    return List.of();
                }
                return List.of(WG_QUICK, "up", name);
            }
            default -> {
                return List.of();
            }
        }
    }

    /**
     * Builds the disconnect command for a profile under the given backend. A
     * standalone {@code openvpn} tunnel has no clean CLI teardown (it is a process),
     * so this returns an empty list and the panel stops the tracked process
     * instead.
     *
     * @param backend the tool driving the tunnel
     * @param profile the profile to disconnect
     * @return the argument list, never null; empty when there is nothing to run
     */
    public static List<String> disconnectCommand(String backend, VpnProfile profile) {
        if (backend == null || profile == null) {
            return List.of();
        }
        String tool = backend.trim();
        switch (tool) {
            case NMCLI -> {
                String target = profile.getUuid().isBlank()
                        ? profile.getName() : profile.getUuid();
                if (target.isBlank()) {
                    return List.of();
                }
                if (profile.getUuid().isBlank()) {
                    return List.of(NMCLI, "connection", "down", target);
                }
                return List.of(NMCLI, "connection", "down", "uuid", target);
            }
            case WG_QUICK -> {
                String name = interfaceName(profile);
                if (name.isBlank()) {
                    return List.of();
                }
                return List.of(WG_QUICK, "down", name);
            }
            default -> {
                // openvpn (and unknown): teardown is terminating the process.
                return List.of();
            }
        }
    }

    /**
     * The WireGuard interface name for a profile: the imported config file's base
     * name (without extension), which is how {@code wg-quick} addresses it, falling
     * back to the profile name.
     *
     * @param profile the profile
     * @return the interface name, never null
     */
    public static String interfaceName(VpnProfile profile) {
        if (profile == null) {
            return "";
        }
        String path = profile.getConfigPath();
        if (!path.isBlank()) {
            return stripExtension(baseName(path));
        }
        return profile.getName();
    }

    // ------------------------------------------------------------------
    // Parsers (pure)
    // ------------------------------------------------------------------

    /**
     * Parses {@code nmcli -t -f NAME,UUID,TYPE connection show} output into the
     * VPN profiles it contains, skipping non-VPN connections (ethernet, wi-fi,
     * bridges). Terse fields escape a literal colon as {@code \\:}, so the split
     * respects backslash escapes.
     *
     * @param lines the terse output (may be null)
     * @return the VPN profiles, never null
     */
    public static List<VpnProfile> parseConnections(List<String> lines) {
        List<VpnProfile> profiles = new ArrayList<>();
        if (lines == null) {
            return profiles;
        }
        for (String raw : lines) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String[] fields = splitTerse(raw);
            if (fields.length < 3) {
                continue;
            }
            String name = fields[0];
            String uuid = fields[1];
            String type = fields[2];
            if (!isVpnType(type)) {
                continue;
            }
            VpnProfile profile = new VpnProfile(name, uuid, ConnectionType.fromText(type));
            profile.setBackend(NMCLI);
            profiles.add(profile);
        }
        return profiles;
    }

    /**
     * Parses {@code nmcli -t -f NAME,TYPE,DEVICE connection show --active} output
     * into a {@link VpnStatus}. The first active VPN-typed connection wins; when
     * none is active the status is disconnected.
     *
     * @param lines the terse output (may be null)
     * @return the status, never null
     */
    public static VpnStatus parseStatus(List<String> lines) {
        if (lines == null) {
            return VpnStatus.disconnected("Could not read the connection state.");
        }
        for (String raw : lines) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String[] fields = splitTerse(raw);
            if (fields.length < 2) {
                continue;
            }
            String name = fields[0];
            String type = fields[1];
            String device = (fields.length >= 3) ? fields[2] : "";
            if (isVpnType(type)) {
                return VpnStatus.connected(name, device);
            }
        }
        return VpnStatus.disconnected("No active VPN connection.");
    }

    /**
     * True when an {@code nmcli} TYPE field denotes some kind of VPN.
     *
     * @param type the raw type text (may be null)
     * @return true when it maps to a VPN {@link ConnectionType}
     */
    public static boolean isVpnType(String type) {
        return ConnectionType.fromText(type).isVpn();
    }

    /**
     * Splits one terse {@code nmcli} line on unescaped colons and unescapes each
     * field ({@code \\:} → {@code :}, {@code \\\\} → {@code \\}).
     *
     * @param line the raw line (may be null)
     * @return the fields, never null (empty for a null line)
     */
    public static String[] splitTerse(String line) {
        if (line == null) {
            return new String[0];
        }
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                char next = line.charAt(i + 1);
                if (next == ':' || next == '\\') {
                    current.append(next);
                    i++;
                    continue;
                }
                current.append(c);
            } else if (c == ':') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    /**
     * Extracts the first {@code remote <host> [port]} target from an OpenVPN
     * config's text, for showing the gateway on an imported profile.
     *
     * @param configText the {@code .ovpn} contents (may be null)
     * @return the remote host, or empty when none is declared
     */
    public static Optional<String> parseOvpnRemote(String configText) {
        if (configText == null || configText.isBlank()) {
            return Optional.empty();
        }
        for (String raw : configText.split("\\R")) {
            String line = raw.trim();
            if (!line.toLowerCase(Locale.ROOT).startsWith("remote ")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length >= 2 && !parts[1].isBlank()) {
                return Optional.of(parts[1]);
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Summarises a connect / disconnect run for the status line, preferring a
     * recognisable line from the tool's own output.
     *
     * @param lines    the merged process output (may be null)
     * @param exitCode the process exit code
     * @param connect  true when this was a connect (false for disconnect)
     * @return a short human-readable outcome, never null
     */
    public static String describeResult(List<String> lines, int exitCode, boolean connect) {
        String detail = "";
        if (lines != null) {
            for (String raw : lines) {
                if (raw == null) {
                    continue;
                }
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("successfully activated")
                        || lower.contains("successfully deactivated")
                        || lower.contains("connection successfully")
                        || lower.startsWith("error")
                        || lower.contains("not authorized")
                        || lower.contains("authorization")
                        || lower.contains("permission denied")
                        || lower.contains("failed")) {
                    detail = line;
                    if (lower.startsWith("error") || lower.contains("failed")
                            || lower.contains("not authorized")
                            || lower.contains("permission denied")) {
                        break;
                    }
                }
            }
        }
        if (exitCode == 0) {
            if (!detail.isBlank()) {
                return detail;
            }
            return connect ? "Connected." : "Disconnected.";
        }
        String reason = detail.isBlank() ? ("exit " + exitCode) : detail;
        String hint = connect
                ? " Bringing a tunnel up often needs administrator rights."
                : "";
        return "Could not " + (connect ? "connect" : "disconnect")
                + " (" + reason + ")." + hint;
    }

    /** A short human label for a backend, for the picker. */
    public static String describeBackend(String backend) {
        if (backend == null || backend.isBlank()) {
            return "Auto-detect";
        }
        return switch (backend.trim()) {
            case NMCLI -> "NetworkManager (nmcli)";
            case OPENVPN -> "OpenVPN";
            case WG_QUICK -> "WireGuard (wg-quick)";
            default -> backend.trim();
        };
    }

    /** The final path segment of {@code path}. */
    private static String baseName(String path) {
        String s = path.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        return (slash >= 0 && slash < s.length() - 1) ? s.substring(slash + 1) : s;
    }

    /** {@code name} without a trailing {@code .ext}. */
    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }
}
