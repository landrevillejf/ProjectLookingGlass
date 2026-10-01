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
package org.jdesktop.lg3d.apps.securitycenter;

import java.util.List;
import java.util.Locale;

/**
 * The AWT-free host-security seam for the Security Overview. It builds the
 * command lines that probe the machine's mandatory-access-control and firewall
 * posture and parses their output into small enums, so the whole table is
 * unit-testable headless. Like {@link AntivirusBackend}, nothing here starts a
 * process; {@link SecurityCenterPanel} runs the (fast) commands on a background
 * thread only when the user opens or refreshes the overview.
 *
 * <p>SELinux is read with {@code getenforce} (Enforcing / Permissive /
 * Disabled). The firewall is read with {@code firewall-cmd --state}; that call
 * needs PolicyKit authorization, so on a session without it the probe reports
 * {@link FirewallState#UNKNOWN} rather than guessing. A missing tool is reported
 * by the panel as {@code ABSENT}, never as a silent "secure".</p>
 */
public final class SecurityProbe {

    /** The SELinux enforcement mode reported by {@code getenforce}. */
    public enum SelinuxMode {
        /** Policy is actively enforced. */
        ENFORCING,
        /** Policy violations are logged but allowed. */
        PERMISSIVE,
        /** SELinux is turned off. */
        DISABLED,
        /** {@code getenforce} is not installed (not an SELinux system). */
        ABSENT,
        /** The mode could not be determined. */
        UNKNOWN
    }

    /** The firewalld running state reported by {@code firewall-cmd --state}. */
    public enum FirewallState {
        /** The firewall is running. */
        RUNNING,
        /** The firewall is installed but not running. */
        NOT_RUNNING,
        /** {@code firewall-cmd} is not installed. */
        ABSENT,
        /** The state could not be read (typically needs authorization). */
        UNKNOWN
    }

    private SecurityProbe() {
        // no instances
    }

    // ------------------------------------------------------------------
    // Command builders
    // ------------------------------------------------------------------

    /** @return the command that prints the SELinux mode ({@code getenforce}). */
    public static List<String> selinuxCommand() {
        return List.of("getenforce");
    }

    /** @return the command that prints the firewall state. */
    public static List<String> firewallStateCommand() {
        return List.of("firewall-cmd", "--state");
    }

    // ------------------------------------------------------------------
    // Parsers (pure)
    // ------------------------------------------------------------------

    /**
     * Parses {@code getenforce} output.
     *
     * @param output the trimmed command output (may be null / blank)
     * @return the mode, or {@link SelinuxMode#UNKNOWN} when unrecognised
     */
    public static SelinuxMode parseSelinux(String output) {
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
     * Parses {@code firewall-cmd --state}. The command prints {@code running} or
     * {@code not running} and exits non-zero when the daemon is down or the call
     * is not authorized; an authorization denial is reported as
     * {@link FirewallState#UNKNOWN}, not as "not running".
     *
     * @param exitCode the process exit code
     * @param output   the trimmed command output (may be null / blank)
     * @return the firewall state
     */
    public static FirewallState parseFirewallState(int exitCode, String output) {
        String text = (output == null) ? "" : output.trim().toLowerCase(Locale.ROOT);
        if (text.contains("not running")) {
            return FirewallState.NOT_RUNNING;
        }
        if (text.contains("authorization") || text.contains("polkit")
                || text.contains("not authorized") || text.contains("access denied")) {
            return FirewallState.UNKNOWN;
        }
        if (text.equals("running") || text.contains("running")) {
            return FirewallState.RUNNING;
        }
        // No usable text: fall back to the exit code (0 = running for --state).
        return (exitCode == 0) ? FirewallState.RUNNING : FirewallState.UNKNOWN;
    }

    /**
     * A short human label for a SELinux mode, for the overview card.
     *
     * @param mode the mode
     * @return the display label, never null
     */
    public static String describeSelinux(SelinuxMode mode) {
        if (mode == null) {
            return "Unknown";
        }
        return switch (mode) {
            case ENFORCING -> "Enforcing";
            case PERMISSIVE -> "Permissive";
            case DISABLED -> "Disabled";
            case ABSENT -> "Not installed";
            case UNKNOWN -> "Unknown";
        };
    }

    /**
     * A short human label for a firewall state, for the overview card.
     *
     * @param state the state
     * @return the display label, never null
     */
    public static String describeFirewall(FirewallState state) {
        if (state == null) {
            return "Unknown";
        }
        return switch (state) {
            case RUNNING -> "Running";
            case NOT_RUNNING -> "Not running";
            case ABSENT -> "Not installed";
            case UNKNOWN -> "Unknown (needs authorization)";
        };
    }
}
