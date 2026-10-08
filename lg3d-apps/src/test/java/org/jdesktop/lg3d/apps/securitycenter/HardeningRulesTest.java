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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jdesktop.lg3d.apps.securitycenter.HardeningRules.Action;
import org.jdesktop.lg3d.apps.securitycenter.HardeningRules.Recommendation;
import org.jdesktop.lg3d.apps.securitycenter.HardeningRules.Severity;
import org.jdesktop.lg3d.apps.securitycenter.SecurityProbe.FirewallState;
import org.jdesktop.lg3d.apps.securitycenter.SecurityProbe.SelinuxMode;
import org.jdesktop.lg3d.utils.system.TorPrivateMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure {@link HardeningRules} advisor: each rule fires on the right
 * posture, the list is ordered worst-first, a fully hardened host yields nothing
 * actionable, and a {@link Recommendation} normalises nulls and renders as its
 * title.
 */
class HardeningRulesTest {

    private static final VersionInfo VERSION = new VersionInfo("1.4.6", "27171", "");

    private static SecuritySnapshot snapshot(
            SelinuxMode selinux, FirewallState firewall,
            boolean scanner, VersionInfo version,
            TorPrivateMode.State tor, boolean vpnUp, boolean vpnKill) {
        return new SecuritySnapshot(selinux, firewall, scanner, scanner ? "clamscan" : "",
                version, tor, vpnUp, vpnKill);
    }

    /** The first recommendation carrying {@code action}, or null. */
    private static Recommendation withAction(List<Recommendation> list, Action action) {
        for (Recommendation r : list) {
            if (r.action() == action) {
                return r;
            }
        }
        return null;
    }

    private static void assertWorstFirst(List<Recommendation> list) {
        for (int i = 1; i < list.size(); i++) {
            assertTrue(list.get(i - 1).severity().ordinal() <= list.get(i).severity().ordinal(),
                    "severities must not decrease in urgency at index " + i);
        }
    }

    @Test
    @DisplayName("a fully hardened host has nothing actionable")
    void hardenedHostIsEmpty() {
        List<Recommendation> recs = HardeningRules.evaluate(snapshot(
                SelinuxMode.ENFORCING, FirewallState.RUNNING, true, VERSION,
                TorPrivateMode.State.ON, true, true));
        assertTrue(recs.isEmpty(), "every control is on, so no advice: " + recs);
    }

    @Test
    @DisplayName("a null snapshot degrades to unknown nudges, never a false all-clear")
    void nullIsUnknown() {
        List<Recommendation> recs = HardeningRules.evaluate(null);
        assertEquals(4, recs.size(), "firewall unknown, no scanner, selinux unknown, tor off");
        assertWorstFirst(recs);
        assertNotNull(withAction(recs, Action.OPEN_ANTIVIRUS), "no scanner is actionable");
        assertNotNull(withAction(recs, Action.OPEN_PRIVACY), "tor off is actionable");
    }

    @Test
    @DisplayName("the list is ordered worst-first across severities")
    void orderingIsWorstFirst() {
        List<Recommendation> recs = HardeningRules.evaluate(snapshot(
                SelinuxMode.DISABLED, FirewallState.NOT_RUNNING, false, VersionInfo.unknown(),
                TorPrivateMode.State.OFF, false, false));
        assertTrue(recs.size() >= 4, recs.toString());
        assertWorstFirst(recs);
        assertEquals(Severity.HIGH, recs.get(0).severity(), "the first advice is the most urgent");
    }

    @Test
    @DisplayName("a firewall that is down or missing is a HIGH finding")
    void firewallRules() {
        Recommendation notRunning = withAction(HardeningRules.evaluate(snapshot(
                SelinuxMode.ENFORCING, FirewallState.NOT_RUNNING, true, VERSION,
                TorPrivateMode.State.ON, true, true)), Action.REFRESH_OVERVIEW);
        assertNotNull(notRunning);
        assertEquals(Severity.HIGH, notRunning.severity());
        assertTrue(notRunning.title().toLowerCase().contains("firewall"), notRunning.title());

        Recommendation absent = withAction(HardeningRules.evaluate(snapshot(
                SelinuxMode.ENFORCING, FirewallState.ABSENT, true, VERSION,
                TorPrivateMode.State.ON, true, true)), Action.INFO);
        assertNotNull(absent);
        assertEquals(Severity.HIGH, absent.severity());
    }

    @Test
    @DisplayName("SELinux permissive is MEDIUM, disabled is HIGH")
    void selinuxRules() {
        Recommendation permissive = HardeningRules.evaluate(snapshot(
                SelinuxMode.PERMISSIVE, FirewallState.RUNNING, true, VERSION,
                TorPrivateMode.State.ON, true, true)).get(0);
        assertEquals(Severity.MEDIUM, permissive.severity());

        Recommendation disabled = HardeningRules.evaluate(snapshot(
                SelinuxMode.DISABLED, FirewallState.RUNNING, true, VERSION,
                TorPrivateMode.State.ON, true, true)).get(0);
        assertEquals(Severity.HIGH, disabled.severity());
        assertEquals(Action.INFO, disabled.action(), "the fix lives outside this app");
    }

    @Test
    @DisplayName("a missing scanner opens the antivirus tab; stale definitions update them")
    void antivirusRules() {
        Recommendation noScanner = withAction(HardeningRules.evaluate(snapshot(
                SelinuxMode.ENFORCING, FirewallState.RUNNING, false, VersionInfo.unknown(),
                TorPrivateMode.State.ON, true, true)), Action.OPEN_ANTIVIRUS);
        assertNotNull(noScanner);
        assertEquals(Severity.MEDIUM, noScanner.severity());

        Recommendation staleDefs = withAction(HardeningRules.evaluate(snapshot(
                SelinuxMode.ENFORCING, FirewallState.RUNNING, true, VersionInfo.unknown(),
                TorPrivateMode.State.ON, true, true)), Action.UPDATE_DEFINITIONS);
        assertNotNull(staleDefs, "a scanner with unknown definitions should offer an update");
        assertEquals(Severity.LOW, staleDefs.severity());
    }

    @Test
    @DisplayName("a CUT private mode is a HIGH finding that opens the Privacy tab")
    void torCutIsHigh() {
        List<Recommendation> recs = HardeningRules.evaluate(snapshot(
                SelinuxMode.ENFORCING, FirewallState.RUNNING, true, VERSION,
                TorPrivateMode.State.CUT, false, false));
        assertEquals(1, recs.size(), "only the cut is actionable");
        Recommendation cut = recs.get(0);
        assertEquals(Severity.HIGH, cut.severity());
        assertEquals(Action.OPEN_PRIVACY, cut.action());
        assertTrue(cut.title().contains("CUT"), cut.title());
    }

    @Test
    @DisplayName("a tunnel without a kill switch is flagged; arming it clears the flag")
    void vpnKillSwitchRule() {
        Recommendation noKill = withAction(HardeningRules.evaluate(snapshot(
                SelinuxMode.ENFORCING, FirewallState.RUNNING, true, VERSION,
                TorPrivateMode.State.ON, true, false)), Action.INFO);
        assertNotNull(noKill, "a connected tunnel with no kill switch can leak");
        assertEquals(Severity.MEDIUM, noKill.severity());
        assertTrue(noKill.title().toLowerCase().contains("kill switch"), noKill.title());

        List<Recommendation> armed = HardeningRules.evaluate(snapshot(
                SelinuxMode.ENFORCING, FirewallState.RUNNING, true, VERSION,
                TorPrivateMode.State.ON, true, true));
        assertTrue(armed.isEmpty(), "arming the kill switch clears the VPN finding: " + armed);
    }

    @Test
    @DisplayName("a recommendation normalises nulls and renders as its title")
    void recommendationNormalisation() {
        Recommendation blank = new Recommendation(null, null, null, null);
        assertEquals("", blank.title());
        assertEquals("", blank.detail());
        assertEquals(Action.INFO, blank.action(), "a null action defaults to INFO");
        assertEquals(Severity.LOW, blank.severity(), "a null severity defaults to LOW");
        assertEquals("", blank.toString());

        Recommendation titled = new Recommendation("  Do the thing  ", " why ",
                Action.OPEN_PRIVACY, Severity.HIGH);
        assertEquals("Do the thing", titled.title(), "the title is trimmed");
        assertEquals("why", titled.detail());
        assertEquals("Do the thing", titled.toString(), "the list cell shows the title");
    }
}
