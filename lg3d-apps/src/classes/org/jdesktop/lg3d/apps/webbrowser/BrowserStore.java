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
package org.jdesktop.lg3d.apps.webbrowser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.apps.webbrowser.ext.ExtensionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON persistence for the web browser: the {@link BrowserSettings}, the
 * {@link Bookmark bookmarks}, the {@link HistoryEntry history}, the
 * {@link DownloadRecord downloads} and the open-tab session (a list of URLs
 * restored on next launch).
 *
 * <p>Configuration lives under {@code ~/.lg3d/webbrowser/} by default; override
 * with the system property {@link #DIR_PROPERTY} (tests point it at a temp
 * folder, or construct the store with an explicit {@link Path}). Every read is
 * defensive: a missing, empty or corrupt file yields the empty/default value and
 * is logged, never thrown, so a damaged profile can never stop the browser from
 * opening.</p>
 *
 * <p>This class holds no AWT or JavaFX references and does no rendering, so it
 * exercises identically in a headless unit test and in the desktop.</p>
 */
public final class BrowserStore {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserStore.class);

    /** System property overriding the configuration directory. */
    public static final String DIR_PROPERTY = "lg3d.webbrowser.dir";

    static final String SETTINGS_FILE = "settings.json";
    static final String BOOKMARKS_FILE = "bookmarks.json";
    static final String HISTORY_FILE = "history.json";
    static final String DOWNLOADS_FILE = "downloads.json";
    static final String SESSION_FILE = "session.json";
    static final String EXTENSIONS_FILE = "extensions.json";

    private final Path configDir;
    private final ObjectMapper mapper;

    /** Creates a store rooted at {@link #defaultConfigDir()}. */
    public BrowserStore() {
        this(defaultConfigDir());
    }

    /**
     * Creates a store rooted at an explicit directory.
     *
     * @param configDir where the JSON files live; created on first write
     */
    public BrowserStore(Path configDir) {
        this.configDir = configDir;
        this.mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Resolves the configuration directory.
     *
     * @return {@code lg3d.webbrowser.dir} when set, else {@code ~/.lg3d/webbrowser}
     */
    public static Path defaultConfigDir() {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".lg3d", "webbrowser");
    }

    /** @return the directory this store reads and writes. */
    public Path getConfigDir() {
        return configDir;
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    /** @return the settings, or defaults on any error. */
    public BrowserSettings loadSettings() {
        Path file = configDir.resolve(SETTINGS_FILE);
        if (!Files.isRegularFile(file)) {
            return new BrowserSettings();
        }
        try {
            BrowserSettings s = mapper.readValue(file.toFile(), BrowserSettings.class);
            return (s == null) ? new BrowserSettings() : s;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read {}; using defaults", file, e);
            return new BrowserSettings();
        }
    }

    /** Persists the settings. */
    public void saveSettings(BrowserSettings settings) {
        write(SETTINGS_FILE, (settings == null) ? new BrowserSettings() : settings);
    }

    // ------------------------------------------------------------------
    // Bookmarks
    // ------------------------------------------------------------------

    /** @return the saved bookmarks, or an empty list on any error. */
    public List<Bookmark> loadBookmarks() {
        return readList(BOOKMARKS_FILE, new TypeReference<List<Bookmark>>() { });
    }

    /** Persists the bookmarks. */
    public void saveBookmarks(List<Bookmark> bookmarks) {
        write(BOOKMARKS_FILE, nonNull(bookmarks));
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    /** @return the browsing history, or an empty list on any error. */
    public List<HistoryEntry> loadHistory() {
        return readList(HISTORY_FILE, new TypeReference<List<HistoryEntry>>() { });
    }

    /**
     * Persists the history, trimmed to {@code limit} newest entries.
     *
     * @param history the history (assumed newest-first)
     * @param limit   the maximum entries to retain; {@code <= 0} keeps none
     */
    public void saveHistory(List<HistoryEntry> history, int limit) {
        List<HistoryEntry> src = nonNull(history);
        List<HistoryEntry> trimmed = (limit <= 0)
                ? new ArrayList<>()
                : new ArrayList<>(src.subList(0, Math.min(limit, src.size())));
        write(HISTORY_FILE, trimmed);
    }

    // ------------------------------------------------------------------
    // Downloads
    // ------------------------------------------------------------------

    /** @return the recorded downloads, or an empty list on any error. */
    public List<DownloadRecord> loadDownloads() {
        return readList(DOWNLOADS_FILE, new TypeReference<List<DownloadRecord>>() { });
    }

    /** Persists the downloads. */
    public void saveDownloads(List<DownloadRecord> downloads) {
        write(DOWNLOADS_FILE, nonNull(downloads));
    }

    // ------------------------------------------------------------------
    // Session (open tab URLs)
    // ------------------------------------------------------------------

    /** @return the saved open-tab URLs, or an empty list on any error. */
    public List<String> loadSession() {
        return readList(SESSION_FILE, new TypeReference<List<String>>() { });
    }

    /** Persists the open-tab URLs. */
    public void saveSession(List<String> urls) {
        write(SESSION_FILE, nonNull(urls));
    }

    // ------------------------------------------------------------------
    // Extensions (enable / permission-grant state)
    // ------------------------------------------------------------------

    /** @return the persisted extension states, or an empty list on any error. */
    public List<ExtensionState> loadExtensionStates() {
        return readList(EXTENSIONS_FILE, new TypeReference<List<ExtensionState>>() { });
    }

    /** Persists the extension enable/grant states. */
    public void saveExtensionStates(List<ExtensionState> states) {
        write(EXTENSIONS_FILE, nonNull(states));
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
