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
package org.jdesktop.lg3d.apps.firewall;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import org.jdesktop.lg3d.utils.system.PrivilegedRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link FirewallPanel}'s headless construction and the nftables view's
 * availability gating. The panel is plain Swing and never spawns a process until
 * the user acts, so it builds in CI; the pure nft / AppArmor / SELinux / sshd
 * parsing behind it is covered by {@code SecurityServiceTest} in lg3d-core.
 */
class FirewallPanelTest {

    @Test
    @DisplayName("the panel constructs headless at its native 700x500 size")
    void constructsHeadless() {
        FirewallPanel panel = assertDoesNotThrow(FirewallPanel::new);
        Dimension pref = panel.getPreferredSize();
        assertEquals(700, pref.width);
        assertEquals(500, pref.height);
    }

    @Test
    @DisplayName("it offers a Rules tab and an nftables tab")
    void hasRulesAndNftablesTabs() {
        FirewallPanel panel = new FirewallPanel();
        assertEquals(2, panel.tabCount());
        assertEquals("Rules", panel.tabTitle(0));
        assertEquals("nftables", panel.tabTitle(1));
    }

    @Test
    @DisplayName("the nft summary starts labelled, before any read")
    void summaryDefaultsBeforeRead() {
        FirewallPanel panel = new FirewallPanel();
        assertTrue(panel.nftSummaryText().startsWith("nftables:"), panel.nftSummaryText());
    }

    @Test
    @DisplayName("nft controls are gated on backend availability; apply also needs polkit")
    void nftButtonGating() {
        FirewallPanel panel = new FirewallPanel();
        boolean available = panel.nftAvailable();
        assertEquals(available, panel.nftReloadEnabled(),
                "reload is enabled exactly when nft is present");
        assertEquals(available && PrivilegedRunner.isAvailable(), panel.nftApplyEnabled(),
                "apply also requires privilege escalation");
    }

    @Test
    @DisplayName("stop() and setOnClose() are safe lifecycle no-ops before any refresh")
    void lifecycleIsSafe() {
        FirewallPanel panel = new FirewallPanel();
        assertDoesNotThrow(() -> {
            panel.setOnClose(() -> {
                // no-op callback
            });
            panel.stop();
        });
    }
}
