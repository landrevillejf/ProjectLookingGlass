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
package org.jdesktop.lg3d.apps.audioplayer;

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
 * JSON persistence for the audio player's library and preferences.
 *
 * <p>Configuration lives under {@code ~/.lg3d/audioplayer/} by default; override
 * with the system property {@link #DIR_PROPERTY} (tests point it at a temp
 * folder). Every read is defensive: a missing, empty or corrupt file yields an
 * empty library / default settings and is logged, never thrown, so a damaged
 * config can never stop the player from opening.</p>
 *
 * <p>The store holds no secrets - a library records only names, file paths and
 * stream URLs.</p>
 */
public final class AudioPlayerStore {

    private static final Logger LOG =
            LoggerFactory.getLogger(AudioPlayerStore.class);

    /** System property overriding the configuration directory. */
    public static final String DIR_PROPERTY = "lg3d.audioplayer.dir";

    static final String LIBRARY_FILE = "library.json";
    static final String SETTINGS_FILE = "settings.json";
    static final String RIP_SETTINGS_FILE = "ripsettings.json";
    static final String COVERS_DIR = "covers";
    static final String MUSIC_DIR = "music";

    private final Path configDir;
    private final ObjectMapper mapper;

    /** Creates a store rooted at {@link #defaultConfigDir()}. */
    public AudioPlayerStore() {
        this(defaultConfigDir());
    }

    /**
     * Creates a store rooted at an explicit directory.
     *
     * @param configDir where the JSON files live; created on first write
     */
    public AudioPlayerStore(Path configDir) {
        this.configDir = configDir;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Resolves the configuration directory.
     *
     * @return {@code lg3d.audioplayer.dir} when set, else
     *         {@code ~/.lg3d/audioplayer}
     */
    public static Path defaultConfigDir() {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".lg3d", "audioplayer");
    }

    /** @return the directory this store reads and writes. */
    public Path getConfigDir() {
        return configDir;
    }

    /** @return the saved library, or an empty list on any error. */
    public List<MediaItem> loadLibrary() {
        Path file = configDir.resolve(LIBRARY_FILE);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            List<MediaItem> list = mapper.readValue(file.toFile(),
                    new TypeReference<List<MediaItem>>() { });
            return (list == null) ? new ArrayList<>() : list;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; starting empty", file, e);
            return new ArrayList<>();
        }
    }

    /** Persists the library. */
    public void saveLibrary(List<MediaItem> library) {
        List<MediaItem> src = (library == null) ? new ArrayList<>() : library;
        write(LIBRARY_FILE, src);
    }

    /** @return the saved settings, or defaults on any error. */
    public PlayerSettings loadSettings() {
        Path file = configDir.resolve(SETTINGS_FILE);
        if (!Files.isRegularFile(file)) {
            return new PlayerSettings();
        }
        try {
            PlayerSettings settings = mapper.readValue(file.toFile(), PlayerSettings.class);
            return (settings == null) ? new PlayerSettings() : settings;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; using defaults", file, e);
            return new PlayerSettings();
        }
    }

    /** Persists the settings. */
    public void saveSettings(PlayerSettings settings) {
        write(SETTINGS_FILE, (settings == null) ? new PlayerSettings() : settings);
    }

    /** @return the saved CD-ripping settings, or defaults on any error. */
    public RipSettings loadRipSettings() {
        Path file = configDir.resolve(RIP_SETTINGS_FILE);
        if (!Files.isRegularFile(file)) {
            return new RipSettings();
        }
        try {
            RipSettings settings = mapper.readValue(file.toFile(), RipSettings.class);
            return (settings == null) ? new RipSettings() : settings;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; using defaults", file, e);
            return new RipSettings();
        }
    }

    /** Persists the CD-ripping settings. */
    public void saveRipSettings(RipSettings settings) {
        write(RIP_SETTINGS_FILE, (settings == null) ? new RipSettings() : settings);
    }

    /**
     * The directory cached album-cover images live in ({@code <config>/covers}),
     * created on demand by {@link #coverFile}.
     *
     * @return the covers directory path
     */
    public Path getCoversDir() {
        return configDir.resolve(COVERS_DIR);
    }

    /**
     * The cache file for a release's cover image, {@code <config>/covers/<mbid>.jpg}.
     * Creates the covers directory as a convenience; a blank MBID yields a path
     * under {@code covers} named {@code unknown.jpg}.
     *
     * @param mbid the MusicBrainz release MBID
     * @return the cover image path (never null)
     */
    public Path coverFile(String mbid) {
        String name = (mbid == null || mbid.isBlank()) ? "unknown" : mbid.trim();
        // Sanitise: an MBID is hex+dashes, but never trust it as a path segment.
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return getCoversDir().resolve(name + ".jpg");
    }

    /**
     * The default destination folder for ripped tracks ({@code <config>/music}).
     *
     * @return the music directory path
     */
    public Path getMusicDir() {
        return configDir.resolve(MUSIC_DIR);
    }

    /**
     * Creates a directory (and parents) if absent, swallowing any failure.
     *
     * @param dir the directory to create
     * @return {@code dir} when it exists afterwards, else null
     */
    public Path ensureDir(Path dir) {
        if (dir == null) {
            return null;
        }
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not create directory {}", dir, e);
            return null;
        }
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
