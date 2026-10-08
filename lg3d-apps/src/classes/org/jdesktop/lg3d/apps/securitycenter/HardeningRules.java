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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The pure hardening advisor behind the Overview's recommendations list. Given a
 * {@link SecuritySnapshot} it derives an ordered set of {@link Recommendation}s -
 * worst first - each carrying a short title, the reason it matters, a
 * {@link Severity} and an {@link Action} the panel can perform (open a tab, run
 * {@code freshclam}, re-probe) or, where the fix lives outside this app, an
 * honest {@link Action#INFO} nudge.
 *
 * <p>It complements {@link SecurityScore}: the score answers "how hardened am
 * I?", the rules answer "what should I do next, in what order?". Like the score
 * it reads nothing and spawns nothing, so the whole contract is unit-testable
 * headless. An empty list means nothing actionable was found.</p>
 */
public final class HardeningRules {

    /** What the Security Center can do about a recommendation. */
    public enum Action {
        /** Switch to the Privacy tab (tor / private mode). */
        OPEN_PRIVACY,
        /** Switch to the Antivirus tab. */
        OPEN_ANTIVIRUS,
        /** Run {@code freshclam} to update virus definitions. */
        UPDATE_DEFINITIONS,
        /** Re-run the host posture probe. */
        REFRESH_OVERVIEW,
        /** Informational only - the fix lives outside this app. */
        INFO
    }

    /** How urgent a recommendation is; the ordinal drives the worst-first ordering. */
    public enum Severity {
        /** An active exposure or a broken guarantee. */
        HIGH,
        /** A meaningful weakness worth fixing soon. */
        MEDIUM,
        /** Optional hardening / defense-in-depth. */
        LOW
    }

    /**
     * One ordered recommendation.
     *
     * @param title    the short headline shown in the list (never null)
     * @param detail   why it matters and what to do (never null)
     * @param action   what the panel's Remediate button should do
     * @param severity how urgent it is
     */
    public record Recommendation(String title, String detail, Action action, Severity severity) {

        /** Normalises nulls so a recommendation is always safe to render and act on. */
        public Recommendation {
            title = (title == null) ? "" : title.trim();
            detail = (detail == null) ? "" : detail.trim();
            action = (action == null) ? Action.INFO : action;
            severity = (severity == null) ? Severity.LOW : severity;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private HardeningRules() {
        // no instances
    }

    /**
     * Evaluates a snapshot into an ordered, worst-first recommendation list. A
     * null snapshot is treated as {@link SecuritySnapshot#unknown()}, whose
     * unread posture yields "unknown" nudges rather than a false all-clear.
     *
     * @param snapshot the posture to advise on
     * @return the recommendations, worst first; empty when nothing is actionable
     */
    public static List<Recommendation> evaluate(SecuritySnapshot snapshot) {
        SecuritySnapshot snap = (snapshot == null) ? SecuritySnapshot.unknown() : snapshot;
        List<Recommendation> out = new ArrayList<>();
        addFirewall(snap, out);
        addSelinux(snap, out);
        addAntivirus(snap, out);
        addPrivateMode(snap, out);
        addVpn(snap, out);
        out.sort(Comparator.comparingInt(r -> r.severity().ordinal()));
        return out;
    }

    private static void addFirewall(SecuritySnapshot s, List<Recommendation> out) {
        switch (s.firewall()) {
            case NOT_RUNNING -> out.add(new Recommendation(
                    "The firewall is not running",
                    "An inactive firewall leaves every listening service reachable from the "
                            + "network. Start firewalld and re-check.",
                    Action.REFRESH_OVERVIEW, Severity.HIGH));
            case ABSENT -> out.add(new Recommendation(
                    "No firewall manager was found",
                    "Install and enable firewalld (or nftables) to filter inbound traffic.",
                    Action.INFO, Severity.HIGH));
            case UNKNOWN -> out.add(new Recommendation(
                    "The firewall state is unknown",
                    "Reading it needs administrator authorization; re-check once authorized.",
                    Action.REFRESH_OVERVIEW, Severity.MEDIUM));
            case RUNNING -> {
                // The desired state; nothing to recommend.
            }
        }
    }

    private static void addSelinux(SecuritySnapshot s, List<Recommendation> out) {
        switch (s.selinux()) {
            case DISABLED -> out.add(new Recommendation(
                    "SELinux is disabled",
                    "Mandatory access control is off, so a compromised app is unconstrained. "
                            + "Switch SELinux to enforcing.",
                    Action.INFO, Severity.HIGH));
            case PERMISSIVE -> out.add(new Recommendation(
                    "SELinux is permissive",
                    "Violations are logged but not blocked; switch to enforcing to actually "
                            + "contain them.",
                    Action.INFO, Severity.MEDIUM));
            case ABSENT -> out.add(new Recommendation(
                    "SELinux is not installed",
                    "This system has no SELinux; consider a distribution with mandatory "
                            + "access control.",
                    Action.INFO, Severity.LOW));
            case UNKNOWN -> out.add(new Recommendation(
                    "SELinux status is unknown",
                    "getenforce returned nothing recognisable; re-check the host posture.",
                    Action.REFRESH_OVERVIEW, Severity.LOW));
            case ENFORCING -> {
                // The desired state; nothing to recommend.
            }
        }
    }

    private static void addAntivirus(SecuritySnapshot s, List<Recommendation> out) {
        if (!s.scannerAvailable()) {
            out.add(new Recommendation(
                    "No antivirus scanner is installed",
                    "Install ClamAV (clamscan) so files can be scanned for malware.",
                    Action.OPEN_ANTIVIRUS, Severity.MEDIUM));
        } else if (!s.version().isPresent()) {
            out.add(new Recommendation(
                    "Virus definitions are unknown or stale",
                    "Update ClamAV's definitions (freshclam) so scans match current threats.",
                    Action.UPDATE_DEFINITIONS, Severity.LOW));
        }
    }

    private static void addPrivateMode(SecuritySnapshot s, List<Recommendation> out) {
        switch (s.tor()) {
            case CUT -> out.add(new Recommendation(
                    "Private (Tor) mode has CUT the network",
                    "Tor stopped while private mode was on, so the network is refused to "
                            + "prevent leaks. Restart tor to restore it.",
                    Action.OPEN_PRIVACY, Severity.HIGH));
            case ENABLING -> out.add(new Recommendation(
                    "Private (Tor) mode is still enabling",
                    "Tor is starting up; re-check the Privacy tab shortly.",
                    Action.OPEN_PRIVACY, Severity.LOW));
            case OFF -> out.add(new Recommendation(
                    "Private (Tor) mode is off",
                    "Enable it to route desktop traffic through tor for anonymity "
                            + "(defense in depth).",
                    Action.OPEN_PRIVACY, Severity.LOW));
            case ON -> {
                // The desired state; nothing to recommend.
            }
        }
    }

    private static void addVpn(SecuritySnapshot s, List<Recommendation> out) {
        if (s.vpnConnected() && !s.vpnKillSwitch()) {
            out.add(new Recommendation(
                    "The VPN kill switch is not armed",
                    "A connected tunnel without a kill switch can leak clearnet traffic if it "
                            + "drops. Arm it in the VPN app.",
                    Action.INFO, Severity.MEDIUM));
        }
    }
}
