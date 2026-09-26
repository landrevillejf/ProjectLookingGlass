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
package org.jdesktop.lg3d.ftpclient.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists the FTP Client's state as JSON under a configuration directory: the
 * saved site profiles and the application settings.
 *
 * <p>The directory defaults to {@code ~/.lg3d/ftpclient} and can be overridden
 * with the {@code lg3d.ftpclient.dir} system property (the unit tests point it
 * at a temporary folder). Reads are forgiving: a missing or corrupt file yields
 * the documented defaults rather than throwing, so a hand-edited store can never
 * stop the app from starting. Writes surface failures as a {@link StoreException}
 * so the UI can tell the user their change was not saved.</p>
 *
 * <p>Passwords are never written in plaintext: a profile that opts into
 * {@link SiteProfile#isSavePassword()} has its password obfuscated by
 * {@link PasswordObfuscator} on save and restored on load; otherwise the
 * password is dropped from the stored form.</p>
 */
public final class ProfileStore {

    private static final Logger LOG = LoggerFactory.getLogger(ProfileStore.class);

    /** System property overriding the configuration directory. */
    public static final String DIR_PROPERTY = "lg3d.ftpclient.dir";

    static final String PROFILES_FILE = "profiles.json";
    static final String SETTINGS_FILE = "settings.json";

    private final Path configDir;
    private final ObjectMapper mapper;

    /** Creates a store rooted at {@link #defaultConfigDir()}. */
    public ProfileStore() {
        this(defaultConfigDir());
    }

    /**
     * Creates a store rooted at an explicit directory.
     *
     * @param configDir where the JSON files live; created on first write
     */
    public ProfileStore(Path configDir) {
        this.configDir = configDir;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Resolves the configuration directory.
     *
     * @return {@code lg3d.ftpclient.dir} when set, else {@code ~/.lg3d/ftpclient}
     */
    public static Path defaultConfigDir() {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".lg3d", "ftpclient");
    }

    /** @return the directory this store reads and writes. */
    public Path getConfigDir() {
        return configDir;
    }

    // ------------------------------------------------------------------
    // profiles
    // ------------------------------------------------------------------

    /**
     * Loads the saved site profiles.
     *
     * @return the profiles (passwords restored), or an empty list on any error
     */
    public List<SiteProfile> loadProfiles() {
        Path file = configDir.resolve(PROFILES_FILE);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            List<SiteProfile> list = mapper.readValue(file.toFile(),
                    new TypeReference<List<SiteProfile>>() { });
            List<SiteProfile> result = new ArrayList<>();
            for (SiteProfile p : list) {
                p.setPassword(PasswordObfuscator.deobfuscate(p.getPassword()));
                result.add(p);
            }
            return result;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; starting with no profiles", file, e);
            return new ArrayList<>();
        }
    }

    /**
     * Persists the site profiles, obfuscating opted-in passwords.
     *
     * @param profiles the profiles to write
     * @throws StoreException when the file cannot be written
     */
    public void saveProfiles(List<SiteProfile> profiles) {
        List<SiteProfile> toWrite = new ArrayList<>();
        for (SiteProfile p : safe(profiles)) {
            SiteProfile c = p.copy();
            if (c.isSavePassword()) {
                c.setPassword(PasswordObfuscator.obfuscate(c.getPassword()));
            } else {
                c.setPassword(null);
            }
            toWrite.add(c);
        }
        write(PROFILES_FILE, toWrite);
    }

    // ------------------------------------------------------------------
    // settings
    // ------------------------------------------------------------------

    /**
     * Loads the application settings.
     *
     * @return the stored settings, or defaults on any error
     */
    public AppSettings loadSettings() {
        Path file = configDir.resolve(SETTINGS_FILE);
        if (!Files.isRegularFile(file)) {
            return new AppSettings();
        }
        try {
            return mapper.readValue(file.toFile(), AppSettings.class);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; using default settings", file, e);
            return new AppSettings();
        }
    }

    /**
     * Persists the application settings.
     *
     * @param settings the settings to write; {@code null} writes defaults
     * @throws StoreException when the file cannot be written
     */
    public void saveSettings(AppSettings settings) {
        write(SETTINGS_FILE, settings == null ? new AppSettings() : settings);
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private void write(String fileName, Object value) {
        try {
            Files.createDirectories(configDir);
            mapper.writeValue(configDir.resolve(fileName).toFile(), value);
        } catch (IOException e) {
            throw new StoreException("Could not write " + fileName, e);
        }
    }

    private static <T> List<T> safe(List<T> list) {
        return (list == null) ? Collections.emptyList() : list;
    }

    /** Signals that persisted state could not be written. */
    public static class StoreException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public StoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
