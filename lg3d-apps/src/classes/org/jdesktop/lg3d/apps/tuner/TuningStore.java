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
package org.jdesktop.lg3d.apps.tuner;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
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
 * JSON persistence for the tuner's <em>user-defined</em> tunings (the built-in
 * guitar/bass sets in {@link Tuning#ALL} are compiled in and never written here).
 *
 * <p>Custom tunings live under {@code ~/.lg3d/tuner/} by default; override with
 * the system property {@link #DIR_PROPERTY} (tests point it at a temp folder).
 * Every read is defensive: a missing, empty or corrupt file yields an empty list
 * and is logged, never thrown, so a damaged config can never stop the tuner from
 * opening. This mirrors the sibling {@code RecorderStore} pattern.</p>
 *
 * <p>A tuning is serialised through the small {@link StoredTuning} DTO (a name
 * and the string MIDI numbers) rather than {@link Tuning} itself, so none of the
 * model's derived getters leak into the JSON and the file stays hand-editable.</p>
 */
public final class TuningStore {

    private static final Logger LOG =
            LoggerFactory.getLogger(TuningStore.class);

    /** System property overriding the configuration directory. */
    public static final String DIR_PROPERTY = "lg3d.tuner.dir";

    static final String TUNINGS_FILE = "tunings.json";

    private final Path configDir;
    private final ObjectMapper mapper;

    /** Creates a store rooted at {@link #defaultConfigDir()}. */
    public TuningStore() {
        this(defaultConfigDir());
    }

    /**
     * Creates a store rooted at an explicit directory.
     *
     * @param configDir where the JSON file lives; created on first write
     */
    public TuningStore(Path configDir) {
        this.configDir = configDir;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Resolves the configuration directory.
     *
     * @return {@code lg3d.tuner.dir} when set, else {@code ~/.lg3d/tuner}
     */
    public static Path defaultConfigDir() {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".lg3d", "tuner");
    }

    /** @return the directory this store reads and writes. */
    public Path getConfigDir() {
        return configDir;
    }

    /** @return the saved custom tunings, or an empty list on any error. */
    public List<Tuning> loadCustomTunings() {
        Path file = configDir.resolve(TUNINGS_FILE);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            List<StoredTuning> stored = mapper.readValue(file.toFile(),
                    new TypeReference<List<StoredTuning>>() { });
            List<Tuning> tunings = new ArrayList<>();
            if (stored != null) {
                for (StoredTuning s : stored) {
                    Tuning t = (s == null) ? null : s.toTuning();
                    if (t != null) {
                        tunings.add(t);
                    }
                }
            }
            return tunings;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; starting with no custom tunings", file, e);
            return new ArrayList<>();
        }
    }

    /** Persists the custom tunings (null is stored as an empty list). */
    public void saveCustomTunings(List<Tuning> tunings) {
        List<StoredTuning> stored = new ArrayList<>();
        if (tunings != null) {
            for (Tuning t : tunings) {
                if (t != null) {
                    stored.add(StoredTuning.from(t));
                }
            }
        }
        try {
            Files.createDirectories(configDir);
            mapper.writeValue(configDir.resolve(TUNINGS_FILE).toFile(), stored);
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not write {}", TUNINGS_FILE, e);
        }
    }

    /**
     * The JSON shape of one persisted tuning: a name and the string MIDI numbers
     * (low to high). Kept separate from {@link Tuning} so the model stays a pure,
     * immutable value type and the file stays small and hand-editable.
     */
    static final class StoredTuning {

        private final String name;
        private final int[] strings;

        @JsonCreator
        StoredTuning(@JsonProperty("name") String name,
                @JsonProperty("strings") int[] strings) {
            this.name = name;
            this.strings = (strings == null) ? new int[0] : strings;
        }

        static StoredTuning from(Tuning tuning) {
            return new StoredTuning(tuning.getName(), tuning.getStrings());
        }

        Tuning toTuning() {
            return Tuning.of(name, strings);
        }

        @JsonProperty("name")
        public String getName() {
            return name;
        }

        @JsonProperty("strings")
        public int[] getStrings() {
            return strings;
        }
    }
}
