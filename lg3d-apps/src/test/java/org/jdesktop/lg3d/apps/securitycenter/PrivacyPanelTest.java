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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jdesktop.lg3d.utils.system.TorPrivateMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link PrivacyPanel}'s headless construction and its availability
 * gating. The panel is plain Swing and resolves tor / init / polkit / file
 * presence with non-spawning probes, so it builds in CI and never runs a command
 * until the user acts; the init-delegated tor vectors, the state parser and the
 * bounded read-only file views behind it are covered by {@code PrivacyServiceTest}
 * in lg3d-core.
 */
class PrivacyPanelTest {

    @Test
    @DisplayName("the panel constructs headless without spawning a process")
    void constructsHeadless() {
        assertDoesNotThrow(PrivacyPanel::new);
    }

    @Test
    @DisplayName("the tor status header starts labelled, before any read")
    void statusDefaultsBeforeRead() {
        PrivacyPanel panel = new PrivacyPanel();
        assertTrue(panel.torStatusText().startsWith("tor:"), panel.torStatusText());
        assertTrue(panel.viewerText().isEmpty(), "the viewer is blank until a read runs");
    }

    @Test
    @DisplayName("manageable is exactly 'installed AND an init detected'")
    void manageableIsInstalledAndInit() {
        PrivacyPanel panel = new PrivacyPanel();
        assertEquals(panel.torInstalled() && panel.initKnown(), panel.torManageable(),
                "tor is drivable only when the binary and a supervisor are both present");
    }

    @Test
    @DisplayName("each control is gated on its own availability (idle by default)")
    void buttonGatingMatchesAvailability() {
        PrivacyPanel panel = new PrivacyPanel();
        assertEquals(panel.initKnown(), panel.refreshEnabled(),
                "status needs a detected init system");
        assertEquals(panel.torrcPresent(), panel.viewConfigEnabled(),
                "the config view needs /etc/tor/torrc");
        assertEquals(panel.torLogPresent(), panel.viewLogEnabled(),
                "the log view needs /var/log/tor/notices.log");

        boolean canManage = panel.torManageable() && panel.polkitAvailable();
        assertEquals(canManage, panel.startEnabled(), "start needs tor + init + polkit");
        assertEquals(canManage, panel.stopEnabled(), "stop needs tor + init + polkit");
        assertEquals(canManage, panel.restartEnabled(), "restart needs tor + init + polkit");
    }

    @Test
    @DisplayName("the three lifecycle controls always agree")
    void lifecycleControlsAgree() {
        PrivacyPanel panel = new PrivacyPanel();
        assertEquals(panel.startEnabled(), panel.stopEnabled());
        assertEquals(panel.stopEnabled(), panel.restartEnabled());
    }

    @Test
    @DisplayName("the private-mode section starts labelled and uncut")
    void privateModeDefaults() {
        PrivacyPanel panel = new PrivacyPanel();
        assertTrue(panel.privateModeText().startsWith("Private (Tor) mode:"),
                panel.privateModeText());
        // No test enables the (global) mode, so it is OFF here: no CUT banner.
        assertEquals(TorPrivateMode.State.OFF, TorPrivateMode.state());
        assertFalse(panel.cutBannerVisible(), "the CUT banner is hidden while off");
    }

    @Test
    @DisplayName("private-mode controls are gated on availability and the off state")
    void privateModeGating() {
        PrivacyPanel panel = new PrivacyPanel();
        // Enable needs the same manageable-tor + polkit gate as Start, and is
        // offered from OFF; Disable/Verify need an active mode, so both are off.
        assertEquals(panel.torManageable() && panel.polkitAvailable(), panel.enableEnabled(),
                "enable needs tor + init + polkit, and the mode is off");
        assertFalse(panel.disableEnabled(), "nothing to disable while the mode is off");
        assertFalse(panel.verifyEnabled(), "verify only makes sense while the mode is on");
    }
}
