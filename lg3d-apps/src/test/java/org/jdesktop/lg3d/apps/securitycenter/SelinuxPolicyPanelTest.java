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

import org.jdesktop.lg3d.apps.securitycenter.SelinuxPolicyPanel.Area;
import org.jdesktop.lg3d.utils.system.SecurityService.PolicyTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SelinuxPolicyPanel}'s headless construction and its availability /
 * selection gating. The panel is plain Swing and resolves each policy tool with a
 * non-spawning PATH probe, so it builds in CI (where semanage / restorecon /
 * semodule / load_policy / ausearch / audit2allow are typically absent) and never
 * runs a command until the user acts. The vectors, parameter validation, parsers
 * and execution guards behind it are covered by {@code SecurityServiceTest} and
 * {@code selinux-policy.feature} in lg3d-core.
 */
class SelinuxPolicyPanelTest {

    @Test
    @DisplayName("the panel constructs headless without spawning a process")
    void constructsHeadless() {
        assertDoesNotThrow(SelinuxPolicyPanel::new);
    }

    @Test
    @DisplayName("all seven areas are listed, present or not, and one is selected")
    void listsEveryArea() {
        SelinuxPolicyPanel panel = new SelinuxPolicyPanel();
        assertEquals(7, panel.areaCount(),
                "file contexts, booleans, ports, relabel, modules, reload and AVC are always listed");
        assertEquals(Area.FILE_CONTEXT, panel.selectedArea(), "the first area is selected on open");
        assertTrue(panel.viewerText().isEmpty(), "the detail viewer is blank until a query runs");
    }

    @Test
    @DisplayName("a read action is enabled only for the selected area's installed tool")
    void readGatingFollowsSelectionAndPresence() {
        SelinuxPolicyPanel panel = new SelinuxPolicyPanel();
        panel.selectArea(Area.FILE_CONTEXT);
        assertEquals(panel.isToolPresent(PolicyTool.SEMANAGE), panel.fcListEnabled(),
                "semanage fcontext -l needs semanage installed and the area selected");
        assertFalse(panel.reloadEnabled(), "another area's action stays disabled");
        assertFalse(panel.avcReadEnabled(), "another area's action stays disabled");

        panel.selectArea(Area.AVC);
        assertEquals(panel.isToolPresent(PolicyTool.AUSEARCH), panel.avcReadEnabled(),
                "reading AVC denials needs ausearch installed and the area selected");
        assertFalse(panel.fcListEnabled(), "the previously selected area is now disabled");
    }

    @Test
    @DisplayName("a destructive write additionally requires privilege escalation")
    void mutatingGatingNeedsPolkit() {
        SelinuxPolicyPanel panel = new SelinuxPolicyPanel();
        panel.selectArea(Area.FILE_CONTEXT);
        assertEquals(panel.isToolPresent(PolicyTool.SEMANAGE) && panel.polkitAvailable(),
                panel.fcAddEnabled(), "an fcontext add needs semanage, the selection and pkexec");

        panel.selectArea(Area.RELABEL);
        assertEquals(panel.isToolPresent(PolicyTool.RESTORECON) && panel.polkitAvailable(),
                panel.relabelEnabled(), "a relabel needs restorecon, the selection and pkexec");

        panel.selectArea(Area.MODULE);
        assertEquals(panel.isToolPresent(PolicyTool.SEMODULE), panel.modListEnabled(),
                "the read-only module list needs only semodule and the selection");
    }

    @Test
    @DisplayName("the AVC draft is a read action gated on audit2allow, not on polkit")
    void draftGatingNeedsAudit2AllowOnly() {
        SelinuxPolicyPanel panel = new SelinuxPolicyPanel();
        panel.selectArea(Area.AVC);
        assertEquals(panel.isToolPresent(PolicyTool.AUDIT2ALLOW), panel.avcDraftEnabled(),
                "generating a draft only prints text, so it needs audit2allow but never pkexec");
    }

    @Test
    @DisplayName("cancel is offered only while an operation is in flight")
    void cancelDisabledWhileIdle() {
        SelinuxPolicyPanel panel = new SelinuxPolicyPanel();
        assertFalse(panel.cancelEnabled(), "nothing is running on open, so Cancel is disabled");
    }

    @Test
    @DisplayName("an absent tool is surfaced as guidance, never as an error")
    void absentToolsAreHonest() {
        SelinuxPolicyPanel panel = new SelinuxPolicyPanel();
        // Whichever area is selected, its note is either blank or honest guidance;
        // the panel never throws and never claims a result it did not obtain.
        assertFalse(panel.noteText().contains("Exception"), panel.noteText());
        if (!panel.isToolPresent(PolicyTool.SEMANAGE)) {
            panel.selectArea(Area.FILE_CONTEXT);
            assertFalse(panel.fcListEnabled(), "an uninstalled tool offers no live action");
        }
    }
}
