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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.jdesktop.lg3d.utils.system.InitSystemService.InitSystem;
import org.jdesktop.lg3d.utils.system.InitSystemService.Operation;
import org.jdesktop.lg3d.utils.system.ServerService.Probes;
import org.jdesktop.lg3d.utils.system.ServerService.ServerDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link ServerService} (the §4.9 server-services catalog
 * seam). The catalog, the detection filter, the command-vector delegation to the
 * §4.1 init abstraction and the config-path resolution are all pure: no server is
 * installed, no process is spawned and the filesystem is only touched through
 * injected {@link Probes}, so the suite is CI-safe. It proves the vectors match
 * {@link InitSystemService} exactly, that an absent binary+config leaves a server
 * out, and that every helper degrades rather than throws.
 */
class ServerServiceTest {

    private static ServerDef byUnit(String unit) {
        for (ServerDef d : ServerService.catalog()) {
            if (d.getServiceUnit().equals(unit)) {
                return d;
            }
        }
        throw new IllegalArgumentException("no catalog entry for unit " + unit);
    }

    // ------------------------------------------------------------------
    // Catalog

    @Test
    @DisplayName("the catalog is non-empty, immutable and carries the §4 servers")
    void catalogIsCurated() {
        List<ServerDef> catalog = ServerService.catalog();
        assertFalse(catalog.isEmpty());
        assertNotNull(byUnit("httpd"));
        assertNotNull(byUnit("named"));
        assertNotNull(byUnit("postfix"));
        assertNotNull(byUnit("smbd"));
        assertNotNull(byUnit("mariadb"));
        assertTrue(catalog.stream().anyMatch(d -> d.getDisplayName().equals("Apache httpd")));
    }

    @Test
    @DisplayName("Tomcat has no single well-known config path, so it offers no config view")
    void tomcatHasNoConfig() {
        ServerDef tomcat = byUnit("tomcat");
        assertFalse(tomcat.hasConfig());
        assertEquals("Apache Tomcat", tomcat.toString());
    }

    @Test
    @DisplayName("a config-bearing entry exposes its read-only path")
    void configBearingEntry() {
        ServerDef httpd = byUnit("httpd");
        assertTrue(httpd.hasConfig());
        assertEquals("/etc/httpd/httpd.conf", httpd.getConfigPath());
        assertEquals("httpd", httpd.getBinary());
    }

    // ------------------------------------------------------------------
    // Detection matrix

    @Test
    @DisplayName("a server is installed when its binary is on PATH")
    void installedByBinary() {
        Probes p = new Probes(Set.of("httpd"), Set.of(), InitSystem.SYSTEMD);
        assertTrue(ServerService.isInstalled(p, byUnit("httpd")));
    }

    @Test
    @DisplayName("a server is installed when only its config file exists")
    void installedByConfig() {
        Probes p = new Probes(Set.of(), Set.of("/etc/named.conf"), InitSystem.SYSTEMD);
        assertTrue(ServerService.isInstalled(p, byUnit("named")));
    }

    @Test
    @DisplayName("a server with neither binary nor config is not installed")
    void notInstalled() {
        Probes p = new Probes(Set.of(), Set.of(), InitSystem.SYSTEMD);
        assertFalse(ServerService.isInstalled(p, byUnit("postfix")));
        assertTrue(ServerService.installedServers(p).isEmpty());
    }

    @Test
    @DisplayName("installedServers filters to the present entries in catalog order")
    void installedServersFiltersAndOrders() {
        // Present out of catalog order on purpose; the result must be catalog-ordered.
        Probes p = new Probes(Set.of("smbd", "httpd"), Set.of(), InitSystem.SYSTEMD);
        List<ServerDef> installed = ServerService.installedServers(p);
        assertEquals(2, installed.size());
        assertEquals("httpd", installed.get(0).getServiceUnit());
        assertEquals("smbd", installed.get(1).getServiceUnit());
    }

    @Test
    @DisplayName("null probes and null defs degrade rather than throw")
    void nullGuards() {
        assertFalse(ServerService.isInstalled(null, byUnit("httpd")));
        assertFalse(ServerService.isInstalled(new Probes(Set.of("httpd"), Set.of(), null), null));
        assertTrue(ServerService.installedServers(null).isEmpty());
    }

    @Test
    @DisplayName("the init system carried by the probes is exposed, defaulting to UNKNOWN")
    void probesCarryInit() {
        assertEquals(InitSystem.OPENRC,
                new Probes(Set.of(), Set.of(), InitSystem.OPENRC).init());
        assertEquals(InitSystem.UNKNOWN, new Probes(null, null, null).init());
    }

    // ------------------------------------------------------------------
    // Command-vector delegation (§4.1)

    @Test
    @DisplayName("the lifecycle vector is exactly the init abstraction's for the unit")
    void lifecycleCommandDelegates() {
        ServerDef httpd = byUnit("httpd");
        assertEquals(List.of("systemctl", "start", "httpd"),
                ServerService.lifecycleCommand(httpd, InitSystem.SYSTEMD, Operation.START));
        assertEquals(List.of("rc-service", "postfix", "stop"),
                ServerService.lifecycleCommand(byUnit("postfix"), InitSystem.OPENRC, Operation.STOP));
    }

    @Test
    @DisplayName("a null def or unknown init yields an empty vector, never a shell string")
    void lifecycleCommandDegrades() {
        assertTrue(ServerService.lifecycleCommand(null, InitSystem.SYSTEMD, Operation.START).isEmpty());
        assertTrue(ServerService.lifecycleCommand(
                byUnit("httpd"), InitSystem.UNKNOWN, Operation.START).isEmpty());
    }

    @Test
    @DisplayName("isSupported mirrors the init abstraction (sysvinit has no enable)")
    void isSupportedMirrorsInit() {
        assertTrue(ServerService.isSupported(InitSystem.SYSTEMD, Operation.ENABLE));
        assertFalse(ServerService.isSupported(InitSystem.SYSVINIT, Operation.ENABLE));
        assertFalse(ServerService.isSupported(InitSystem.UNKNOWN, Operation.START));
    }

    // ------------------------------------------------------------------
    // Config view + execution guards (non-spawning paths only)

    @Test
    @DisplayName("readConfig on a config-less or null def is an honest not-present")
    void readConfigGuards() {
        assertFalse(ServerService.readConfig(null).isExists());
        assertFalse(ServerService.readConfig(byUnit("tomcat")).isExists());
        assertEquals("not present", ServerService.readConfig(byUnit("tomcat")).describe());
    }

    @Test
    @DisplayName("runStatus on a null def is a not-started result, no process spawned")
    void runStatusNullGuard() {
        ProcessRunner.Result r = ServerService.runStatus(null, InitSystem.SYSTEMD);
        assertFalse(r.isStarted());
        assertEquals("no server selected", r.getStderr());
    }

    @Test
    @DisplayName("runStatus under an unknown init builds an empty vector and does not spawn")
    void runStatusUnknownInitIsSafe() {
        ProcessRunner.Result r = ServerService.runStatus(byUnit("httpd"), InitSystem.UNKNOWN);
        assertFalse(r.isStarted(), "an unknown init yields an empty vector, so nothing runs");
    }

    @Test
    @DisplayName("runLifecycle on a null def is an ERROR result, no process spawned")
    void runLifecycleNullGuard() {
        PrivilegedRunner.PrivilegedResult r =
                ServerService.runLifecycle(null, InitSystem.SYSTEMD, Operation.START);
        assertEquals(PrivilegedRunner.Status.ERROR, r.getStatus());
        assertEquals("no server selected", r.getMessage());
    }

    @Test
    @DisplayName("runLifecycle under an unknown init does not spawn (empty vector)")
    void runLifecycleUnknownInitIsSafe() {
        PrivilegedRunner.PrivilegedResult r =
                ServerService.runLifecycle(byUnit("httpd"), InitSystem.UNKNOWN, Operation.START);
        assertNotNull(r.getStatus());
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("the live probe never throws and yields usable probes")
    void liveProbeIsSafe() {
        Probes p = ServerService.probe();
        assertNotNull(p);
        assertNotNull(p.init());
        assertNotNull(ServerService.installedServers(p));
    }
}
