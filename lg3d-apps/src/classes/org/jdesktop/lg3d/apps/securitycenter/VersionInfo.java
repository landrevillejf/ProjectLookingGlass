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

/**
 * The ClamAV engine and virus-database versions parsed from
 * {@code clamscan --version}, which prints a single line such as
 * {@code ClamAV 1.4.6/27171/Wed Jan 31 04:46:17 2024}. The engine is the
 * program version, the database the signature revision; both are shown on the
 * Security Overview so the user can tell whether definitions are current.
 *
 * @param engine   the ClamAV engine version (e.g. {@code 1.4.6}); empty if unknown
 * @param database the virus-database revision (e.g. {@code 27171}); empty if unknown
 * @param raw      the full version line, for display / debugging
 */
public record VersionInfo(String engine, String database, String raw) {

    /** Normalises nulls to empty strings. */
    public VersionInfo {
        engine = (engine == null) ? "" : engine.trim();
        database = (database == null) ? "" : database.trim();
        raw = (raw == null) ? "" : raw.trim();
    }

    /** An empty version, used when no scanner is installed. */
    public static VersionInfo unknown() {
        return new VersionInfo("", "", "");
    }

    /** @return true when an engine version was successfully parsed. */
    public boolean isPresent() {
        return !engine.isBlank();
    }

    @Override
    public String toString() {
        if (!isPresent()) {
            return "unknown";
        }
        return database.isBlank()
                ? "ClamAV " + engine
                : "ClamAV " + engine + " (db " + database + ")";
    }
}
