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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only reader for LPM's database files under {@code /var/lib/lpm}.
 *
 * <p>The LPM Control Application Contract (§5.5) permits the front-end to read
 * these files directly for fast, structured listing, but every write MUST go
 * through the {@code lpm} binary (§7). This class therefore only ever opens the
 * files for reading and never modifies them.</p>
 *
 * <p>File formats (contract §5.5):</p>
 * <ul>
 *   <li>{@code packages.list}: {@code name|version|description|deps|checksum}</li>
 *   <li>{@code installed.list}: {@code name version}</li>
 *   <li>{@code holds.list}: one package name per line</li>
 *   <li>{@code history.log}: {@code timestamp|action|package|version}</li>
 * </ul>
 *
 * <p>When the database is absent (e.g. running the desktop on a machine without
 * LPM installed) every reader degrades to an empty result and
 * {@link #isAvailable()} reports {@code false}, so the UI can show a banner
 * instead of crashing.</p>
 */
public final class LpmDatabase {

    /** Overridable for development/testing; defaults to the real LPM state dir. */
    private static final String DB_DIR_PROPERTY = "lpm.dbdir";
    private static final String DEFAULT_DB_DIR = "/var/lib/lpm";

    private final Path dbDir;

    public LpmDatabase() {
        String override = System.getProperty(DB_DIR_PROPERTY);
        this.dbDir = Paths.get(override == null || override.isBlank()
                ? DEFAULT_DB_DIR : override);
    }

    /** True when the LPM state directory and at least one index file exist. */
    public boolean isAvailable() {
        return Files.isDirectory(dbDir)
                && (Files.isReadable(dbDir.resolve("packages.list"))
                    || Files.isReadable(dbDir.resolve("installed.list")));
    }

    /** The resolved database directory (for the UI banner / diagnostics). */
    public Path getDirectory() {
        return dbDir;
    }

    /**
     * Every package known to LPM (available and/or installed), each flagged with
     * its installed / held state. Ordered as in {@code packages.list}, with any
     * installed-but-unlisted packages appended.
     */
    public List<LpmPackage> readPackages() {
        Set<String> installed = readInstalledNames();
        Set<String> held = readHeldNames();
        Map<String, LpmPackage> byName = new LinkedHashMap<>();

        for (String line : readLines(dbDir.resolve("packages.list"))) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\\|", -1);
            String name = field(f, 0);
            if (name.isEmpty()) {
                continue;
            }
            byName.put(name, new LpmPackage(name, field(f, 1), field(f, 2),
                    field(f, 3), field(f, 4),
                    installed.contains(name), held.contains(name)));
        }

        // Installed packages that are not (or no longer) in packages.list.
        for (String name : installed) {
            if (!byName.containsKey(name)) {
                byName.put(name, new LpmPackage(name, "", "", "", "",
                        true, held.contains(name)));
            }
        }
        return new ArrayList<>(byName.values());
    }

    /** Only the installed packages. */
    public List<LpmPackage> readInstalled() {
        List<LpmPackage> result = new ArrayList<>();
        for (LpmPackage p : readPackages()) {
            if (p.isInstalled()) {
                result.add(p);
            }
        }
        return result;
    }

    /** Only the held (pinned) packages. */
    public List<LpmPackage> readHeld() {
        List<LpmPackage> result = new ArrayList<>();
        for (LpmPackage p : readPackages()) {
            if (p.isHeld()) {
                result.add(p);
            }
        }
        return result;
    }

    /**
     * The transaction log, most recent first. Each row is
     * {@code [timestamp, action, package, version]}.
     */
    public List<String[]> readHistory(int max) {
        List<String> lines = readLines(dbDir.resolve("history.log"));
        List<String[]> rows = new ArrayList<>();
        // history.log appends oldest-first; walk backwards for newest-first.
        for (int i = lines.size() - 1; i >= 0 && rows.size() < max; i--) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split("\\|", -1);
            rows.add(new String[] { field(f, 0), field(f, 1), field(f, 2),
                    field(f, 3) });
        }
        return rows;
    }

    /** Names from {@code installed.list} ({@code name version} per line). */
    private Set<String> readInstalledNames() {
        Set<String> names = new LinkedHashSet<>();
        for (String line : readLines(dbDir.resolve("installed.list"))) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length > 0 && !parts[0].isEmpty()) {
                names.add(parts[0]);
            }
        }
        return names;
    }

    /** Names from {@code holds.list} (one package name per line). */
    private Set<String> readHeldNames() {
        Set<String> names = new LinkedHashSet<>();
        for (String line : readLines(dbDir.resolve("holds.list"))) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                names.add(trimmed);
            }
        }
        return names;
    }

    private List<String> readLines(Path file) {
        if (!Files.isReadable(file)) {
            return Collections.emptyList();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return Collections.emptyList();
        }
    }

    private static String field(String[] parts, int index) {
        return index < parts.length ? parts[index].trim() : "";
    }
}
