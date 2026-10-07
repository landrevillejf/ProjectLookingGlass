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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jdesktop.lg3d.utils.system.InitSystemService.InitSystem;
import org.jdesktop.lg3d.utils.system.SecurityService.AppArmorTool;
import org.jdesktop.lg3d.utils.system.SecurityService.NftSummary;
import org.jdesktop.lg3d.utils.system.SecurityService.AppArmorStatus;
import org.jdesktop.lg3d.utils.system.SecurityService.Operation;
import org.jdesktop.lg3d.utils.system.SecurityService.Probes;
import org.jdesktop.lg3d.utils.system.SecurityService.SelinuxMode;
import org.jdesktop.lg3d.utils.system.SecurityService.SshdState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SecurityService}: the §4.6 detection matrix, the exact command
 * vectors (no shell interpolation), the pure output parsers and the non-spawning
 * guards of the {@code run*} helpers. Everything here is pure - no process is
 * started - so it runs headless in CI.
 */
class SecurityServiceTest {

    // ------------------------------------------------------------------
    // Detection matrix

    @Test
    @DisplayName("nft present is reported, absent is not")
    void detectNft() {
        assertTrue(SecurityService.isNftPresent(new Probes(true, false, false, false, false, false)));
        assertFalse(SecurityService.isNftPresent(new Probes(false, false, false, false, false, false)));
        assertFalse(SecurityService.isNftPresent(null));
    }

    @Test
    @DisplayName("selinux present if getenforce OR selinuxenabled exists")
    void detectSelinux() {
        assertTrue(SecurityService.isSelinuxPresent(new Probes(false, false, false, true, false, false)));
        assertTrue(SecurityService.isSelinuxPresent(new Probes(false, false, false, false, true, false)));
        assertFalse(SecurityService.isSelinuxPresent(new Probes(false, false, false, false, false, false)));
        assertFalse(SecurityService.isSelinuxPresent(null));
    }

    @Test
    @DisplayName("sshd present is reported, absent is not")
    void detectSshd() {
        assertTrue(SecurityService.isSshdPresent(new Probes(false, false, false, false, false, true)));
        assertFalse(SecurityService.isSshdPresent(new Probes(false, false, false, false, false, false)));
        assertFalse(SecurityService.isSshdPresent(null));
    }

    @Test
    @DisplayName("apparmor_status is preferred over aa-status; else NONE")
    void detectAppArmorTool() {
        assertEquals(AppArmorTool.APPARMOR_STATUS,
                SecurityService.apparmorTool(new Probes(false, true, true, false, false, false)));
        assertEquals(AppArmorTool.AA_STATUS,
                SecurityService.apparmorTool(new Probes(false, false, true, false, false, false)));
        assertEquals(AppArmorTool.NONE,
                SecurityService.apparmorTool(new Probes(false, false, false, false, false, false)));
        assertEquals(AppArmorTool.NONE, SecurityService.apparmorTool(null));
    }

    @Test
    @DisplayName("live probes never throw and never return null")
    void liveProbesAreSafe() {
        assertDoesNotThrow(() -> {
            Probes p = SecurityService.probe();
            assertNotNull(p);
            assertNotNull(SecurityService.detectAppArmorTool());
            SecurityService.isNftAvailable();
            SecurityService.isSelinuxAvailable();
            SecurityService.isSshdAvailable();
        });
    }

    // ------------------------------------------------------------------
    // Command vectors

    @Test
    @DisplayName("nft list ruleset (read) and nft -f /etc/nftables.conf (apply)")
    void nftVectors() {
        assertEquals(List.of("nft", "list", "ruleset"), SecurityService.nftCommand(Operation.NFT_LIST));
        assertEquals(List.of("nft", "-f", "/etc/nftables.conf"), SecurityService.nftCommand(Operation.NFT_APPLY));
        assertEquals(List.of(), SecurityService.nftCommand(Operation.SELINUX_MODE));
        assertEquals(List.of(), SecurityService.nftCommand(null));
    }

    @Test
    @DisplayName("apparmor status vector follows the resolved tool")
    void apparmorVectors() {
        assertEquals(List.of("apparmor_status"), SecurityService.apparmorCommand(AppArmorTool.APPARMOR_STATUS));
        assertEquals(List.of("aa-status"), SecurityService.apparmorCommand(AppArmorTool.AA_STATUS));
        assertEquals(List.of(), SecurityService.apparmorCommand(AppArmorTool.NONE));
        assertEquals(List.of(), SecurityService.apparmorCommand(null));
    }

    @Test
    @DisplayName("selinux vectors are getenforce and selinuxenabled")
    void selinuxVectors() {
        assertEquals(List.of("getenforce"), SecurityService.selinuxCommand(Operation.SELINUX_MODE));
        assertEquals(List.of("selinuxenabled"), SecurityService.selinuxCommand(Operation.SELINUX_ENABLED));
        assertEquals(List.of(), SecurityService.selinuxCommand(Operation.NFT_LIST));
        assertEquals(List.of(), SecurityService.selinuxCommand(null));
    }

    @Test
    @DisplayName("sshd -t config test ignores the init system")
    void sshdConfigTestVector() {
        assertEquals(List.of("sshd", "-t"), SecurityService.sshdCommand(Operation.SSHD_CONFIG_TEST, InitSystem.UNKNOWN));
        assertEquals(List.of("sshd", "-t"), SecurityService.sshdCommand(Operation.SSHD_CONFIG_TEST, InitSystem.SYSTEMD));
    }

    @Test
    @DisplayName("sshd status/start/stop delegate to the init abstraction (systemd)")
    void sshdLifecycleVectorsSystemd() {
        assertEquals(List.of("systemctl", "status", "sshd"),
                SecurityService.sshdCommand(Operation.SSHD_STATUS, InitSystem.SYSTEMD));
        assertEquals(List.of("systemctl", "start", "sshd"),
                SecurityService.sshdCommand(Operation.SSHD_START, InitSystem.SYSTEMD));
        assertEquals(List.of("systemctl", "stop", "sshd"),
                SecurityService.sshdCommand(Operation.SSHD_STOP, InitSystem.SYSTEMD));
    }

    @Test
    @DisplayName("sshd lifecycle maps per init system (openrc/runit) and empties on UNKNOWN")
    void sshdLifecycleVectorsOtherInit() {
        assertEquals(List.of("rc-service", "sshd", "status"),
                SecurityService.sshdCommand(Operation.SSHD_STATUS, InitSystem.OPENRC));
        assertEquals(List.of("sv", "up", "sshd"),
                SecurityService.sshdCommand(Operation.SSHD_START, InitSystem.RUNIT));
        assertEquals(List.of(), SecurityService.sshdCommand(Operation.SSHD_STATUS, InitSystem.UNKNOWN));
        assertEquals(List.of(), SecurityService.sshdCommand(Operation.SSHD_START, null));
    }

    @Test
    @DisplayName("buildCommand dispatches each op to the right vector")
    void buildCommandDispatch() {
        assertEquals(List.of("nft", "list", "ruleset"),
                SecurityService.buildCommand(Operation.NFT_LIST, AppArmorTool.NONE, InitSystem.UNKNOWN));
        assertEquals(List.of("nft", "-f", "/etc/nftables.conf"),
                SecurityService.buildCommand(Operation.NFT_APPLY, AppArmorTool.NONE, InitSystem.UNKNOWN));
        assertEquals(List.of("aa-status"),
                SecurityService.buildCommand(Operation.APPARMOR_STATUS, AppArmorTool.AA_STATUS, InitSystem.UNKNOWN));
        assertEquals(List.of(),
                SecurityService.buildCommand(Operation.APPARMOR_STATUS, AppArmorTool.NONE, InitSystem.UNKNOWN));
        assertEquals(List.of("getenforce"),
                SecurityService.buildCommand(Operation.SELINUX_MODE, AppArmorTool.NONE, InitSystem.UNKNOWN));
        assertEquals(List.of("systemctl", "status", "sshd"),
                SecurityService.buildCommand(Operation.SSHD_STATUS, AppArmorTool.NONE, InitSystem.SYSTEMD));
        assertEquals(List.of(), SecurityService.buildCommand(null, AppArmorTool.NONE, InitSystem.SYSTEMD));
    }

    @Test
    @DisplayName("mutating classification matches §4.6 (apply / start / stop only)")
    void mutatingClassification() {
        assertTrue(Operation.NFT_APPLY.isMutating());
        assertTrue(Operation.SSHD_START.isMutating());
        assertTrue(Operation.SSHD_STOP.isMutating());
        assertFalse(Operation.NFT_LIST.isMutating());
        assertFalse(Operation.APPARMOR_STATUS.isMutating());
        assertFalse(Operation.SELINUX_MODE.isMutating());
        assertFalse(Operation.SELINUX_ENABLED.isMutating());
        assertFalse(Operation.SSHD_STATUS.isMutating());
        assertFalse(Operation.SSHD_CONFIG_TEST.isMutating());
    }

    // ------------------------------------------------------------------
    // Parsers

    @Test
    @DisplayName("parseSelinuxMode maps getenforce output, tolerating case / blank")
    void parseSelinuxMode() {
        assertEquals(SelinuxMode.ENFORCING, SecurityService.parseSelinuxMode("Enforcing"));
        assertEquals(SelinuxMode.PERMISSIVE, SecurityService.parseSelinuxMode(" Permissive\n"));
        assertEquals(SelinuxMode.DISABLED, SecurityService.parseSelinuxMode("disabled"));
        assertEquals(SelinuxMode.UNKNOWN, SecurityService.parseSelinuxMode("garbage"));
        assertEquals(SelinuxMode.UNKNOWN, SecurityService.parseSelinuxMode(null));
        assertEquals(SelinuxMode.UNKNOWN, SecurityService.parseSelinuxMode("   "));
    }

    @Test
    @DisplayName("selinuxenabled exit 0 = enabled, non-zero = not")
    void parseSelinuxEnabled() {
        assertTrue(SecurityService.parseSelinuxEnabled(0));
        assertFalse(SecurityService.parseSelinuxEnabled(1));
        assertFalse(SecurityService.parseSelinuxEnabled(255));
    }

    @Test
    @DisplayName("parseAppArmor reads module-loaded + enforce/complain counts")
    void parseAppArmorLoaded() {
        String out = "apparmor module is loaded.\n"
                + "15 processes are in enforce mode.\n"
                + "3 processes are in complain mode.\n"
                + "0 processes have profiles defined.\n";
        AppArmorStatus s = SecurityService.parseAppArmor(out);
        assertTrue(s.isModuleLoaded());
        assertEquals(15, s.getEnforceCount());
        assertEquals(3, s.getComplainCount());
        assertTrue(s.describe().contains("Loaded"));
    }

    @Test
    @DisplayName("parseAppArmor detects the not-loaded case and blank output")
    void parseAppArmorNotLoaded() {
        AppArmorStatus notLoaded = SecurityService.parseAppArmor("apparmor module is not loaded.");
        assertFalse(notLoaded.isModuleLoaded());
        assertEquals(0, notLoaded.getEnforceCount());
        assertEquals("Not loaded", notLoaded.describe());

        AppArmorStatus blank = SecurityService.parseAppArmor("  ");
        assertFalse(blank.isModuleLoaded());
        AppArmorStatus nul = SecurityService.parseAppArmor(null);
        assertFalse(nul.isModuleLoaded());
        assertEquals(0, nul.getComplainCount());
    }

    @Test
    @DisplayName("summarizeNft counts table and chain declarations")
    void summarizeNft() {
        String ruleset = "table inet filter {\n"
                + "\tchain input {\n"
                + "\t\ttype filter hook input priority 0; policy drop;\n"
                + "\t\tct state established,related accept\n"
                + "\t}\n"
                + "\tchain forward {\n"
                + "\t\ttype filter hook forward priority 0; policy drop;\n"
                + "\t}\n"
                + "\tchain output {\n"
                + "\t\ttype filter hook output priority 0; policy accept;\n"
                + "\t}\n"
                + "}\n";
        NftSummary s = SecurityService.summarizeNft(ruleset);
        assertEquals(1, s.getTables());
        assertEquals(3, s.getChains());
        assertFalse(s.isEmpty());
        assertEquals("1 table, 3 chains", s.describe());
    }

    @Test
    @DisplayName("summarizeNft on an empty / null dump reports empty ruleset")
    void summarizeNftEmpty() {
        NftSummary empty = SecurityService.summarizeNft("");
        assertTrue(empty.isEmpty());
        assertEquals("empty ruleset", empty.describe());
        assertTrue(SecurityService.summarizeNft(null).isEmpty());
        assertEquals("2 tables, 1 chain",
                SecurityService.summarizeNft("table ip a {\nchain x {\n}\n}\ntable ip b {\n}").describe());
    }

    @Test
    @DisplayName("parseSshdState reads systemd active/inactive shapes")
    void parseSshdStateSystemd() {
        assertEquals(SshdState.RUNNING,
                SecurityService.parseSshdState(0, "sshd.service\n   Active: active (running) since Mon"));
        assertEquals(SshdState.STOPPED,
                SecurityService.parseSshdState(3, "sshd.service\n   Active: inactive (dead) since Mon"));
    }

    @Test
    @DisplayName("parseSshdState reads runit run:/down: and openrc started/stopped")
    void parseSshdStateOtherInit() {
        assertEquals(SshdState.RUNNING, SecurityService.parseSshdState(0, "run: sshd: (pid 900) 42s"));
        assertEquals(SshdState.STOPPED, SecurityService.parseSshdState(1, "down: sshd: 3s"));
        assertEquals(SshdState.RUNNING, SecurityService.parseSshdState(0, " * status: started"));
        assertEquals(SshdState.STOPPED, SecurityService.parseSshdState(1, " * status: stopped"));
    }

    @Test
    @DisplayName("parseSshdState never reads 'not running' as running; falls back to exit code")
    void parseSshdStateNegativesAndFallback() {
        assertEquals(SshdState.STOPPED, SecurityService.parseSshdState(1, "sshd is not running"));
        assertEquals(SshdState.STOPPED, SecurityService.parseSshdState(1, "Active: failed"));
        assertEquals(SshdState.RUNNING, SecurityService.parseSshdState(0, ""),
                "a zero exit with no text still means running");
        assertEquals(SshdState.UNKNOWN, SecurityService.parseSshdState(1, null));
    }

    @Test
    @DisplayName("sshd -t config test exit 0 = valid")
    void sshdConfigTestParse() {
        assertTrue(SecurityService.isSshdConfigOk(0));
        assertFalse(SecurityService.isSshdConfigOk(1));
    }

    @Test
    @DisplayName("describe helpers render every enum value and tolerate null")
    void describeHelpers() {
        assertEquals("Enforcing", SecurityService.describeSelinux(SelinuxMode.ENFORCING));
        assertEquals("Permissive", SecurityService.describeSelinux(SelinuxMode.PERMISSIVE));
        assertEquals("Disabled", SecurityService.describeSelinux(SelinuxMode.DISABLED));
        assertEquals("Unknown", SecurityService.describeSelinux(SelinuxMode.UNKNOWN));
        assertEquals("Unknown", SecurityService.describeSelinux(null));

        assertEquals("Running", SecurityService.describeSshd(SshdState.RUNNING));
        assertEquals("Stopped", SecurityService.describeSshd(SshdState.STOPPED));
        assertEquals("Unknown", SecurityService.describeSshd(SshdState.UNKNOWN));
        assertEquals("Unknown", SecurityService.describeSshd(null));
    }

    @Test
    @DisplayName("value objects clamp negative counts")
    void valueObjectsClamp() {
        AppArmorStatus s = new AppArmorStatus(true, -5, -1);
        assertEquals(0, s.getEnforceCount());
        assertEquals(0, s.getComplainCount());
        NftSummary n = new NftSummary(-2, -3);
        assertTrue(n.isEmpty());
    }

    // ------------------------------------------------------------------
    // Non-spawning run* guards

    @Test
    @DisplayName("runRead refuses a mutating op before building or spawning")
    void runReadRejectsMutating() {
        ProcessRunner.Result r = SecurityService.runRead(Operation.NFT_APPLY);
        assertFalse(r.isStarted());
        assertTrue(r.getMessage().contains("mutating"));
        assertFalse(SecurityService.runRead(Operation.SSHD_START).isStarted());
        assertFalse(SecurityService.runRead(null).isStarted());
    }

    @Test
    @DisplayName("runMutating refuses a read-only op before spawning")
    void runMutatingRejectsReadOnly() {
        PrivilegedRunner.PrivilegedResult r = SecurityService.runMutating(Operation.NFT_LIST);
        assertEquals(PrivilegedRunner.Status.ERROR, r.getStatus());
        assertFalse(r.isSuccess());
        assertEquals(PrivilegedRunner.Status.ERROR, SecurityService.runMutating(Operation.SELINUX_MODE).getStatus());
        assertEquals(PrivilegedRunner.Status.ERROR, SecurityService.runMutating(null).getStatus());
    }
}
