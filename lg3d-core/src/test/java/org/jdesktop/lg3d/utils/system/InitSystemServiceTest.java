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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jdesktop.lg3d.utils.system.InitSystemService.InitSystem;
import org.jdesktop.lg3d.utils.system.InitSystemService.Operation;
import org.jdesktop.lg3d.utils.system.InitSystemService.Probes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link InitSystemService}: the init-detection matrix and
 * the per-init operation → argument-vector mapping, both pure (no real init
 * system, no process is spawned). These prove the vectors are exactly the ones
 * the LFS builder's {@code 06b-service-management.sh} abstraction uses, that no
 * shell interpolation is involved, and that unsupported operations degrade to
 * an empty vector.
 */
class InitSystemServiceTest {

    // ------------------------------------------------------------------
    // Detection matrix

    private static Probes probes(boolean systemctl, boolean systemdDir,
                                 boolean rcService, boolean initdDir,
                                 boolean sv, boolean etcSv,
                                 boolean s6Svscan, boolean etcS6) {
        return new Probes(systemctl, systemdDir, rcService, initdDir, sv, etcSv, s6Svscan, etcS6);
    }

    @Test
    @DisplayName("systemctl + /usr/lib/systemd detects systemd")
    void detectsSystemd() {
        assertEquals(InitSystem.SYSTEMD,
                InitSystemService.detect(probes(true, true, false, false, false, false, false, false)));
    }

    @Test
    @DisplayName("rc-service + /etc/init.d detects openrc")
    void detectsOpenrc() {
        assertEquals(InitSystem.OPENRC,
                InitSystemService.detect(probes(false, false, true, true, false, false, false, false)));
    }

    @Test
    @DisplayName("sv + /etc/sv detects runit")
    void detectsRunit() {
        assertEquals(InitSystem.RUNIT,
                InitSystemService.detect(probes(false, false, false, false, true, true, false, false)));
    }

    @Test
    @DisplayName("s6-svscan + /etc/s6 detects s6")
    void detectsS6() {
        assertEquals(InitSystem.S6,
                InitSystemService.detect(probes(false, false, false, false, false, false, true, true)));
    }

    @Test
    @DisplayName("/etc/init.d alone falls back to sysvinit")
    void fallsBackToSysvinit() {
        assertEquals(InitSystem.SYSVINIT,
                InitSystemService.detect(probes(false, false, false, true, false, false, false, false)));
    }

    @Test
    @DisplayName("no supervisor and no /etc/init.d is UNKNOWN")
    void detectsUnknown() {
        assertEquals(InitSystem.UNKNOWN,
                InitSystemService.detect(probes(false, false, false, false, false, false, false, false)));
        assertEquals(InitSystem.UNKNOWN, InitSystemService.detect((Probes) null));
    }

    @Test
    @DisplayName("a tool without its directory does not select the backend")
    void toolWithoutDirectoryIsNotEnough() {
        // systemctl present but /usr/lib/systemd missing, /etc/init.d present → sysvinit
        assertEquals(InitSystem.SYSVINIT,
                InitSystemService.detect(probes(true, false, false, true, false, false, false, false)));
    }

    @Test
    @DisplayName("detection order prefers systemd over the others")
    void detectionOrderPrefersSystemd() {
        assertEquals(InitSystem.SYSTEMD,
                InitSystemService.detect(probes(true, true, true, true, true, true, true, true)));
    }

    // ------------------------------------------------------------------
    // systemd vectors

    @Test
    @DisplayName("systemd operation vectors")
    void systemdVectors() {
        assertEquals(List.of("systemctl", "start", "sshd"),
                InitSystemService.buildCommand(InitSystem.SYSTEMD, Operation.START, "sshd"));
        assertEquals(List.of("systemctl", "stop", "sshd"),
                InitSystemService.buildCommand(InitSystem.SYSTEMD, Operation.STOP, "sshd"));
        assertEquals(List.of("systemctl", "restart", "sshd"),
                InitSystemService.buildCommand(InitSystem.SYSTEMD, Operation.RESTART, "sshd"));
        assertEquals(List.of("systemctl", "status", "sshd"),
                InitSystemService.buildCommand(InitSystem.SYSTEMD, Operation.STATUS, "sshd"));
        assertEquals(List.of("systemctl", "enable", "sshd"),
                InitSystemService.buildCommand(InitSystem.SYSTEMD, Operation.ENABLE, "sshd"));
        assertEquals(List.of("systemctl", "disable", "sshd"),
                InitSystemService.buildCommand(InitSystem.SYSTEMD, Operation.DISABLE, "sshd"));
        assertEquals(List.of("systemctl", "list-units", "--type=service", "--all",
                        "--no-legend", "--no-pager"),
                InitSystemService.buildCommand(InitSystem.SYSTEMD, Operation.LIST, null));
    }

    // ------------------------------------------------------------------
    // openrc vectors

    @Test
    @DisplayName("openrc operation vectors")
    void openrcVectors() {
        assertEquals(List.of("rc-service", "net", "start"),
                InitSystemService.buildCommand(InitSystem.OPENRC, Operation.START, "net"));
        assertEquals(List.of("rc-service", "net", "stop"),
                InitSystemService.buildCommand(InitSystem.OPENRC, Operation.STOP, "net"));
        assertEquals(List.of("rc-service", "net", "restart"),
                InitSystemService.buildCommand(InitSystem.OPENRC, Operation.RESTART, "net"));
        assertEquals(List.of("rc-service", "net", "status"),
                InitSystemService.buildCommand(InitSystem.OPENRC, Operation.STATUS, "net"));
        assertEquals(List.of("rc-update", "add", "net", "default"),
                InitSystemService.buildCommand(InitSystem.OPENRC, Operation.ENABLE, "net"));
        assertEquals(List.of("rc-update", "del", "net"),
                InitSystemService.buildCommand(InitSystem.OPENRC, Operation.DISABLE, "net"));
        assertEquals(List.of("rc-status", "--all"),
                InitSystemService.buildCommand(InitSystem.OPENRC, Operation.LIST, null));
    }

    // ------------------------------------------------------------------
    // runit vectors

    @Test
    @DisplayName("runit operation vectors")
    void runitVectors() {
        assertEquals(List.of("sv", "up", "sshd"),
                InitSystemService.buildCommand(InitSystem.RUNIT, Operation.START, "sshd"));
        assertEquals(List.of("sv", "down", "sshd"),
                InitSystemService.buildCommand(InitSystem.RUNIT, Operation.STOP, "sshd"));
        assertEquals(List.of("sv", "restart", "sshd"),
                InitSystemService.buildCommand(InitSystem.RUNIT, Operation.RESTART, "sshd"));
        assertEquals(List.of("sv", "status", "sshd"),
                InitSystemService.buildCommand(InitSystem.RUNIT, Operation.STATUS, "sshd"));
        assertEquals(List.of("ln", "-sfn", "/etc/sv/sshd", "/var/service/sshd"),
                InitSystemService.buildCommand(InitSystem.RUNIT, Operation.ENABLE, "sshd"));
        assertEquals(List.of("rm", "-f", "/var/service/sshd"),
                InitSystemService.buildCommand(InitSystem.RUNIT, Operation.DISABLE, "sshd"));
        assertEquals(List.of("ls", "/etc/sv"),
                InitSystemService.buildCommand(InitSystem.RUNIT, Operation.LIST, null));
    }

    // ------------------------------------------------------------------
    // s6 vectors

    @Test
    @DisplayName("s6 operation vectors")
    void s6Vectors() {
        assertEquals(List.of("s6-svc", "-u", "/etc/s6/sv/sshd"),
                InitSystemService.buildCommand(InitSystem.S6, Operation.START, "sshd"));
        assertEquals(List.of("s6-svc", "-d", "/etc/s6/sv/sshd"),
                InitSystemService.buildCommand(InitSystem.S6, Operation.STOP, "sshd"));
        assertEquals(List.of("s6-svc", "-r", "/etc/s6/sv/sshd"),
                InitSystemService.buildCommand(InitSystem.S6, Operation.RESTART, "sshd"));
        assertEquals(List.of("s6-svstat", "/etc/s6/sv/sshd"),
                InitSystemService.buildCommand(InitSystem.S6, Operation.STATUS, "sshd"));
        assertEquals(List.of("ln", "-sfn", "/etc/s6/sv/sshd", "/etc/s6/current/sshd"),
                InitSystemService.buildCommand(InitSystem.S6, Operation.ENABLE, "sshd"));
        assertEquals(List.of("rm", "-f", "/etc/s6/current/sshd"),
                InitSystemService.buildCommand(InitSystem.S6, Operation.DISABLE, "sshd"));
        assertEquals(List.of("ls", "/etc/s6/sv"),
                InitSystemService.buildCommand(InitSystem.S6, Operation.LIST, null));
    }

    // ------------------------------------------------------------------
    // sysvinit vectors

    @Test
    @DisplayName("sysvinit operation vectors")
    void sysvinitVectors() {
        assertEquals(List.of("/etc/init.d/sshd", "start"),
                InitSystemService.buildCommand(InitSystem.SYSVINIT, Operation.START, "sshd"));
        assertEquals(List.of("/etc/init.d/sshd", "stop"),
                InitSystemService.buildCommand(InitSystem.SYSVINIT, Operation.STOP, "sshd"));
        assertEquals(List.of("/etc/init.d/sshd", "restart"),
                InitSystemService.buildCommand(InitSystem.SYSVINIT, Operation.RESTART, "sshd"));
        assertEquals(List.of("/etc/init.d/sshd", "status"),
                InitSystemService.buildCommand(InitSystem.SYSVINIT, Operation.STATUS, "sshd"));
        assertEquals(List.of("ls", "/etc/init.d"),
                InitSystemService.buildCommand(InitSystem.SYSVINIT, Operation.LIST, null));
    }

    // ------------------------------------------------------------------
    // Support / degradation

    @Test
    @DisplayName("sysvinit has no enable/disable")
    void sysvinitHasNoEnableDisable() {
        assertFalse(InitSystemService.isSupported(InitSystem.SYSVINIT, Operation.ENABLE));
        assertFalse(InitSystemService.isSupported(InitSystem.SYSVINIT, Operation.DISABLE));
        assertTrue(InitSystemService.buildCommand(InitSystem.SYSVINIT, Operation.ENABLE, "sshd").isEmpty());
        assertTrue(InitSystemService.buildCommand(InitSystem.SYSVINIT, Operation.DISABLE, "sshd").isEmpty());
        // but the other ops are supported
        assertTrue(InitSystemService.isSupported(InitSystem.SYSVINIT, Operation.START));
        assertTrue(InitSystemService.isSupported(InitSystem.SYSVINIT, Operation.LIST));
    }

    @Test
    @DisplayName("UNKNOWN supports nothing")
    void unknownSupportsNothing() {
        for (Operation op : Operation.values()) {
            assertFalse(InitSystemService.isSupported(InitSystem.UNKNOWN, op));
            assertTrue(InitSystemService.buildCommand(InitSystem.UNKNOWN, op, "sshd").isEmpty());
        }
    }

    @Test
    @DisplayName("null init/op or a missing service name yields an empty vector")
    void invalidArgumentsYieldEmpty() {
        assertTrue(InitSystemService.buildCommand(null, Operation.START, "sshd").isEmpty());
        assertTrue(InitSystemService.buildCommand(InitSystem.SYSTEMD, null, "sshd").isEmpty());
        assertTrue(InitSystemService.buildCommand(InitSystem.SYSTEMD, Operation.START, null).isEmpty());
        assertTrue(InitSystemService.buildCommand(InitSystem.SYSTEMD, Operation.START, "  ").isEmpty());
    }

    @Test
    @DisplayName("LIST ignores the service name")
    void listIgnoresServiceName() {
        assertEquals(InitSystemService.buildCommand(InitSystem.SYSTEMD, Operation.LIST, null),
                InitSystemService.buildCommand(InitSystem.SYSTEMD, Operation.LIST, "ignored"));
    }

    @Test
    @DisplayName("service name is trimmed, never shell-interpolated")
    void serviceNameIsTrimmedAsOneToken() {
        List<String> cmd = InitSystemService.buildCommand(InitSystem.SYSTEMD, Operation.START, "  sshd  ");
        assertEquals(List.of("systemctl", "start", "sshd"), cmd);
        // a name with a shell metacharacter stays a single literal argument
        List<String> weird = InitSystemService.buildCommand(InitSystem.SYSTEMD, Operation.START, "a;b|c");
        assertEquals(3, weird.size());
        assertEquals("a;b|c", weird.get(2));
    }

    @Test
    @DisplayName("mutating vs read-only classification")
    void mutatingClassification() {
        assertTrue(InitSystemService.isMutating(Operation.START));
        assertTrue(InitSystemService.isMutating(Operation.STOP));
        assertTrue(InitSystemService.isMutating(Operation.RESTART));
        assertTrue(InitSystemService.isMutating(Operation.ENABLE));
        assertTrue(InitSystemService.isMutating(Operation.DISABLE));
        assertFalse(InitSystemService.isMutating(Operation.STATUS));
        assertFalse(InitSystemService.isMutating(Operation.LIST));
        assertFalse(InitSystemService.isMutating(null));
    }

    @Test
    @DisplayName("every supported non-list op targets the given service")
    void everyNonListOpUsesTheService() {
        for (InitSystem init : new InitSystem[] {
                InitSystem.SYSTEMD, InitSystem.OPENRC, InitSystem.RUNIT,
                InitSystem.S6, InitSystem.SYSVINIT }) {
            for (Operation op : Operation.values()) {
                if (op == Operation.LIST || !InitSystemService.isSupported(init, op)) {
                    continue;
                }
                List<String> cmd = InitSystemService.buildCommand(init, op, "tor");
                assertFalse(cmd.isEmpty(), init + "/" + op + " produces a command");
                boolean mentions = cmd.stream().anyMatch(a -> a.contains("tor"));
                assertTrue(mentions, init + "/" + op + " references the service name");
            }
        }
    }
}
