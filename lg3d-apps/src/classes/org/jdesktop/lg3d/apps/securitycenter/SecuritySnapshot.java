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
import java.util.List;

/**
 * The host's aggregated security posture shown on the Security Overview tab:
 * the SELinux mode, the firewall state, whether an antivirus scanner is
 * installed (and which one), and the ClamAV engine / database version. The panel
 * assembles one of these from the {@link SecurityProbe} and
 * {@link AntivirusBackend} parser results after a refresh; {@link #concerns()}
 * turns it into the plain-language advice list the overview renders.
 *
 * @param selinux          the SELinux enforcement mode
 * @param firewall         the firewall running state
 * @param scannerAvailable whether a ClamAV scanner was found on the PATH
 * @param scanner          the scanner name (empty when none is available)
 * @param version          the ClamAV engine / database version
 */
public record SecuritySnapshot(
        SecurityProbe.SelinuxMode selinux,
        SecurityProbe.FirewallState firewall,
        boolean scannerAvailable,
        String scanner,
        VersionInfo version) {

    /** Normalises nulls so a snapshot is always safe to render. */
    public SecuritySnapshot {
        selinux = (selinux == null) ? SecurityProbe.SelinuxMode.UNKNOWN : selinux;
        firewall = (firewall == null) ? SecurityProbe.FirewallState.UNKNOWN : firewall;
        scanner = (scanner == null) ? "" : scanner.trim();
        version = (version == null) ? VersionInfo.unknown() : version;
        scannerAvailable = scannerAvailable && !scanner.isBlank();
    }

    /** An all-unknown snapshot, used before the first refresh. */
    public static SecuritySnapshot unknown() {
        return new SecuritySnapshot(SecurityProbe.SelinuxMode.UNKNOWN,
                SecurityProbe.FirewallState.UNKNOWN, false, "", VersionInfo.unknown());
    }

    /**
     * The plain-language concerns implied by this posture, worst first. An empty
     * list means nothing actionable was detected (SELinux enforcing, firewall
     * running, a scanner installed).
     *
     * @return the advice lines, never null
     */
    public List<String> concerns() {
        List<String> out = new ArrayList<>();
        if (!scannerAvailable) {
            out.add("No antivirus scanner found - install ClamAV (clamscan).");
        }
        switch (selinux) {
            case DISABLED -> out.add("SELinux is disabled.");
            case PERMISSIVE -> out.add("SELinux is permissive - violations are logged, not blocked.");
            case ABSENT -> out.add("SELinux is not installed on this system.");
            case UNKNOWN -> out.add("SELinux status could not be determined.");
            case ENFORCING -> {
                // The desired state; no concern.
            }
        }
        switch (firewall) {
            case NOT_RUNNING -> out.add("The firewall is not running.");
            case ABSENT -> out.add("No firewall manager (firewalld) was found.");
            case UNKNOWN -> out.add("Firewall status is unknown - it needs administrator authorization.");
            case RUNNING -> {
                // The desired state; no concern.
            }
        }
        return out;
    }

    /**
     * A coarse overall rating for the overview header.
     *
     * @return {@code GOOD} when there are no concerns, {@code ATTENTION} when
     *         there are some, or {@code UNKNOWN} before the first refresh
     */
    public String rating() {
        boolean untouched = selinux == SecurityProbe.SelinuxMode.UNKNOWN
                && firewall == SecurityProbe.FirewallState.UNKNOWN
                && !scannerAvailable;
        if (untouched) {
            return "Unknown";
        }
        return concerns().isEmpty() ? "Good" : "Attention needed";
    }
}
