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
package org.jdesktop.lg3d.apps.passwordmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON persistence for the Password Manager: the sealed {@link VaultEnvelope}
 * ({@code vault.json}) and the plaintext {@link PasswordManagerSettings}
 * ({@code settings.json}).
 *
 * <p>Configuration lives under {@code ~/.lg3d/passwordmanager/} by default;
 * override with the system property {@link #DIR_PROPERTY} (tests point it at a
 * temp folder). Every read is defensive: a missing, empty or corrupt file yields
 * an empty envelope / default settings and is logged, never thrown, so a damaged
 * config can never stop the app from opening. Only the envelope carries secrets,
 * and it carries them encrypted - the store never sees a plaintext password.</p>
 */
public final class VaultStore {

    private static final Logger LOG = LoggerFactory.getLogger(VaultStore.class);

    /** System property overriding the configuration directory. */
    public static final String DIR_PROPERTY = "lg3d.passwordmanager.dir";

    static final String VAULT_FILE = "vault.json";
    static final String SETTINGS_FILE = "settings.json";

    private final Path configDir;
    private final ObjectMapper mapper;

    /** Creates a store rooted at {@link #defaultConfigDir()}. */
    public VaultStore() {
        this(defaultConfigDir());
    }

    /**
     * Creates a store rooted at an explicit directory.
     *
     * @param configDir where the JSON files live; created on first write
     */
    public VaultStore(Path configDir) {
        this.configDir = configDir;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Resolves the configuration directory.
     *
     * @return {@code lg3d.passwordmanager.dir} when set, else
     *         {@code ~/.lg3d/passwordmanager}
     */
    public static Path defaultConfigDir() {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".lg3d", "passwordmanager");
    }

    /** @return the directory this store reads and writes. */
    public Path getConfigDir() {
        return configDir;
    }

    /**
     * Loads the sealed vault envelope.
     *
     * @return the stored envelope, or an empty (not-present) one on any error
     */
    public VaultEnvelope loadEnvelope() {
        Path file = configDir.resolve(VAULT_FILE);
        if (!Files.isRegularFile(file)) {
            return new VaultEnvelope();
        }
        try {
            VaultEnvelope envelope = mapper.readValue(file.toFile(), VaultEnvelope.class);
            return (envelope == null) ? new VaultEnvelope() : envelope;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; treating as no vault", file, e);
            return new VaultEnvelope();
        }
    }

    /**
     * Persists the sealed vault envelope.
     *
     * @param envelope the envelope to write (null writes an empty one)
     */
    public void saveEnvelope(VaultEnvelope envelope) {
        write(VAULT_FILE, (envelope == null) ? new VaultEnvelope() : envelope);
    }

    /** True when a sealed vault exists on disk. */
    public boolean hasVault() {
        return loadEnvelope().isPresent();
    }

    /** @return the saved settings, or defaults on any error. */
    public PasswordManagerSettings loadSettings() {
        Path file = configDir.resolve(SETTINGS_FILE);
        if (!Files.isRegularFile(file)) {
            return new PasswordManagerSettings();
        }
        try {
            PasswordManagerSettings settings =
                    mapper.readValue(file.toFile(), PasswordManagerSettings.class);
            return (settings == null) ? new PasswordManagerSettings() : settings;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; using defaults", file, e);
            return new PasswordManagerSettings();
        }
    }

    /** Persists the settings. */
    public void saveSettings(PasswordManagerSettings settings) {
        write(SETTINGS_FILE,
                (settings == null) ? new PasswordManagerSettings() : settings);
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
