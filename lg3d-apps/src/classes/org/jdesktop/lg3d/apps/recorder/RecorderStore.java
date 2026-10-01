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
package org.jdesktop.lg3d.apps.recorder;

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
 * JSON persistence for the recorder's preferences and capture history.
 *
 * <p>Configuration lives under {@code ~/.lg3d/recorder/} by default; override
 * with the system property {@link #DIR_PROPERTY} (tests point it at a temp
 * folder). Every read is defensive: a missing, empty or corrupt file yields
 * default settings / an empty history and is logged, never thrown, so a damaged
 * config can never stop the recorder from opening.</p>
 *
 * <p>The store holds no secrets and no captured media - it records only the
 * output folder, capture format preferences and the paths of finished files.</p>
 */
public final class RecorderStore {

    private static final Logger LOG =
            LoggerFactory.getLogger(RecorderStore.class);

    /** System property overriding the configuration directory. */
    public static final String DIR_PROPERTY = "lg3d.recorder.dir";

    static final String SETTINGS_FILE = "settings.json";
    static final String HISTORY_FILE = "history.json";

    private final Path configDir;
    private final ObjectMapper mapper;

    /** Creates a store rooted at {@link #defaultConfigDir()}. */
    public RecorderStore() {
        this(defaultConfigDir());
    }

    /**
     * Creates a store rooted at an explicit directory.
     *
     * @param configDir where the JSON files live; created on first write
     */
    public RecorderStore(Path configDir) {
        this.configDir = configDir;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Resolves the configuration directory.
     *
     * @return {@code lg3d.recorder.dir} when set, else {@code ~/.lg3d/recorder}
     */
    public static Path defaultConfigDir() {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".lg3d", "recorder");
    }

    /** @return the directory this store reads and writes. */
    public Path getConfigDir() {
        return configDir;
    }

    /** @return the saved settings, or defaults on any error. */
    public RecordingSettings loadSettings() {
        Path file = configDir.resolve(SETTINGS_FILE);
        if (!Files.isRegularFile(file)) {
            return new RecordingSettings();
        }
        try {
            RecordingSettings settings =
                    mapper.readValue(file.toFile(), RecordingSettings.class);
            return (settings == null) ? new RecordingSettings() : settings;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; using defaults", file, e);
            return new RecordingSettings();
        }
    }

    /** Persists the settings. */
    public void saveSettings(RecordingSettings settings) {
        write(SETTINGS_FILE, (settings == null) ? new RecordingSettings() : settings);
    }

    /** @return the saved history, or an empty list on any error. */
    public List<Recording> loadHistory() {
        Path file = configDir.resolve(HISTORY_FILE);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            List<Recording> list = mapper.readValue(file.toFile(),
                    new TypeReference<List<Recording>>() { });
            return (list == null) ? new ArrayList<>() : list;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; starting empty", file, e);
            return new ArrayList<>();
        }
    }

    /** Persists the history. */
    public void saveHistory(List<Recording> history) {
        List<Recording> src = (history == null) ? new ArrayList<>() : history;
        write(HISTORY_FILE, src);
    }

    private void write(String fileName, Object value) {
        try {
            Files.createDirectories(configDir);
            mapper.writeValue(configDir.resolve(fileName).toFile(), value);
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not write {}", fileName, e);
        }
    }
}
