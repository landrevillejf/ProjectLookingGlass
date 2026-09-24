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
package org.lpmconsole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link LPMCommand} enum: the canonical CLI name of each constant,
 * the read-only vs mutating split, and that only the four destructive mutations
 * require an explicit confirmation.
 */
class LPMCommandTest {

    @Test
    @DisplayName("every command maps to a non-empty CLI name")
    void allHaveCommandNames() {
        for (LPMCommand c : LPMCommand.values()) {
            assertNotNull(c.getCommandName(), c.name());
            assertFalse(c.getCommandName().isBlank(), c.name());
        }
    }

    @Test
    @DisplayName("representative commands map to their canonical names")
    void canonicalNames() {
        assertEquals("list", LPMCommand.LIST.getCommandName());
        assertEquals("update-db", LPMCommand.UPDATE_DB.getCommandName());
        assertEquals("list-profiles", LPMCommand.LIST_PROFILES.getCommandName());
        assertEquals("rebuild-kernel", LPMCommand.REBUILD_KERNEL.getCommandName());
        assertEquals("autoremove", LPMCommand.AUTOREMOVE.getCommandName());
    }

    @Test
    @DisplayName("read-only commands are not mutating")
    void readOnlyCommands() {
        assertFalse(LPMCommand.LIST.isMutating());
        assertFalse(LPMCommand.SEARCH.isMutating());
        assertFalse(LPMCommand.INFO.isMutating());
        assertFalse(LPMCommand.UPGRADABLE.isMutating());
        assertFalse(LPMCommand.VERIFY.isMutating());
        assertFalse(LPMCommand.VERSION.isMutating());
    }

    @Test
    @DisplayName("write commands are mutating")
    void mutatingCommands() {
        assertTrue(LPMCommand.INSTALL.isMutating());
        assertTrue(LPMCommand.REMOVE.isMutating());
        assertTrue(LPMCommand.UPDATE.isMutating());
        assertTrue(LPMCommand.UPGRADE.isMutating());
        assertTrue(LPMCommand.HOLD.isMutating());
        assertTrue(LPMCommand.CLEAN.isMutating());
        assertTrue(LPMCommand.BUILD.isMutating());
    }

    @Test
    @DisplayName("only the four destructive mutations require confirmation")
    void confirmationIsReservedForDestructiveOps() {
        Set<LPMCommand> expected = EnumSet.of(
                LPMCommand.REMOVE, LPMCommand.AUTOREMOVE,
                LPMCommand.UPGRADE, LPMCommand.REBUILD_KERNEL);
        for (LPMCommand c : LPMCommand.values()) {
            assertEquals(expected.contains(c), c.requiresConfirmation(),
                    () -> "unexpected confirmation for " + c.name());
        }
    }

    @Test
    @DisplayName("a mutating-but-reversible command needs no confirmation")
    void installDoesNotRequireConfirmation() {
        assertTrue(LPMCommand.INSTALL.isMutating());
        assertFalse(LPMCommand.INSTALL.requiresConfirmation());
    }
}
