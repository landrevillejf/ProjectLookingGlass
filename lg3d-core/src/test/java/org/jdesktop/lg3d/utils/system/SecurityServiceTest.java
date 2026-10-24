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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jdesktop.lg3d.utils.system.InitSystemService.InitSystem;
import org.jdesktop.lg3d.utils.system.SecurityService.AppArmorTool;
import org.jdesktop.lg3d.utils.system.SecurityService.NftSummary;
import org.jdesktop.lg3d.utils.system.SecurityService.AppArmorStatus;
import org.jdesktop.lg3d.utils.system.SecurityService.ModuleDraft;
import org.jdesktop.lg3d.utils.system.SecurityService.Operation;
import org.jdesktop.lg3d.utils.system.SecurityService.PolicyOperation;
import org.jdesktop.lg3d.utils.system.SecurityService.PolicyProbes;
import org.jdesktop.lg3d.utils.system.SecurityService.PolicyTool;
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

    // ==================================================================
    // SELinux policy management (gap G2)

    @Test
    @DisplayName("policy tool detection reports each present binary, absent is not")
    void detectPolicyTools() {
        PolicyProbes all = new PolicyProbes(true, true, true, true, true, true);
        PolicyProbes none = new PolicyProbes(false, false, false, false, false, false);
        for (PolicyTool tool : PolicyTool.values()) {
            assertTrue(SecurityService.isPolicyToolPresent(all, tool), tool + " present");
            assertFalse(SecurityService.isPolicyToolPresent(none, tool), tool + " absent");
        }
        assertFalse(SecurityService.isPolicyToolPresent(null, PolicyTool.SEMANAGE));
        assertFalse(SecurityService.isPolicyToolPresent(all, null));
    }

    @Test
    @DisplayName("toolFor maps every policy operation to the binary that drives it")
    void toolForMapsOperations() {
        assertEquals(PolicyTool.SEMANAGE, SecurityService.toolFor(PolicyOperation.SEMANAGE_FCONTEXT_LIST));
        assertEquals(PolicyTool.SEMANAGE, SecurityService.toolFor(PolicyOperation.SEMANAGE_PORT_ADD));
        assertEquals(PolicyTool.RESTORECON, SecurityService.toolFor(PolicyOperation.RESTORECON));
        assertEquals(PolicyTool.SEMODULE, SecurityService.toolFor(PolicyOperation.SEMODULE_INSTALL));
        assertEquals(PolicyTool.LOAD_POLICY, SecurityService.toolFor(PolicyOperation.LOAD_POLICY));
        assertEquals(PolicyTool.AUSEARCH, SecurityService.toolFor(PolicyOperation.AUSEARCH_AVC));
        assertEquals(PolicyTool.AUDIT2ALLOW, SecurityService.toolFor(PolicyOperation.AUDIT2ALLOW));
        assertNull(SecurityService.toolFor(null));
    }

    @Test
    @DisplayName("semanage list vectors take no parameters")
    void semanageListVectors() {
        assertEquals(List.of("semanage", "fcontext", "-l"),
                SecurityService.buildPolicyCommand(PolicyOperation.SEMANAGE_FCONTEXT_LIST));
        assertEquals(List.of("semanage", "boolean", "-l"),
                SecurityService.buildPolicyCommand(PolicyOperation.SEMANAGE_BOOLEAN_LIST));
        assertEquals(List.of("semanage", "port", "-l"),
                SecurityService.buildPolicyCommand(PolicyOperation.SEMANAGE_PORT_LIST));
    }

    @Test
    @DisplayName("semanage fcontext add/del build the exact rule vector")
    void semanageFcontextVectors() {
        assertEquals(List.of("semanage", "fcontext", "-a", "-t", "httpd_sys_content_t", "/var/www(/.*)?"),
                SecurityService.buildPolicyCommand(
                        PolicyOperation.SEMANAGE_FCONTEXT_ADD, "httpd_sys_content_t", "/var/www(/.*)?"));
        assertEquals(List.of("semanage", "fcontext", "-d", "/var/www(/.*)?"),
                SecurityService.buildPolicyCommand(PolicyOperation.SEMANAGE_FCONTEXT_DEL, "/var/www(/.*)?"));
        // A malformed add (missing type, or a type with whitespace) yields no vector.
        assertEquals(List.of(),
                SecurityService.buildPolicyCommand(PolicyOperation.SEMANAGE_FCONTEXT_ADD, "only_one"));
        assertEquals(List.of(),
                SecurityService.buildPolicyCommand(PolicyOperation.SEMANAGE_FCONTEXT_ADD, "bad type", "/x"));
        assertEquals(List.of(),
                SecurityService.buildPolicyCommand(PolicyOperation.SEMANAGE_FCONTEXT_DEL));
    }

    @Test
    @DisplayName("semanage boolean set maps on/off and rejects any other value")
    void semanageBooleanVector() {
        assertEquals(List.of("semanage", "boolean", "-m", "--on", "httpd_can_network_connect"),
                SecurityService.buildPolicyCommand(
                        PolicyOperation.SEMANAGE_BOOLEAN_SET, "httpd_can_network_connect", "on"));
        assertEquals(List.of("semanage", "boolean", "-m", "--off", "httpd_can_network_connect"),
                SecurityService.buildPolicyCommand(
                        PolicyOperation.SEMANAGE_BOOLEAN_SET, "httpd_can_network_connect", "OFF"));
        assertEquals(List.of(),
                SecurityService.buildPolicyCommand(PolicyOperation.SEMANAGE_BOOLEAN_SET, "name", "maybe"));
        assertEquals(List.of(),
                SecurityService.buildPolicyCommand(PolicyOperation.SEMANAGE_BOOLEAN_SET, "name"));
    }

    @Test
    @DisplayName("semanage port add/del carry type, protocol and port")
    void semanagePortVectors() {
        assertEquals(List.of("semanage", "port", "-a", "-t", "http_port_t", "-p", "tcp", "8080"),
                SecurityService.buildPolicyCommand(
                        PolicyOperation.SEMANAGE_PORT_ADD, "http_port_t", "tcp", "8080"));
        assertEquals(List.of("semanage", "port", "-d", "-t", "http_port_t", "-p", "tcp", "8080"),
                SecurityService.buildPolicyCommand(
                        PolicyOperation.SEMANAGE_PORT_DEL, "http_port_t", "tcp", "8080"));
        assertEquals(List.of(),
                SecurityService.buildPolicyCommand(PolicyOperation.SEMANAGE_PORT_ADD, "t", "tcp"));
    }

    @Test
    @DisplayName("restorecon, semodule and load_policy vectors")
    void restoreconSemanageModuleVectors() {
        assertEquals(List.of("restorecon", "-Rv", "/var/www"),
                SecurityService.buildPolicyCommand(PolicyOperation.RESTORECON, "/var/www"));
        assertEquals(List.of(), SecurityService.buildPolicyCommand(PolicyOperation.RESTORECON));
        assertEquals(List.of(),
                SecurityService.buildPolicyCommand(PolicyOperation.RESTORECON, "has\nnewline"));
        assertEquals(List.of("semodule", "-l"),
                SecurityService.buildPolicyCommand(PolicyOperation.SEMODULE_LIST));
        assertEquals(List.of("semodule", "-i", "/root/mymodule.pp"),
                SecurityService.buildPolicyCommand(PolicyOperation.SEMODULE_INSTALL, "/root/mymodule.pp"));
        assertEquals(List.of("semodule", "-r", "mymodule"),
                SecurityService.buildPolicyCommand(PolicyOperation.SEMODULE_REMOVE, "mymodule"));
        assertEquals(List.of("load_policy"),
                SecurityService.buildPolicyCommand(PolicyOperation.LOAD_POLICY));
    }

    @Test
    @DisplayName("the ausearch -> audit2allow review pipe vectors")
    void auditReviewVectors() {
        assertEquals(List.of("ausearch", "-m", "avc", "-ts", "recent"),
                SecurityService.buildPolicyCommand(PolicyOperation.AUSEARCH_AVC));
        assertEquals(List.of("audit2allow"),
                SecurityService.buildPolicyCommand(PolicyOperation.AUDIT2ALLOW));
        assertEquals(List.of(), SecurityService.buildPolicyCommand(null));
    }

    @Test
    @DisplayName("policy mutating classification: every write is destructive, reads are not")
    void policyMutatingClassification() {
        assertTrue(PolicyOperation.SEMANAGE_FCONTEXT_ADD.isMutating());
        assertTrue(PolicyOperation.SEMANAGE_FCONTEXT_DEL.isMutating());
        assertTrue(PolicyOperation.SEMANAGE_BOOLEAN_SET.isMutating());
        assertTrue(PolicyOperation.SEMANAGE_PORT_ADD.isMutating());
        assertTrue(PolicyOperation.SEMANAGE_PORT_DEL.isMutating());
        assertTrue(PolicyOperation.RESTORECON.isMutating());
        assertTrue(PolicyOperation.SEMODULE_INSTALL.isMutating());
        assertTrue(PolicyOperation.SEMODULE_REMOVE.isMutating());
        assertTrue(PolicyOperation.LOAD_POLICY.isMutating());
        assertFalse(PolicyOperation.SEMANAGE_FCONTEXT_LIST.isMutating());
        assertFalse(PolicyOperation.SEMANAGE_BOOLEAN_LIST.isMutating());
        assertFalse(PolicyOperation.SEMANAGE_PORT_LIST.isMutating());
        assertFalse(PolicyOperation.SEMODULE_LIST.isMutating());
        assertFalse(PolicyOperation.AUSEARCH_AVC.isMutating());
        assertFalse(PolicyOperation.AUDIT2ALLOW.isMutating());
    }

    @Test
    @DisplayName("isToken rejects blanks, whitespace and over-long words; isPath rejects control chars")
    void tokenAndPathValidation() {
        assertTrue(SecurityService.isToken("httpd_sys_content_t"));
        assertTrue(SecurityService.isToken("tcp"));
        assertFalse(SecurityService.isToken("two words"));
        assertFalse(SecurityService.isToken("  "));
        assertFalse(SecurityService.isToken(null));
        assertFalse(SecurityService.isToken("x".repeat(65)));

        assertTrue(SecurityService.isPath("/var/www(/.*)?"));
        assertTrue(SecurityService.isPath("/home/a dir/b.pp"), "a space is safe in one argv element");
        assertFalse(SecurityService.isPath("/has\nnewline"));
        assertFalse(SecurityService.isPath(""));
        assertFalse(SecurityService.isPath(null));
    }

    @Test
    @DisplayName("countAvcDenials counts type=AVC records and tolerates junk")
    void countAvcDenials() {
        String log = "time->Fri Oct 24 10:00:00 2026\n"
                + "type=AVC msg=audit(1.2:3): avc:  denied  { read } for  pid=1 comm=\"httpd\"\n"
                + "type=SYSCALL msg=audit(1.2:3): arch=c000003e syscall=2 success=no\n"
                + "----\n"
                + "type=AVC msg=audit(1.2:4): avc:  denied  { write } for  pid=1 comm=\"httpd\"\n";
        assertEquals(2, SecurityService.countAvcDenials(log));
        assertEquals(0, SecurityService.countAvcDenials("<no events>"));
        assertEquals(0, SecurityService.countAvcDenials(null));
        assertEquals(0, SecurityService.countAvcDenials("  "));
    }

    @Test
    @DisplayName("summarizeAudit2Allow counts allow rules and tunable hints")
    void summarizeAudit2Allow() {
        String module = "module mydraft 1.0;\n\n"
                + "require {\n\ttype httpd_t;\n\tclass file read;\n}\n\n"
                + "#============= httpd_t ==============\n"
                + "allow httpd_t user_home_t:file read;\n"
                + "allow httpd_t var_t:dir write;\n";
        ModuleDraft d = SecurityService.summarizeAudit2Allow(module);
        assertEquals(2, d.getAllowRules());
        assertEquals(0, d.getTunables());
        assertFalse(d.isEmpty());
        assertEquals("2 allow rules", d.describe());

        ModuleDraft empty = SecurityService.summarizeAudit2Allow("");
        assertTrue(empty.isEmpty());
        assertEquals("audit2allow drafted no rules (nothing was denied)", empty.describe());
        assertTrue(SecurityService.summarizeAudit2Allow(null).isEmpty());

        ModuleDraft tunable = SecurityService.summarizeAudit2Allow(
                "#!!!! The boolean httpd_can_network_connect can be set to allow\nsetsebool -P x 1\n");
        assertEquals(1, tunable.getTunables());
        assertEquals("0 allow rules, 1 tunable", tunable.describe());
    }

    @Test
    @DisplayName("describePolicy renders every operation and tolerates null")
    void describePolicy() {
        assertEquals("Reload kernel policy", SecurityService.describePolicy(PolicyOperation.LOAD_POLICY));
        assertEquals("Relabel path (restorecon)", SecurityService.describePolicy(PolicyOperation.RESTORECON));
        assertEquals("Generate module draft (review)", SecurityService.describePolicy(PolicyOperation.AUDIT2ALLOW));
        assertEquals("Unknown", SecurityService.describePolicy(null));
        for (PolicyOperation op : PolicyOperation.values()) {
            assertFalse(SecurityService.describePolicy(op).isBlank(), op + " has a label");
        }
    }

    @Test
    @DisplayName("runPolicyRead refuses a mutating op before building or spawning")
    void runPolicyReadRejectsMutating() {
        ProcessRunner.Result r = SecurityService.runPolicyRead(PolicyOperation.RESTORECON, "/var/www");
        assertFalse(r.isStarted());
        assertTrue(r.getMessage().contains("mutating"));
        assertFalse(SecurityService.runPolicyRead(PolicyOperation.LOAD_POLICY).isStarted());
        assertFalse(SecurityService.runPolicyRead(null).isStarted());
        // A read op with malformed parameters is refused without spawning.
        assertFalse(SecurityService.runPolicyRead(PolicyOperation.SEMANAGE_FCONTEXT_DEL).isStarted());
    }

    @Test
    @DisplayName("runPolicyMutating refuses a read-only op before spawning")
    void runPolicyMutatingRejectsReadOnly() {
        PrivilegedRunner.PrivilegedResult r =
                SecurityService.runPolicyMutating(PolicyOperation.SEMODULE_LIST);
        assertEquals(PrivilegedRunner.Status.ERROR, r.getStatus());
        assertFalse(r.isSuccess());
        assertEquals(PrivilegedRunner.Status.ERROR,
                SecurityService.runPolicyMutating(PolicyOperation.AUSEARCH_AVC).getStatus());
        assertEquals(PrivilegedRunner.Status.ERROR, SecurityService.runPolicyMutating(null).getStatus());
        // A mutating op with malformed parameters is refused without spawning.
        assertEquals(PrivilegedRunner.Status.ERROR,
                SecurityService.runPolicyMutating(PolicyOperation.SEMODULE_INSTALL).getStatus());
    }

    @Test
    @DisplayName("live policy probes never throw and never return null")
    void livePolicyProbesAreSafe() {
        assertDoesNotThrow(() -> {
            PolicyProbes p = SecurityService.probePolicy();
            assertNotNull(p);
            // runAudit2Allow degrades to a not-started result when the tool is absent.
            ProcessRunner.Result r = SecurityService.runAudit2Allow("type=AVC denied");
            assertNotNull(r);
        });
    }
}
