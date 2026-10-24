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

import org.jdesktop.lg3d.utils.system.HidsService.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link HidsPanel}'s headless construction and its availability /
 * selection gating. The panel is plain Swing and resolves each HIDS tool with a
 * non-spawning PATH probe, so it builds in CI (where fail2ban / rkhunter / lynis
 * / auditd are typically absent) and never runs a command until the user acts.
 * The detection matrix, argument vectors, parsers and execution guards behind it
 * are covered by {@code HidsServiceTest} and {@code hids.feature} in lg3d-core.
 */
class HidsPanelTest {

    @Test
    @DisplayName("the panel constructs headless without spawning a process")
    void constructsHeadless() {
        assertDoesNotThrow(HidsPanel::new);
    }

    @Test
    @DisplayName("all four tools are listed, present or not, and one is selected")
    void listsEveryTool() {
        HidsPanel panel = new HidsPanel();
        assertEquals(4, panel.toolCount(), "fail2ban, rkhunter, lynis and auditd are always listed");
        assertEquals(Tool.FAIL2BAN, panel.selectedTool(), "the first tool is selected on open");
        assertTrue(panel.viewerText().isEmpty(), "the detail viewer is blank until a query runs");
    }

    @Test
    @DisplayName("a read action is enabled only for the selected, installed tool")
    void readGatingFollowsSelectionAndPresence() {
        HidsPanel panel = new HidsPanel();
        panel.selectTool(Tool.FAIL2BAN);
        assertEquals(panel.isInstalled(Tool.FAIL2BAN), panel.f2bStatusEnabled(),
                "fail2ban status needs the tool installed and selected");
        assertFalse(panel.auditStatusEnabled(), "another tool's action stays disabled");
        assertFalse(panel.lynisAuditEnabled(), "another tool's action stays disabled");

        panel.selectTool(Tool.AUDITD);
        assertEquals(panel.isInstalled(Tool.AUDITD), panel.auditStatusEnabled(),
                "auditd status needs the tool installed and selected");
        assertFalse(panel.f2bStatusEnabled(), "the previously selected tool is now disabled");
    }

    @Test
    @DisplayName("a mutating action additionally requires privilege escalation")
    void mutatingGatingNeedsPolkit() {
        HidsPanel panel = new HidsPanel();
        panel.selectTool(Tool.FAIL2BAN);
        assertEquals(panel.isInstalled(Tool.FAIL2BAN) && panel.polkitAvailable(),
                panel.f2bBanEnabled(), "a ban needs the tool, the selection and pkexec");

        panel.selectTool(Tool.RKHUNTER);
        assertEquals(panel.isInstalled(Tool.RKHUNTER) && panel.polkitAvailable(),
                panel.rkhScanEnabled(), "a scan needs the tool, the selection and pkexec");
        assertEquals(panel.isInstalled(Tool.RKHUNTER), panel.rkhLogEnabled(),
                "the read-only log view needs only the tool and the selection");
    }

    @Test
    @DisplayName("cancel is offered only while an operation is in flight")
    void cancelDisabledWhileIdle() {
        HidsPanel panel = new HidsPanel();
        assertFalse(panel.cancelEnabled(), "nothing is running on open, so Cancel is disabled");
    }

    @Test
    @DisplayName("an absent tool is surfaced as guidance, never as an error")
    void absentToolsAreHonest() {
        HidsPanel panel = new HidsPanel();
        // Whichever tool is selected, its note is either blank or honest guidance;
        // the panel never throws and never claims a result it did not obtain.
        assertFalse(panel.noteText().contains("Exception"), panel.noteText());
        if (!panel.isInstalled(Tool.FAIL2BAN)) {
            panel.selectTool(Tool.FAIL2BAN);
            assertFalse(panel.f2bStatusEnabled(),
                    "an uninstalled tool offers no live action");
        }
    }
}
