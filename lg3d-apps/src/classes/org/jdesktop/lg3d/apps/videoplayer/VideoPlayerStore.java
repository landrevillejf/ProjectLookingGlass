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
package org.jdesktop.lg3d.apps.videoplayer;

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
 * JSON persistence for the video player's library and preferences.
 *
 * <p>Configuration lives under {@code ~/.lg3d/videoplayer/} by default; override
 * with the system property {@link #DIR_PROPERTY} (tests point it at a temp
 * folder). Every read is defensive: a missing, empty or corrupt file yields an
 * empty library / default settings and is logged, never thrown, so a damaged
 * config can never stop the player from opening.</p>
 *
 * <p>The store holds no secrets - a library records only names, file paths,
 * stream URLs and disc devices.</p>
 */
public final class VideoPlayerStore {

    private static final Logger LOG =
            LoggerFactory.getLogger(VideoPlayerStore.class);

    /** System property overriding the configuration directory. */
    public static final String DIR_PROPERTY = "lg3d.videoplayer.dir";

    static final String LIBRARY_FILE = "library.json";
    static final String SETTINGS_FILE = "settings.json";

    private final Path configDir;
    private final ObjectMapper mapper;

    /** Creates a store rooted at {@link #defaultConfigDir()}. */
    public VideoPlayerStore() {
        this(defaultConfigDir());
    }

    /**
     * Creates a store rooted at an explicit directory.
     *
     * @param configDir where the JSON files live; created on first write
     */
    public VideoPlayerStore(Path configDir) {
        this.configDir = configDir;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Resolves the configuration directory.
     *
     * @return {@code lg3d.videoplayer.dir} when set, else
     *         {@code ~/.lg3d/videoplayer}
     */
    public static Path defaultConfigDir() {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".lg3d", "videoplayer");
    }

    /** @return the directory this store reads and writes. */
    public Path getConfigDir() {
        return configDir;
    }

    /** @return the saved library, or an empty list on any error. */
    public List<VideoItem> loadLibrary() {
        Path file = configDir.resolve(LIBRARY_FILE);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            List<VideoItem> list = mapper.readValue(file.toFile(),
                    new TypeReference<List<VideoItem>>() { });
            return (list == null) ? new ArrayList<>() : list;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; starting empty", file, e);
            return new ArrayList<>();
        }
    }

    /** Persists the library. */
    public void saveLibrary(List<VideoItem> library) {
        List<VideoItem> src = (library == null) ? new ArrayList<>() : library;
        write(LIBRARY_FILE, src);
    }

    /** @return the saved settings, or defaults on any error. */
    public VideoSettings loadSettings() {
        Path file = configDir.resolve(SETTINGS_FILE);
        if (!Files.isRegularFile(file)) {
            return new VideoSettings();
        }
        try {
            VideoSettings settings = mapper.readValue(file.toFile(), VideoSettings.class);
            return (settings == null) ? new VideoSettings() : settings;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; using defaults", file, e);
            return new VideoSettings();
        }
    }

    /** Persists the settings. */
    public void saveSettings(VideoSettings settings) {
        write(SETTINGS_FILE, (settings == null) ? new VideoSettings() : settings);
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
