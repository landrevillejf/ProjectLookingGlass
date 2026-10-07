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
package org.jdesktop.lg3d.utils.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jdesktop.lg3d.utils.system.LfsUpdateService.Action;
import org.jdesktop.lg3d.utils.system.LfsUpdateService.CheckVerdict;
import org.jdesktop.lg3d.utils.system.LfsUpdateService.StatusSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link LfsUpdateService}: the {@code lfs-update} command
 * vectors, the mutating/read-only classification, ANSI stripping, the
 * exit-code-as-state check verdict, and the lenient {@code status} parsing. All
 * pure - no process is spawned and no {@code lfs-update} binary is needed.
 */
class LfsUpdateServiceTest {

    @Test
    @DisplayName("each action maps to its lfs-update sub-command vector")
    void commandVectors() {
        assertEquals(List.of("lfs-update", "check"), LfsUpdateService.buildCommand(Action.CHECK));
        assertEquals(List.of("lfs-update", "status"), LfsUpdateService.buildCommand(Action.STATUS));
        assertEquals(List.of("lfs-update", "upgrade"), LfsUpdateService.buildCommand(Action.UPGRADE));
        assertTrue(LfsUpdateService.buildCommand(null).isEmpty());
    }

    @Test
    @DisplayName("only upgrade is mutating")
    void mutatingClassification() {
        assertTrue(LfsUpdateService.isMutating(Action.UPGRADE));
        assertFalse(LfsUpdateService.isMutating(Action.CHECK));
        assertFalse(LfsUpdateService.isMutating(Action.STATUS));
        assertFalse(LfsUpdateService.isMutating(null));
    }

    @Test
    @DisplayName("ANSI colour/cursor escapes are stripped, text is preserved")
    void stripsAnsi() {
        assertEquals("Updates available: 3",
                LfsUpdateService.stripAnsi("\u001B[1;32mUpdates available:\u001B[0m 3"));
        assertEquals("plain", LfsUpdateService.stripAnsi("plain"));
        assertEquals("", LfsUpdateService.stripAnsi(""));
        assertEquals("", LfsUpdateService.stripAnsi(null));
        // a 256-colour SGR and a cursor move are both removed
        assertEquals("ok",
                LfsUpdateService.stripAnsi("\u001B[38;5;208mok\u001B[H"));
    }

    @Test
    @DisplayName("check exit code 1 = up to date, 0 = updates, else error")
    void interpretsCheckExitCode() {
        assertEquals(CheckVerdict.UP_TO_DATE, LfsUpdateService.interpretCheck(1));
        assertEquals(CheckVerdict.UPDATES_AVAILABLE, LfsUpdateService.interpretCheck(0));
        assertEquals(CheckVerdict.ERROR, LfsUpdateService.interpretCheck(2));
        assertEquals(CheckVerdict.ERROR, LfsUpdateService.interpretCheck(-1));
    }

    @Test
    @DisplayName("status parses KEY=value lines, case-insensitively")
    void parsesEqualsStatus() {
        StatusSnapshot s = LfsUpdateService.parseStatus(
                "LFS_VERSION=12.3\nINSTALLED_PACKAGES=412\nUPGRADABLE=7\n");
        assertFalse(s.isEmpty());
        assertEquals("12.3", s.version());
        assertEquals(Integer.valueOf(412), s.installed());
        assertEquals(Integer.valueOf(7), s.upgradable());
        assertEquals("12.3", s.get("lfs_version"));
    }

    @Test
    @DisplayName("status parses 'key: value' lines and a leading integer count")
    void parsesColonStatus() {
        StatusSnapshot s = LfsUpdateService.parseStatus(
                "System version: 12.3\nInstalled packages: 412 total\nUpgradable: 7 available\n");
        assertEquals("12.3", s.version());
        assertEquals(Integer.valueOf(412), s.installed(), "leading int extracted from '412 total'");
        assertEquals(Integer.valueOf(7), s.upgradable());
    }

    @Test
    @DisplayName("status is ANSI-stripped before parsing and kept verbatim in raw()")
    void stripsAnsiBeforeParsing() {
        StatusSnapshot s = LfsUpdateService.parseStatus(
                "\u001B[1mLFS_VERSION\u001B[0m=\u001B[32m12.3\u001B[0m\n");
        assertEquals("12.3", s.version());
        assertFalse(s.raw().contains("\u001B"), "raw() holds no escape bytes");
        assertTrue(s.raw().contains("LFS_VERSION=12.3"));
    }

    @Test
    @DisplayName("unrecognised or empty status degrades to an empty snapshot")
    void statusDegradesGracefully() {
        assertTrue(LfsUpdateService.parseStatus("").isEmpty());
        assertTrue(LfsUpdateService.parseStatus(null).isEmpty());
        StatusSnapshot noise = LfsUpdateService.parseStatus("hello world\nno pairs here\n");
        assertTrue(noise.isEmpty(), "prose without key/value pairs yields no fields");
        assertNull(noise.version());
        assertNull(noise.installed());
        assertNull(noise.upgradable());
    }

    @Test
    @DisplayName("missing individual fields read back as null, not zero")
    void partialStatus() {
        StatusSnapshot s = LfsUpdateService.parseStatus("UPGRADABLE=2\n");
        assertNull(s.version());
        assertNull(s.installed());
        assertEquals(Integer.valueOf(2), s.upgradable());
    }
}
