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
import org.jdesktop.lg3d.utils.system.HidsService.AuditdStatus;
import org.jdesktop.lg3d.utils.system.HidsService.Fail2BanStatus;
import org.jdesktop.lg3d.utils.system.HidsService.JailStatus;
import org.jdesktop.lg3d.utils.system.HidsService.LynisResult;
import org.jdesktop.lg3d.utils.system.HidsService.Operation;
import org.jdesktop.lg3d.utils.system.HidsService.Probes;
import org.jdesktop.lg3d.utils.system.HidsService.RkhunterResult;
import org.jdesktop.lg3d.utils.system.HidsService.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link HidsService} (the §4.10 host-intrusion-detection
 * seam). Detection, command building and output parsing are pure: no HIDS tool
 * is installed, no process is spawned (the execution tests only exercise the
 * guards that return before {@link ProcessRunner} / {@link PrivilegedRunner}),
 * so the suite is CI-safe on any host.
 */
class HidsServiceTest {

    private static Probes none() {
        return new Probes(false, false, false, false, false);
    }

    private static Probes all() {
        return new Probes(true, true, true, true, true);
    }

    // ------------------------------------------------------------------
    // Detection matrix

    @Test
    @DisplayName("each tool is present only when its binary is on PATH")
    void detectionMatrix() {
        assertTrue(HidsService.isPresent(all(), Tool.FAIL2BAN));
        assertTrue(HidsService.isPresent(all(), Tool.RKHUNTER));
        assertTrue(HidsService.isPresent(all(), Tool.LYNIS));
        assertTrue(HidsService.isPresent(all(), Tool.AUDITD));
        assertFalse(HidsService.isPresent(none(), Tool.FAIL2BAN));
        assertFalse(HidsService.isPresent(none(), Tool.RKHUNTER));
        assertFalse(HidsService.isPresent(none(), Tool.LYNIS));
        assertFalse(HidsService.isPresent(none(), Tool.AUDITD));
    }

    @Test
    @DisplayName("auditd is present when only ausearch (or only auditctl) exists")
    void auditdEitherBinary() {
        assertTrue(HidsService.isPresent(new Probes(false, false, false, false, true), Tool.AUDITD));
        assertTrue(HidsService.isPresent(new Probes(false, false, false, true, false), Tool.AUDITD));
    }

    @Test
    @DisplayName("null probes / null tool degrade rather than throw")
    void detectionNullGuards() {
        assertFalse(HidsService.isPresent(null, Tool.FAIL2BAN));
        assertFalse(HidsService.isPresent(all(), null));
        assertTrue(HidsService.installedTools(null).isEmpty());
    }

    @Test
    @DisplayName("installedTools returns the present tools in a stable order")
    void installedToolsOrdered() {
        Probes p = new Probes(true, false, true, false, true);
        assertEquals(List.of(Tool.FAIL2BAN, Tool.LYNIS, Tool.AUDITD), HidsService.installedTools(p));
        assertTrue(HidsService.installedTools(none()).isEmpty());
    }

    @Test
    @DisplayName("describeTool labels every tool and tolerates null")
    void describeTool() {
        assertEquals("fail2ban (intrusion prevention)", HidsService.describeTool(Tool.FAIL2BAN));
        assertEquals("rkhunter (rootkit scan)", HidsService.describeTool(Tool.RKHUNTER));
        assertEquals("lynis (security audit)", HidsService.describeTool(Tool.LYNIS));
        assertEquals("auditd (kernel audit trail)", HidsService.describeTool(Tool.AUDITD));
        assertEquals("Unknown", HidsService.describeTool(null));
    }

    // ------------------------------------------------------------------
    // Command vectors (no shell interpolation)

    @Test
    @DisplayName("read-only vectors are exact")
    void readVectors() {
        assertEquals(List.of("fail2ban-client", "status"),
                HidsService.buildCommand(Operation.FAIL2BAN_STATUS));
        assertEquals(List.of("fail2ban-client", "status", "sshd"),
                HidsService.buildCommand(Operation.FAIL2BAN_JAIL_STATUS, "sshd"));
        assertEquals(List.of("auditctl", "-s"),
                HidsService.buildCommand(Operation.AUDITCTL_STATUS));
        assertEquals(List.of("ausearch", "-m", "USER_AUTH", "-ts", "recent"),
                HidsService.buildCommand(Operation.AUSEARCH, "USER_AUTH"));
    }

    @Test
    @DisplayName("mutating vectors are exact")
    void mutatingVectors() {
        assertEquals(List.of("fail2ban-client", "set", "sshd", "banip", "1.2.3.4"),
                HidsService.buildCommand(Operation.FAIL2BAN_BAN, "sshd", "1.2.3.4"));
        assertEquals(List.of("fail2ban-client", "set", "sshd", "unbanip", "1.2.3.4"),
                HidsService.buildCommand(Operation.FAIL2BAN_UNBAN, "sshd", "1.2.3.4"));
        assertEquals(List.of("rkhunter", "--check", "--sk", "--nocolors"),
                HidsService.buildCommand(Operation.RKHUNTER_CHECK));
        assertEquals(List.of("rkhunter", "--propupd"),
                HidsService.buildCommand(Operation.RKHUNTER_PROPUPD));
        assertEquals(List.of("lynis", "audit", "system", "--no-colors", "--quick"),
                HidsService.buildCommand(Operation.LYNIS_AUDIT));
    }

    @Test
    @DisplayName("a missing or malformed parameter yields an empty vector, never a partial command")
    void parameterValidation() {
        assertTrue(HidsService.buildCommand(Operation.FAIL2BAN_JAIL_STATUS).isEmpty());
        assertTrue(HidsService.buildCommand(Operation.FAIL2BAN_JAIL_STATUS, "  ").isEmpty());
        assertTrue(HidsService.buildCommand(Operation.FAIL2BAN_BAN, "sshd").isEmpty());
        assertTrue(HidsService.buildCommand(Operation.FAIL2BAN_BAN, "sshd", "1.2.3.4 5").isEmpty());
        assertTrue(HidsService.buildCommand(Operation.AUSEARCH).isEmpty());
        assertTrue(HidsService.buildCommand(null).isEmpty());
    }

    @Test
    @DisplayName("isToken rejects blank, whitespace-bearing and over-long parameters")
    void tokenValidation() {
        assertTrue(HidsService.isToken("sshd"));
        assertFalse(HidsService.isToken(null));
        assertFalse(HidsService.isToken(""));
        assertFalse(HidsService.isToken("a b"));
        assertFalse(HidsService.isToken("x".repeat(65)));
    }

    @Test
    @DisplayName("the mutating flag partitions the operations")
    void mutatingFlags() {
        assertFalse(Operation.FAIL2BAN_STATUS.isMutating());
        assertFalse(Operation.AUDITCTL_STATUS.isMutating());
        assertFalse(Operation.AUSEARCH.isMutating());
        assertTrue(Operation.FAIL2BAN_BAN.isMutating());
        assertTrue(Operation.RKHUNTER_CHECK.isMutating());
        assertTrue(Operation.LYNIS_AUDIT.isMutating());
    }

    // ------------------------------------------------------------------
    // fail2ban parsing

    @Test
    @DisplayName("parses the fail2ban server status: running, jail count and names")
    void parseFail2BanStatus() {
        String out = "Status\n|- Number of jails:\t2\n`- Jail list:\tsshd, postfix\n";
        Fail2BanStatus s = HidsService.parseFail2BanStatus(out);
        assertTrue(s.isRunning());
        assertEquals(List.of("sshd", "postfix"), s.getJails());
        assertEquals("2 jails: sshd, postfix", s.describe());
    }

    @Test
    @DisplayName("blank fail2ban output is an honest not-running")
    void parseFail2BanStatusBlank() {
        Fail2BanStatus s = HidsService.parseFail2BanStatus("");
        assertFalse(s.isRunning());
        assertEquals("not running", s.describe());
        assertEquals("not running", HidsService.parseFail2BanStatus(null).describe());
    }

    @Test
    @DisplayName("parses a jail's ban state and banned addresses")
    void parseFail2BanJail() {
        String out = "Status for the jail: sshd\n"
                + "`- Actions\n"
                + "   |- Currently banned:\t2\n"
                + "   |- Total banned:\t7\n"
                + "   `- Banned IP list:\t1.2.3.4 5.6.7.8\n";
        JailStatus j = HidsService.parseFail2BanJail(out);
        assertEquals("sshd", j.getJail());
        assertEquals(2, j.getCurrentlyBanned());
        assertEquals(7, j.getTotalBanned());
        assertEquals(List.of("1.2.3.4", "5.6.7.8"), j.getBannedIps());
        assertEquals("sshd: 2 banned now, 7 total", j.describe());
    }

    @Test
    @DisplayName("blank jail output degrades to zeroes, never throws")
    void parseFail2BanJailBlank() {
        JailStatus j = HidsService.parseFail2BanJail(null);
        assertEquals("", j.getJail());
        assertEquals(0, j.getCurrentlyBanned());
        assertEquals("jail: 0 banned now, 0 total", j.describe());
    }

    // ------------------------------------------------------------------
    // rkhunter / lynis parsing

    @Test
    @DisplayName("counts rkhunter warning lines and recognises a run")
    void parseRkhunter() {
        String out = "Checking system boot files\n"
                + "Warning: The command '/bin/ls' has been replaced\n"
                + "Warning: Hidden directory found\n"
                + "System checks summary\n";
        RkhunterResult r = HidsService.parseRkhunter(out);
        assertTrue(r.didRun());
        assertEquals(2, r.getWarnings());
        assertEquals("2 warnings", r.describe());
    }

    @Test
    @DisplayName("a clean rkhunter run reports no warnings")
    void parseRkhunterClean() {
        RkhunterResult r = HidsService.parseRkhunter("Checking system binaries\nSystem checks summary\n");
        assertTrue(r.didRun());
        assertEquals(0, r.getWarnings());
        assertEquals("no warnings", r.describe());
        assertEquals("no scan output", HidsService.parseRkhunter("").describe());
    }

    @Test
    @DisplayName("parses the lynis hardening index, suggestions and warnings")
    void parseLynis() {
        String out = "  Hardening index : [72]\n  Suggestions: 12\n  Warnings: 3\n";
        LynisResult l = HidsService.parseLynis(out);
        assertEquals(72, l.getHardeningIndex());
        assertEquals(12, l.getSuggestions());
        assertEquals(3, l.getWarnings());
        assertEquals("hardening index 72, 3 warning(s), 12 suggestion(s)", l.describe());
    }

    @Test
    @DisplayName("a lynis run with no summary reports -1 fields and says so")
    void parseLynisEmpty() {
        LynisResult l = HidsService.parseLynis("no report here");
        assertEquals(-1, l.getHardeningIndex());
        assertEquals("no summary reported", l.describe());
        assertEquals("no summary reported", HidsService.parseLynis(null).describe());
    }

    // ------------------------------------------------------------------
    // auditd parsing

    @Test
    @DisplayName("parses the single-line AUDIT_STATUS form")
    void parseAuditdSingleLine() {
        String out = "AUDIT_STATUS: enabled=1 flag=1 pid=1000 rate_limit=0 "
                + "backlog_limit=8192 lost=0 backlog=2\n";
        AuditdStatus s = HidsService.parseAuditdStatus(out);
        assertTrue(s.isEnabled());
        assertEquals(1, s.getEnabled());
        assertEquals(1000, s.getPid());
        assertEquals(8192, s.getBacklogLimit());
        assertEquals(0, s.getLost());
        assertEquals(2, s.getBacklog());
        assertEquals("enabled, pid 1000, 0 lost", s.describe());
    }

    @Test
    @DisplayName("parses the older key/value line form")
    void parseAuditdLineForm() {
        String out = "enabled 1\nflag 1\npid 4321\nbacklog 0\n";
        AuditdStatus s = HidsService.parseAuditdStatus(out);
        assertTrue(s.isEnabled());
        assertEquals(4321, s.getPid());
        assertEquals(0, s.getBacklog());
    }

    @Test
    @DisplayName("a disabled or blank auditd status is reported honestly")
    void parseAuditdDisabled() {
        AuditdStatus off = HidsService.parseAuditdStatus("AUDIT_STATUS: enabled=0 pid=0");
        assertFalse(off.isEnabled());
        assertEquals("disabled, pid 0", off.describe());
        assertEquals("unknown", HidsService.parseAuditdStatus("").describe());
        assertEquals(-1, HidsService.parseAuditdStatus(null).getEnabled());
    }

    @Test
    @DisplayName("counts ausearch events by record separator, type line, or none")
    void countAusearchEvents() {
        assertEquals(2, HidsService.countAusearchEvents(
                "type=USER_AUTH msg=audit(1)\n----\ntype=USER_LOGIN msg=audit(2)\n----\n"));
        assertEquals(1, HidsService.countAusearchEvents("type=USER_AUTH msg=audit(1)\n"));
        assertEquals(0, HidsService.countAusearchEvents("<no events>\n"));
        assertEquals(0, HidsService.countAusearchEvents(""));
        assertEquals(0, HidsService.countAusearchEvents(null));
    }

    // ------------------------------------------------------------------
    // Execution guards (return before any process is spawned)

    @Test
    @DisplayName("runRead refuses a mutating operation without spawning")
    void runReadRejectsMutating() {
        ProcessRunner.Result r = HidsService.runRead(Operation.FAIL2BAN_BAN, "sshd", "1.2.3.4");
        assertFalse(r.isStarted());
        assertTrue(r.getStderr().contains("mutating"));
    }

    @Test
    @DisplayName("runRead with a missing parameter builds an empty vector and does not spawn")
    void runReadMissingParam() {
        ProcessRunner.Result r = HidsService.runRead(Operation.AUSEARCH);
        assertFalse(r.isStarted());
        assertTrue(r.getStderr().contains("parameter"));
    }

    @Test
    @DisplayName("runRead refuses a null operation")
    void runReadNull() {
        assertFalse(HidsService.runRead(null).isStarted());
    }

    @Test
    @DisplayName("runMutating refuses a read-only operation without escalating")
    void runMutatingRejectsRead() {
        PrivilegedRunner.PrivilegedResult r = HidsService.runMutating(Operation.FAIL2BAN_STATUS);
        assertEquals(PrivilegedRunner.Status.ERROR, r.getStatus());
        assertTrue(r.getMessage().contains("not mutating"));
    }

    @Test
    @DisplayName("runMutating with a missing parameter does not escalate (empty vector)")
    void runMutatingMissingParam() {
        PrivilegedRunner.PrivilegedResult r = HidsService.runMutating(Operation.FAIL2BAN_BAN, "sshd");
        assertEquals(PrivilegedRunner.Status.ERROR, r.getStatus());
        assertTrue(r.getMessage().contains("parameter"));
    }

    @Test
    @DisplayName("runMutating refuses a null operation")
    void runMutatingNull() {
        assertEquals(PrivilegedRunner.Status.ERROR, HidsService.runMutating(null).getStatus());
    }
}
