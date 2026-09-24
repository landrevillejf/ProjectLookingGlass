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

/**
 * A single LPM package as read (read-only) from the LPM database files under
 * {@code /var/lib/lpm}. This is a plain value object: it never writes anything
 * back. Every mutation still goes through the {@code lpm} binary via
 * {@link LPMExecutor}, per the LPM Control Application Contract §5.5 / §7.
 */
public final class LpmPackage {

    private final String name;
    private final String version;
    private final String description;
    private final String deps;
    private final String checksum;
    private final boolean installed;
    private final boolean held;

    public LpmPackage(String name, String version, String description,
                      String deps, String checksum,
                      boolean installed, boolean held) {
        this.name = name == null ? "" : name;
        this.version = version == null ? "" : version;
        this.description = description == null ? "" : description;
        this.deps = deps == null ? "" : deps;
        this.checksum = checksum == null ? "" : checksum;
        this.installed = installed;
        this.held = held;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public String getDeps() {
        return deps;
    }

    public String getChecksum() {
        return checksum;
    }

    public boolean isInstalled() {
        return installed;
    }

    public boolean isHeld() {
        return held;
    }

    /**
     * A short human status used by the package table's "Status" column.
     */
    public String getStatus() {
        if (held) {
            return installed ? "held" : "held (not installed)";
        }
        return installed ? "installed" : "available";
    }

    /**
     * Returns a copy flagged as upgradable, keeping every other field.
     */
    public LpmPackage asUpgradable() {
        return new LpmPackage(name, version, description, deps, checksum,
                installed, held);
    }

    @Override
    public String toString() {
        return name + (version.isEmpty() ? "" : " " + version);
    }
}
