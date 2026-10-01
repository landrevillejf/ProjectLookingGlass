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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SecurityProbe}'s posture parsers and {@link SecuritySnapshot}'s
 * concern / rating aggregation. Pure, so it runs headless.
 */
class SecurityProbeTest {

    @Test
    @DisplayName("command builders target getenforce and firewall-cmd")
    void commands() {
        assertEquals(List.of("getenforce"), SecurityProbe.selinuxCommand());
        assertEquals(List.of("firewall-cmd", "--state"), SecurityProbe.firewallStateCommand());
    }

    @Test
    @DisplayName("parseSelinux maps getenforce output, tolerating case / blank")
    void parseSelinux() {
        assertEquals(SecurityProbe.SelinuxMode.ENFORCING, SecurityProbe.parseSelinux("Enforcing"));
        assertEquals(SecurityProbe.SelinuxMode.PERMISSIVE, SecurityProbe.parseSelinux(" Permissive\n"));
        assertEquals(SecurityProbe.SelinuxMode.DISABLED, SecurityProbe.parseSelinux("disabled"));
        assertEquals(SecurityProbe.SelinuxMode.UNKNOWN, SecurityProbe.parseSelinux("garbage"));
        assertEquals(SecurityProbe.SelinuxMode.UNKNOWN, SecurityProbe.parseSelinux(null));
        assertEquals(SecurityProbe.SelinuxMode.UNKNOWN, SecurityProbe.parseSelinux("  "));
    }

    @Test
    @DisplayName("parseFirewallState reads running / not running / authorization")
    void parseFirewallState() {
        assertEquals(SecurityProbe.FirewallState.RUNNING,
                SecurityProbe.parseFirewallState(0, "running"));
        assertEquals(SecurityProbe.FirewallState.NOT_RUNNING,
                SecurityProbe.parseFirewallState(252, "not running"));
        assertEquals(SecurityProbe.FirewallState.UNKNOWN,
                SecurityProbe.parseFirewallState(1, "Authorization failed, could not get state."),
                "a polkit denial is unknown, never 'not running'");
        assertEquals(SecurityProbe.FirewallState.RUNNING,
                SecurityProbe.parseFirewallState(0, ""),
                "a zero exit with no text still means running");
        assertEquals(SecurityProbe.FirewallState.UNKNOWN,
                SecurityProbe.parseFirewallState(1, null));
    }

    @Test
    @DisplayName("describe helpers render every enum value")
    void describe() {
        assertEquals("Enforcing", SecurityProbe.describeSelinux(SecurityProbe.SelinuxMode.ENFORCING));
        assertEquals("Permissive", SecurityProbe.describeSelinux(SecurityProbe.SelinuxMode.PERMISSIVE));
        assertEquals("Disabled", SecurityProbe.describeSelinux(SecurityProbe.SelinuxMode.DISABLED));
        assertEquals("Not installed", SecurityProbe.describeSelinux(SecurityProbe.SelinuxMode.ABSENT));
        assertEquals("Unknown", SecurityProbe.describeSelinux(SecurityProbe.SelinuxMode.UNKNOWN));
        assertEquals("Unknown", SecurityProbe.describeSelinux(null));

        assertEquals("Running", SecurityProbe.describeFirewall(SecurityProbe.FirewallState.RUNNING));
        assertEquals("Not running", SecurityProbe.describeFirewall(SecurityProbe.FirewallState.NOT_RUNNING));
        assertEquals("Not installed", SecurityProbe.describeFirewall(SecurityProbe.FirewallState.ABSENT));
        assertTrue(SecurityProbe.describeFirewall(SecurityProbe.FirewallState.UNKNOWN)
                .contains("authorization"));
        assertEquals("Unknown", SecurityProbe.describeFirewall(null));
    }

    @Test
    @DisplayName("a fully protected snapshot has no concerns and rates Good")
    void snapshotGood() {
        SecuritySnapshot snap = new SecuritySnapshot(
                SecurityProbe.SelinuxMode.ENFORCING,
                SecurityProbe.FirewallState.RUNNING,
                true, "clamscan", new VersionInfo("1.4.6", "27171", ""));
        assertTrue(snap.concerns().isEmpty());
        assertEquals("Good", snap.rating());
    }

    @Test
    @DisplayName("weak posture surfaces one concern per problem and rates Attention")
    void snapshotConcerns() {
        SecuritySnapshot snap = new SecuritySnapshot(
                SecurityProbe.SelinuxMode.PERMISSIVE,
                SecurityProbe.FirewallState.NOT_RUNNING,
                false, "", VersionInfo.unknown());
        List<String> concerns = snap.concerns();
        assertEquals(3, concerns.size(), "no scanner + permissive + firewall down");
        assertTrue(concerns.stream().anyMatch(c -> c.contains("antivirus") || c.contains("ClamAV")));
        assertTrue(concerns.stream().anyMatch(c -> c.contains("permissive")));
        assertTrue(concerns.stream().anyMatch(c -> c.contains("firewall")));
        assertEquals("Attention needed", snap.rating());
    }

    @Test
    @DisplayName("an untouched snapshot rates Unknown; scannerAvailable needs a name")
    void snapshotUnknownAndNormalization() {
        SecuritySnapshot untouched = SecuritySnapshot.unknown();
        assertEquals("Unknown", untouched.rating());
        assertFalse(untouched.scannerAvailable());

        // scannerAvailable is forced false when the scanner name is blank.
        SecuritySnapshot blank = new SecuritySnapshot(
                SecurityProbe.SelinuxMode.DISABLED,
                SecurityProbe.FirewallState.ABSENT,
                true, "  ", null);
        assertFalse(blank.scannerAvailable());
        assertEquals(VersionInfo.unknown(), blank.version(), "null version normalises");
        assertTrue(blank.concerns().stream().anyMatch(c -> c.contains("disabled")));
        assertTrue(blank.concerns().stream().anyMatch(c -> c.contains("firewalld")));
    }
}
