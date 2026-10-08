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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jdesktop.lg3d.apps.securitycenter.SecurityProbe.FirewallState;
import org.jdesktop.lg3d.apps.securitycenter.SecurityProbe.SelinuxMode;
import org.jdesktop.lg3d.utils.system.TorPrivateMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure {@link SecurityScore} checklist: the weights sum to 100, the
 * grade bands, the per-item earn / gap accounting, the {@link SecurityScore.Result}
 * normalisation, and the two anonymity layers being weighted so a fully patched
 * host without a tunnel still grades a B while everything-on reaches an A.
 */
class SecurityScoreTest {

    private static final VersionInfo VERSION = new VersionInfo("1.4.6", "27171", "");

    /** The fully hardened host: every check passes. */
    private static SecuritySnapshot perfect() {
        return new SecuritySnapshot(SelinuxMode.ENFORCING, FirewallState.RUNNING,
                true, "clamscan", VERSION,
                TorPrivateMode.State.ON, true, true);
    }

    /** Patched and protected, but no anonymity layer (tor off, no tunnel). */
    private static SecuritySnapshot noTunnel() {
        return new SecuritySnapshot(SelinuxMode.ENFORCING, FirewallState.RUNNING,
                true, "clamscan", VERSION,
                TorPrivateMode.State.OFF, false, false);
    }

    @Test
    @DisplayName("the six weights sum to exactly 100")
    void weightsSumTo100() {
        int total = SecurityScore.W_SELINUX + SecurityScore.W_FIREWALL
                + SecurityScore.W_SCANNER + SecurityScore.W_DEFINITIONS
                + SecurityScore.W_TOR + SecurityScore.W_VPN;
        assertEquals(100, total, "the checklist is a 0-100 scale");
    }

    @Test
    @DisplayName("a fully hardened host scores 100 and grades A")
    void perfectHostIsGradeA() {
        SecurityScore.Result result = SecurityScore.compute(perfect());
        assertEquals(100, result.max());
        assertEquals(100, result.score());
        assertEquals(100, result.percent());
        assertEquals(SecurityScore.Grade.A, result.grade());
        assertEquals("Grade A (100/100)", result.header());
        assertTrue(result.gaps().isEmpty(), "nothing failed, so no gaps");
        assertEquals(6, result.items().size(), "one item per check");
        for (SecurityScore.Item item : result.items()) {
            assertTrue(item.passed(), item.check() + " should pass");
            assertEquals(item.weight(), item.earned());
        }
    }

    @Test
    @DisplayName("a patched host without a tunnel grades B (anonymity is optional)")
    void noTunnelIsGradeB() {
        SecurityScore.Result result = SecurityScore.compute(noTunnel());
        // SELinux 25 + firewall 25 + scanner 20 + definitions 10 = 80; tor + VPN miss.
        assertEquals(80, result.score());
        assertEquals(SecurityScore.Grade.B, result.grade());
        List<SecurityScore.Item> gaps = result.gaps();
        assertEquals(2, gaps.size(), "only the tor and VPN checks failed");
        for (SecurityScore.Item gap : gaps) {
            assertFalse(gap.passed());
            assertEquals(0, gap.earned());
        }
    }

    @Test
    @DisplayName("an unprobed host scores zero and grades F, never a false all-clear")
    void unknownScoresZero() {
        SecurityScore.Result result = SecurityScore.compute(null);
        assertEquals(0, result.score());
        assertEquals(100, result.max());
        assertEquals(SecurityScore.Grade.F, result.grade());
        assertEquals(6, result.gaps().size(), "every check is unmet");
    }

    @Test
    @DisplayName("the VPN check needs both a tunnel and an armed kill switch")
    void vpnNeedsKillSwitch() {
        SecuritySnapshot connectedNoKillSwitch = new SecuritySnapshot(
                SelinuxMode.ENFORCING, FirewallState.RUNNING, true, "clamscan", VERSION,
                TorPrivateMode.State.ON, true, false);
        SecurityScore.Result result = SecurityScore.compute(connectedNoKillSwitch);
        // Everything but the VPN kill switch: 100 - 10 = 90, still an A.
        assertEquals(90, result.score());
        SecurityScore.Item vpn = result.items().get(5);
        assertEquals("VPN tunnel with kill switch", vpn.check());
        assertFalse(vpn.passed(), "a tunnel without a kill switch does not earn the point");
        assertTrue(vpn.detail().contains("kill switch is off"), vpn.detail());
    }

    @Test
    @DisplayName("grade bands map the percentage onto A-F")
    void gradeBands() {
        assertEquals(SecurityScore.Grade.A, SecurityScore.gradeFor(90, 100));
        assertEquals(SecurityScore.Grade.A, SecurityScore.gradeFor(100, 100));
        assertEquals(SecurityScore.Grade.B, SecurityScore.gradeFor(89, 100));
        assertEquals(SecurityScore.Grade.B, SecurityScore.gradeFor(75, 100));
        assertEquals(SecurityScore.Grade.C, SecurityScore.gradeFor(74, 100));
        assertEquals(SecurityScore.Grade.C, SecurityScore.gradeFor(60, 100));
        assertEquals(SecurityScore.Grade.D, SecurityScore.gradeFor(59, 100));
        assertEquals(SecurityScore.Grade.D, SecurityScore.gradeFor(40, 100));
        assertEquals(SecurityScore.Grade.F, SecurityScore.gradeFor(39, 100));
        assertEquals(SecurityScore.Grade.F, SecurityScore.gradeFor(0, 100));
    }

    @Test
    @DisplayName("a zero or negative max scores zero percent and grades F, never dividing by zero")
    void degenerateMaxIsSafe() {
        assertEquals(SecurityScore.Grade.F, SecurityScore.gradeFor(10, 0));
        assertEquals(0, SecurityScore.gradeFor(0, 0) == SecurityScore.Grade.F ? 0 : 1);
        SecurityScore.Result empty = new SecurityScore.Result(0, 0, null, null);
        assertEquals(0, empty.percent());
        assertEquals(SecurityScore.Grade.F, empty.grade(), "a null grade falls back to F");
        assertNotNull(empty.items());
        assertTrue(empty.items().isEmpty(), "null items become an empty list");
    }

    @Test
    @DisplayName("a result clamps an out-of-range score into [0, max]")
    void resultClampsScore() {
        assertEquals(0, new SecurityScore.Result(-50, 100, SecurityScore.Grade.F, List.of()).score());
        assertEquals(100, new SecurityScore.Result(500, 100, SecurityScore.Grade.A, List.of()).score());
        assertEquals(0, new SecurityScore.Result(10, -100, SecurityScore.Grade.F, List.of()).max(),
                "a negative max clamps to zero");
    }

    @Test
    @DisplayName("an item normalises nulls and clamps a negative weight")
    void itemNormalisation() {
        SecurityScore.Item item = new SecurityScore.Item(null, true, -5, null);
        assertEquals("", item.check());
        assertEquals("", item.detail());
        assertEquals(0, item.weight(), "a negative weight clamps to zero");
        assertEquals(0, item.earned(), "passed but weightless still earns zero");
    }
}
