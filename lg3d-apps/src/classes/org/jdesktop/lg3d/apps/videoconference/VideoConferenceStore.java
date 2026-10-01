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
package org.jdesktop.lg3d.apps.videoconference;

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
 * JSON persistence for the video-conference client: saved {@link ConferenceRoom
 * rooms}, {@link Contact contacts}, the recent-call {@link CallHistoryEntry
 * history} and the {@link VideoConferenceSettings}.
 *
 * <p>Configuration lives under {@code ~/.lg3d/videoconference/} by default.
 * Override with the system property {@link #DIR_PROPERTY} (used by tests to
 * point at a temp folder). Every read is defensive: a missing, empty or corrupt
 * file yields the empty/default value and is logged, never thrown, so a damaged
 * config can never stop the app from opening.</p>
 *
 * <p>The store holds no secrets: a Jitsi room is public-by-name, so unlike the
 * SSH client there is nothing to obfuscate. Display name and e-mail are the only
 * personal data persisted, and only because the meeting needs an identity.</p>
 */
public final class VideoConferenceStore {

    private static final Logger LOG = LoggerFactory.getLogger(VideoConferenceStore.class);

    /** System property overriding the configuration directory. */
    public static final String DIR_PROPERTY = "lg3d.videoconference.dir";

    static final String ROOMS_FILE = "rooms.json";
    static final String CONTACTS_FILE = "contacts.json";
    static final String HISTORY_FILE = "history.json";
    static final String SETTINGS_FILE = "settings.json";

    private final Path configDir;
    private final ObjectMapper mapper;

    /** Creates a store rooted at {@link #defaultConfigDir()}. */
    public VideoConferenceStore() {
        this(defaultConfigDir());
    }

    /**
     * Creates a store rooted at an explicit directory.
     *
     * @param configDir where the JSON files live; created on first write
     */
    public VideoConferenceStore(Path configDir) {
        this.configDir = configDir;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Resolves the configuration directory.
     *
     * @return {@code lg3d.videoconference.dir} when set, else
     *         {@code ~/.lg3d/videoconference}
     */
    public static Path defaultConfigDir() {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".lg3d", "videoconference");
    }

    /** @return the directory this store reads and writes. */
    public Path getConfigDir() {
        return configDir;
    }

    // ------------------------------------------------------------------
    // Rooms
    // ------------------------------------------------------------------

    /** @return the saved rooms, or an empty list on any error. */
    public List<ConferenceRoom> loadRooms() {
        return readList(ROOMS_FILE, new TypeReference<List<ConferenceRoom>>() { });
    }

    /** Persists the saved rooms. */
    public void saveRooms(List<ConferenceRoom> rooms) {
        write(ROOMS_FILE, nonNull(rooms));
    }

    // ------------------------------------------------------------------
    // Contacts
    // ------------------------------------------------------------------

    /** @return the saved contacts, or an empty list on any error. */
    public List<Contact> loadContacts() {
        return readList(CONTACTS_FILE, new TypeReference<List<Contact>>() { });
    }

    /** Persists the contacts. */
    public void saveContacts(List<Contact> contacts) {
        write(CONTACTS_FILE, nonNull(contacts));
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    /** @return the recent-call history, or an empty list on any error. */
    public List<CallHistoryEntry> loadHistory() {
        return readList(HISTORY_FILE, new TypeReference<List<CallHistoryEntry>>() { });
    }

    /**
     * Persists the recent-call history, trimmed to {@code limit} newest entries.
     *
     * @param history the history (assumed newest-first)
     * @param limit   the maximum entries to retain; {@code <= 0} keeps none
     */
    public void saveHistory(List<CallHistoryEntry> history, int limit) {
        List<CallHistoryEntry> src = nonNull(history);
        List<CallHistoryEntry> trimmed = (limit <= 0)
                ? new ArrayList<>()
                : new ArrayList<>(src.subList(0, Math.min(limit, src.size())));
        write(HISTORY_FILE, trimmed);
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    /** @return the settings, or defaults on any error. */
    public VideoConferenceSettings loadSettings() {
        Path file = configDir.resolve(SETTINGS_FILE);
        if (!Files.isRegularFile(file)) {
            return new VideoConferenceSettings();
        }
        try {
            VideoConferenceSettings s = mapper.readValue(file.toFile(), VideoConferenceSettings.class);
            return (s == null) ? new VideoConferenceSettings() : s;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; using defaults", file, e);
            return new VideoConferenceSettings();
        }
    }

    /** Persists the settings. */
    public void saveSettings(VideoConferenceSettings settings) {
        write(SETTINGS_FILE, (settings == null) ? new VideoConferenceSettings() : settings);
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
