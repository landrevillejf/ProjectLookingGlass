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
package org.jdesktop.lg3d.apps.messenger;

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
 * JSON persistence for the Messenger: the saved {@link AccountConfig accounts},
 * the message {@link StoredMessage history} and the {@link MessengerSettings}.
 *
 * <p>Configuration lives under {@code ~/.lg3d/messenger/} by default; override
 * with the system property {@link #DIR_PROPERTY} (tests point this at a temp
 * folder). Every read is defensive: a missing, empty or corrupt file yields the
 * empty/default value and is logged, never thrown, so a damaged config can never
 * stop the app from opening.</p>
 *
 * <p><b>Security:</b> no secret is ever written. {@link AccountConfig}'s
 * password fields are {@code @JsonIgnore}, so the accounts file holds connection
 * details and a {@code passwordPrompt} flag only; a password lives in memory for
 * the session and is re-entered on connect.</p>
 */
public final class MessengerStore {

    private static final Logger LOG = LoggerFactory.getLogger(MessengerStore.class);

    /** System property overriding the configuration directory. */
    public static final String DIR_PROPERTY = "lg3d.messenger.dir";

    static final String ACCOUNTS_FILE = "accounts.json";
    static final String MESSAGES_FILE = "messages.json";
    static final String SETTINGS_FILE = "settings.json";

    private final Path configDir;
    private final ObjectMapper mapper;

    /** Creates a store rooted at {@link #defaultConfigDir()}. */
    public MessengerStore() {
        this(defaultConfigDir());
    }

    /**
     * Creates a store rooted at an explicit directory.
     *
     * @param configDir where the JSON files live; created on first write
     */
    public MessengerStore(Path configDir) {
        this.configDir = configDir;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Resolves the configuration directory.
     *
     * @return {@code lg3d.messenger.dir} when set, else {@code ~/.lg3d/messenger}
     */
    public static Path defaultConfigDir() {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".lg3d", "messenger");
    }

    /** @return the directory this store reads and writes. */
    public Path getConfigDir() {
        return configDir;
    }

    // ------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------

    /** @return the saved accounts, or an empty list on any error. */
    public List<AccountConfig> loadAccounts() {
        return readList(ACCOUNTS_FILE, new TypeReference<List<AccountConfig>>() { });
    }

    /** Persists the accounts (secrets excluded by the model's @JsonIgnore). */
    public void saveAccounts(List<AccountConfig> accounts) {
        write(ACCOUNTS_FILE, nonNull(accounts));
    }

    // ------------------------------------------------------------------
    // Message history
    // ------------------------------------------------------------------

    /** @return the stored transcript (oldest first), or an empty list. */
    public List<StoredMessage> loadMessages() {
        return readList(MESSAGES_FILE, new TypeReference<List<StoredMessage>>() { });
    }

    /**
     * Persists the transcript, trimmed to the newest {@code limit} entries. The
     * list is chronological (oldest first), so the tail is retained.
     *
     * @param messages the transcript
     * @param limit    the maximum entries to keep; {@code <= 0} keeps none
     */
    public void saveMessages(List<StoredMessage> messages, int limit) {
        List<StoredMessage> src = nonNull(messages);
        List<StoredMessage> trimmed;
        if (limit <= 0) {
            trimmed = new ArrayList<>();
        } else if (src.size() <= limit) {
            trimmed = new ArrayList<>(src);
        } else {
            trimmed = new ArrayList<>(src.subList(src.size() - limit, src.size()));
        }
        write(MESSAGES_FILE, trimmed);
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    /** @return the settings, or defaults on any error. */
    public MessengerSettings loadSettings() {
        Path file = configDir.resolve(SETTINGS_FILE);
        if (!Files.isRegularFile(file)) {
            return new MessengerSettings();
        }
        try {
            MessengerSettings s = mapper.readValue(file.toFile(), MessengerSettings.class);
            return (s == null) ? new MessengerSettings() : s;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; using defaults", file, e);
            return new MessengerSettings();
        }
    }

    /** Persists the settings. */
    public void saveSettings(MessengerSettings settings) {
        write(SETTINGS_FILE, (settings == null) ? new MessengerSettings() : settings);
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    private <T> List<T> readList(String fileName, TypeReference<List<T>> type) {
        Path file = configDir.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            List<T> list = mapper.readValue(file.toFile(), type);
            return (list == null) ? new ArrayList<>() : list;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; starting empty", file, e);
            return new ArrayList<>();
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

    private static <T> List<T> nonNull(List<T> list) {
        return (list == null) ? new ArrayList<>() : list;
    }
}
