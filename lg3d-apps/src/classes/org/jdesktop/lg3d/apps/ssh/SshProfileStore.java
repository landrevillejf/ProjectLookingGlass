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
package org.jdesktop.lg3d.apps.ssh;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON persistence for {@link SshProfile} connection profiles and application
 * settings.
 *
 * <p>Configuration lives under {@code ~/.lg3d/ssh/} by default. Override with
 * the system property {@code lg3d.ssh.dir} (used by tests to point at a temp
 * folder).</p>
 *
 * <p>Passwords are <em>never</em> stored unless the user explicitly opts in per
 * profile ({@link SshProfile#isSavePassword()}). Opted-in passwords are
 * obfuscated with XOR + Base64 — this is <b>not</b> encryption, merely a
 * deterrent to casual inspection. The UI warns about this.</p>
 */
public final class SshProfileStore {

    private static final Logger LOG = LoggerFactory.getLogger(SshProfileStore.class);

    /** System property overriding the configuration directory. */
    public static final String DIR_PROPERTY = "lg3d.ssh.dir";

    static final String PROFILES_FILE = "profiles.json";
    static final String SETTINGS_FILE = "settings.json";

    private static final String OBF_PREFIX = "obf1:";
    private static final byte[] OBF_KEY = "lg3d-ssh-obfuscation-key".getBytes(StandardCharsets.UTF_8);

    private final Path configDir;
    private final ObjectMapper mapper;

    /** Creates a store rooted at {@link #defaultConfigDir()}. */
    public SshProfileStore() {
        this(defaultConfigDir());
    }

    /**
     * Creates a store rooted at an explicit directory.
     *
     * @param configDir where the JSON files live; created on first write
     */
    public SshProfileStore(Path configDir) {
        this.configDir = configDir;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Resolves the configuration directory.
     *
     * @return {@code lg3d.ssh.dir} when set, else {@code ~/.lg3d/ssh}
     */
    public static Path defaultConfigDir() {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".lg3d", "ssh");
    }

    /** @return the directory this store reads and writes. */
    public Path getConfigDir() {
        return configDir;
    }

    // ------------------------------------------------------------------
    // Profiles
    // ------------------------------------------------------------------

    /**
     * Loads the saved connection profiles.
     *
     * @return the profiles (passwords restored), or an empty list on any error
     */
    public List<SshProfile> loadProfiles() {
        Path file = configDir.resolve(PROFILES_FILE);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            List<SshProfile> list = mapper.readValue(file.toFile(),
                    new TypeReference<List<SshProfile>>() { });
            List<SshProfile> result = new ArrayList<>();
            for (SshProfile p : list) {
                if (p.getPassword() != null) {
                    p.setPassword(deobfuscate(p.getPassword()));
                }
                if (p.getPassphrase() != null) {
                    p.setPassphrase(deobfuscate(p.getPassphrase()));
                }
                result.add(p);
            }
            return result;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; starting with no profiles", file, e);
            return new ArrayList<>();
        }
    }

    /**
     * Persists the connection profiles, obfuscating opted-in passwords.
     *
     * @param profiles the profiles to write
     */
    public void saveProfiles(List<SshProfile> profiles) {
        List<SshProfile> toWrite = new ArrayList<>();
        for (SshProfile p : safe(profiles)) {
            SshProfile c = p.copy();
            if (c.isSavePassword()) {
                if (c.getPassword() != null) {
                    c.setPassword(obfuscate(c.getPassword()));
                }
                if (c.getPassphrase() != null) {
                    c.setPassphrase(obfuscate(c.getPassphrase()));
                }
            } else {
                c.setPassword(null);
                c.setPassphrase(null);
            }
            toWrite.add(c);
        }
        write(PROFILES_FILE, toWrite);
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    /**
     * Loads application settings (font size, scrollback, etc.).
     *
     * @return the settings, or defaults on error
     */
    public SshSettings loadSettings() {
        Path file = configDir.resolve(SETTINGS_FILE);
        if (!Files.isRegularFile(file)) {
            return new SshSettings();
        }
        try {
            return mapper.readValue(file.toFile(), SshSettings.class);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; using defaults", file, e);
            return new SshSettings();
        }
    }

    /**
     * Persists application settings.
     *
     * @param settings the settings to write
     */
    public void saveSettings(SshSettings settings) {
        write(SETTINGS_FILE, settings);
    }

    // ------------------------------------------------------------------
    // Known hosts path
    // ------------------------------------------------------------------

    /** @return the path to the SSH known_hosts file managed by this store. */
    public Path getKnownHostsPath() {
        return configDir.resolve("known_hosts");
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private void write(String fileName, Object value) {
        try {
            Files.createDirectories(configDir);
            mapper.writeValue(configDir.resolve(fileName).toFile(), value);
        } catch (IOException e) {
            LOG.error("Could not write {}", fileName, e);
        }
    }

    private static <T> List<T> safe(List<T> list) {
        return (list == null) ? Collections.emptyList() : list;
    }

    /** XOR + Base64 obfuscation (NOT encryption). */
    static String obfuscate(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        byte[] data = plain.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < data.length; i++) {
            data[i] ^= OBF_KEY[i % OBF_KEY.length];
        }
        return OBF_PREFIX + Base64.getEncoder().encodeToString(data);
    }

    /** Reverses {@link #obfuscate(String)}. */
    static String deobfuscate(String stored) {
        if (stored == null || !stored.startsWith(OBF_PREFIX)) {
            return stored;
        }
        try {
            byte[] data = Base64.getDecoder().decode(stored.substring(OBF_PREFIX.length()));
            for (int i = 0; i < data.length; i++) {
                data[i] ^= OBF_KEY[i % OBF_KEY.length];
            }
            return new String(data, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            LOG.warn("Corrupt obfuscated value; returning null");
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Settings bean
    // ------------------------------------------------------------------

    /** Application-wide SSH client settings persisted as JSON. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class SshSettings {
        private int fontSize = 14;
        private int scrollbackLines = 10000;
        private String fontFamily = "Monospaced";
        private String defaultTerminalType = "xterm-256color";
        private boolean antiAliasing = true;
        private String foregroundColor = "#00FF00";
        private String backgroundColor = "#000000";
        private String cursorColor = "#FFFFFF";
        private int keepAliveSeconds = 30;
        private int connectTimeoutSeconds = 15;
        private boolean strictHostKeyChecking = true;
        private boolean showTimestamps;
        private int tabLimit = 20;

        public int getFontSize() { return fontSize; }
        public void setFontSize(int fontSize) { this.fontSize = fontSize; }

        public int getScrollbackLines() { return scrollbackLines; }
        public void setScrollbackLines(int scrollbackLines) { this.scrollbackLines = scrollbackLines; }

        public String getFontFamily() { return fontFamily; }
        public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; }

        public String getDefaultTerminalType() { return defaultTerminalType; }
        public void setDefaultTerminalType(String t) { this.defaultTerminalType = t; }

        public boolean isAntiAliasing() { return antiAliasing; }
        public void setAntiAliasing(boolean antiAliasing) { this.antiAliasing = antiAliasing; }

        public String getForegroundColor() { return foregroundColor; }
        public void setForegroundColor(String c) { this.foregroundColor = c; }

        public String getBackgroundColor() { return backgroundColor; }
        public void setBackgroundColor(String c) { this.backgroundColor = c; }

        public String getCursorColor() { return cursorColor; }
        public void setCursorColor(String c) { this.cursorColor = c; }

        public int getKeepAliveSeconds() { return keepAliveSeconds; }
        public void setKeepAliveSeconds(int s) { this.keepAliveSeconds = s; }

        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int s) { this.connectTimeoutSeconds = s; }

        public boolean isStrictHostKeyChecking() { return strictHostKeyChecking; }
        public void setStrictHostKeyChecking(boolean b) { this.strictHostKeyChecking = b; }

        public boolean isShowTimestamps() { return showTimestamps; }
        public void setShowTimestamps(boolean b) { this.showTimestamps = b; }

        public int getTabLimit() { return tabLimit; }
        public void setTabLimit(int tabLimit) { this.tabLimit = tabLimit; }
    }
}
