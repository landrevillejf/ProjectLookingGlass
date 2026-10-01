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
package org.jdesktop.lg3d.apps.backup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON persistence for the backup tool's saved {@link BackupProfile profiles}.
 *
 * <p>Configuration lives under {@code ~/.lg3d/backup/} by default. Override with
 * the system property {@link #DIR_PROPERTY} (used by tests to point at a temp
 * folder). Every read is defensive: a missing, empty or corrupt file yields an
 * empty list and is logged, never thrown, so a damaged config can never stop the
 * app from opening.</p>
 *
 * <p>The store holds no secrets: a profile records only folder paths and archive
 * options, never credentials, and the archive itself is written wherever the
 * user points the destination.</p>
 */
public final class BackupStore {

    private static final Logger LOG = LoggerFactory.getLogger(BackupStore.class);

    /** System property overriding the configuration directory. */
    public static final String DIR_PROPERTY = "lg3d.backup.dir";

    static final String PROFILES_FILE = "profiles.json";

    private final Path configDir;
    private final ObjectMapper mapper;

    /** Creates a store rooted at {@link #defaultConfigDir()}. */
    public BackupStore() {
        this(defaultConfigDir());
    }

    /**
     * Creates a store rooted at an explicit directory.
     *
     * @param configDir where the JSON file lives; created on first write
     */
    public BackupStore(Path configDir) {
        this.configDir = configDir;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Resolves the configuration directory.
     *
     * @return {@code lg3d.backup.dir} when set, else {@code ~/.lg3d/backup}
     */
    public static Path defaultConfigDir() {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".lg3d", "backup");
    }

    /** @return the directory this store reads and writes. */
    public Path getConfigDir() {
        return configDir;
    }

    /** @return the saved profiles, or an empty list on any error. */
    public List<BackupProfile> loadProfiles() {
        Path file = configDir.resolve(PROFILES_FILE);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            List<BackupProfile> list = mapper.readValue(file.toFile(),
                    new TypeReference<List<BackupProfile>>() { });
            return (list == null) ? new ArrayList<>() : list;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; starting empty", file, e);
            return new ArrayList<>();
        }
    }

    /** Persists the profiles. */
    public void saveProfiles(List<BackupProfile> profiles) {
        List<BackupProfile> src = (profiles == null) ? new ArrayList<>() : profiles;
        try {
            Files.createDirectories(configDir);
            mapper.writeValue(configDir.resolve(PROFILES_FILE).toFile(), src);
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not write {}", PROFILES_FILE, e);
        }
    }
}
