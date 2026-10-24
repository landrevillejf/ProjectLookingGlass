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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jdesktop.lg3d.utils.system.InitSystemService.InitSystem;
import org.jdesktop.lg3d.utils.system.InitSystemService.Operation;

/**
 * Server-services front-end for the LFS/BLFS system manager, normative in
 * {@code system-management-contract.md} §4.9. It is a curated <em>catalog</em>
 * of the internet servers and databases the {@code lg3d} build profile installs
 * (Apache httpd, BIND, Postfix, Dovecot, Samba, NFS, ProFTPD, vsftpd, OpenLDAP,
 * Tomcat, MariaDB, PostgreSQL - see {@code lg3d-agent-package-inventory.md} §4),
 * each described by the display name, the service unit, the binary that reveals
 * it is installed and the config file the GUI may show read-only.
 *
 * <p>This class adds <b>no server-specific reimplementation</b> (§6): the whole
 * lifecycle (status / start / stop / restart / enable / disable) is delegated to
 * the {@link InitSystemService} §4.1 abstraction with the catalog entry's service
 * unit, so a server is driven exactly like any other service and no init logic is
 * duplicated here. The config view is a plain bounded read through
 * {@link ConfigFileReader} - the GUI never writes {@code /etc} (§3.5).</p>
 *
 * <p>The catalog, the detection filter, the command building and the config path
 * resolution are pure and unit-testable ({@link #catalog()},
 * {@link #isInstalled(Probes, ServerDef)}, {@link #installedServers(Probes)},
 * {@link #lifecycleCommand}); the live {@link #probe()} is a thin, non-spawning
 * wrapper over {@link ProcessRunner#isAvailable} / {@link Files#exists} /
 * {@link InitSystemService#detect()} that degrades gracefully - an absent binary
 * and config simply leave a server out of the installed list rather than
 * throwing.</p>
 */
public final class ServerService {

    private ServerService() {
        // no instances
    }

    /**
     * One catalog entry: a manageable server, the unit that drives it, the
     * binary whose presence reveals it is installed, and the config file the GUI
     * may read (nullable when the server has no single well-known config path).
     */
    public static final class ServerDef {
        private final String displayName;
        private final String serviceUnit;
        private final String binary;
        private final String configPath;

        public ServerDef(String displayName, String serviceUnit, String binary, String configPath) {
            this.displayName = displayName;
            this.serviceUnit = serviceUnit;
            this.binary = binary;
            this.configPath = configPath;
        }

        /** The human name shown in the panel's list. */
        public String getDisplayName() {
            return displayName;
        }

        /** The init-system service unit driven through §4.1. */
        public String getServiceUnit() {
            return serviceUnit;
        }

        /** The binary whose presence on {@code PATH} reveals the server. */
        public String getBinary() {
            return binary;
        }

        /** The read-only config path, or null when there is no single one. */
        public String getConfigPath() {
            return configPath;
        }

        /** True if this entry has a config path the GUI can offer to read. */
        public boolean hasConfig() {
            return configPath != null && !configPath.isBlank();
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private static final List<ServerDef> CATALOG = buildCatalog();

    private static List<ServerDef> buildCatalog() {
        List<ServerDef> c = new ArrayList<>();
        c.add(new ServerDef("Apache httpd", "httpd", "httpd", "/etc/httpd/httpd.conf"));
        c.add(new ServerDef("BIND (DNS)", "named", "named", "/etc/named.conf"));
        c.add(new ServerDef("Postfix (MTA)", "postfix", "postfix", "/etc/postfix/main.cf"));
        c.add(new ServerDef("Dovecot (IMAP/POP3)", "dovecot", "dovecot", "/etc/dovecot/dovecot.conf"));
        c.add(new ServerDef("Samba (SMB/CIFS)", "smbd", "smbd", "/etc/samba/smb.conf"));
        c.add(new ServerDef("NFS server", "nfs-server", "exportfs", "/etc/exports"));
        c.add(new ServerDef("ProFTPD", "proftpd", "proftpd", "/etc/proftpd.conf"));
        c.add(new ServerDef("vsftpd", "vsftpd", "vsftpd", "/etc/vsftpd.conf"));
        c.add(new ServerDef("OpenLDAP", "slapd", "slapd", "/etc/openldap/slapd.conf"));
        // Tomcat's catalina.sh is not usually on PATH and its server.xml lives
        // under a versioned /opt prefix, so it is detected by unit/binary only
        // and offers no config view here.
        c.add(new ServerDef("Apache Tomcat", "tomcat", "catalina.sh", null));
        c.add(new ServerDef("MariaDB", "mariadb", "mariadb", "/etc/my.cnf"));
        c.add(new ServerDef("PostgreSQL", "postgresql", "psql", "/var/lib/pgsql/data/postgresql.conf"));
        return Collections.unmodifiableList(c);
    }

    /** The full catalog of servers this desktop knows how to surface. */
    public static List<ServerDef> catalog() {
        return CATALOG;
    }

    /**
     * The filesystem/PATH facts {@link #probe()} gathers and the pure predicates
     * consume. Splitting these out keeps detection unit-testable without any real
     * server installed: a test constructs a {@code Probes} with explicit sets.
     */
    public static final class Probes {
        private final Set<String> binaries;
        private final Set<String> configs;
        private final InitSystem init;

        public Probes(Set<String> binaries, Set<String> configs, InitSystem init) {
            this.binaries = (binaries == null) ? Set.of() : Set.copyOf(binaries);
            this.configs = (configs == null) ? Set.of() : Set.copyOf(configs);
            this.init = (init == null) ? InitSystem.UNKNOWN : init;
        }

        boolean hasBinary(String binary) {
            return binary != null && binaries.contains(binary);
        }

        boolean hasConfig(String path) {
            return path != null && configs.contains(path);
        }

        /** The init system detected when the probes were gathered. */
        public InitSystem init() {
            return init;
        }
    }

    /**
     * Live detection against this host: probes {@code PATH} for each catalog
     * binary and the filesystem for each config path, then asks
     * {@link InitSystemService} which supervisor is running. Never spawns a
     * process - {@link ProcessRunner#isAvailable} and {@link InitSystemService#detect()}
     * are PATH/filesystem checks and the former is cached.
     */
    public static Probes probe() {
        Set<String> binaries = new LinkedHashSet<>();
        Set<String> configs = new LinkedHashSet<>();
        for (ServerDef d : CATALOG) {
            if (ProcessRunner.isAvailable(d.getBinary())) {
                binaries.add(d.getBinary());
            }
            if (d.hasConfig() && Files.exists(Path.of(d.getConfigPath()))) {
                configs.add(d.getConfigPath());
            }
        }
        return new Probes(binaries, configs, InitSystemService.detect());
    }

    /**
     * True if {@code def} is installed on the probed host: its binary is on
     * {@code PATH} <em>or</em> its config file exists (a server configured but
     * with its daemon binary off the default PATH is still worth surfacing).
     */
    public static boolean isInstalled(Probes p, ServerDef def) {
        if (p == null || def == null) {
            return false;
        }
        return p.hasBinary(def.getBinary()) || (def.hasConfig() && p.hasConfig(def.getConfigPath()));
    }

    /**
     * The catalog entries installed on the probed host, in catalog order. Pure;
     * returns an empty list when {@code p} is null.
     */
    public static List<ServerDef> installedServers(Probes p) {
        List<ServerDef> out = new ArrayList<>();
        if (p == null) {
            return out;
        }
        for (ServerDef d : CATALOG) {
            if (isInstalled(p, d)) {
                out.add(d);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Command building (pure; delegates to the §4.1 init abstraction).

    /**
     * Builds the argument vector for {@code op} on {@code init} targeting
     * {@code def}'s service unit, by delegating to
     * {@link InitSystemService#buildCommand}. Returns an empty list when
     * {@code def} is null, the init is unknown or the operation is unsupported;
     * the result never contains shell metacharacters.
     */
    public static List<String> lifecycleCommand(ServerDef def, InitSystem init, Operation op) {
        if (def == null) {
            return Collections.emptyList();
        }
        return InitSystemService.buildCommand(init, op, def.getServiceUnit());
    }

    /** True if {@code init} supports {@code op} (delegates to §4.1). */
    public static boolean isSupported(InitSystem init, Operation op) {
        return InitSystemService.isSupported(init, op);
    }

    // ------------------------------------------------------------------
    // Thin execution helpers (the panel owns the UI wiring).

    /**
     * Runs a read-only {@code status} query for {@code def} unprivileged through
     * the §4.1 abstraction. Returns a not-started result when {@code def} is null.
     */
    public static ProcessRunner.Result runStatus(ServerDef def, InitSystem init) {
        if (def == null) {
            return new ProcessRunner.Result(false, -1, "", "no server selected");
        }
        return InitSystemService.runRead(init, Operation.STATUS, def.getServiceUnit());
    }

    /**
     * Runs a mutating lifecycle operation (start/stop/restart/enable/disable) for
     * {@code def} with polkit escalation through the §4.1 abstraction. Returns an
     * ERROR result when {@code def} is null or the operation is not mutating. The
     * caller MUST have confirmed first.
     */
    public static PrivilegedRunner.PrivilegedResult runLifecycle(
            ServerDef def, InitSystem init, Operation op) {
        if (def == null) {
            return new PrivilegedRunner.PrivilegedResult(
                    PrivilegedRunner.Status.ERROR, "no server selected", -1, "");
        }
        return InitSystemService.runMutating(init, op, def.getServiceUnit());
    }

    /**
     * Reads {@code def}'s config file read-only through {@link ConfigFileReader}.
     * A server with no config path, or a null {@code def}, yields an honest
     * not-present {@link ConfigFileReader.Content}; never throws, never writes.
     */
    public static ConfigFileReader.Content readConfig(ServerDef def) {
        if (def == null || !def.hasConfig()) {
            return ConfigFileReader.read(null);
        }
        return ConfigFileReader.read(def.getConfigPath());
    }
}
