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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for the {@link BrowserStore} JSON persistence, pointed at a
 * {@link TempDir} so nothing touches the real {@code ~/.lg3d} profile.
 */
class BrowserStoreTest {

    @Test
    @DisplayName("settings round-trip through JSON")
    void settingsRoundTrip(@TempDir Path dir) {
        BrowserStore store = new BrowserStore(dir);
        BrowserSettings s = new BrowserSettings();
        s.setHomePage("https://example.com");
        s.setSearchEngine(SearchEngine.BING);
        s.setPrivateBrowsing(true);
        s.setZoom(1.5d);
        store.saveSettings(s);

        BrowserSettings loaded = store.loadSettings();
        assertEquals("https://example.com", loaded.getHomePage());
        assertEquals(SearchEngine.BING, loaded.getSearchEngine());
        assertTrue(loaded.isPrivateBrowsing());
        assertEquals(1.5d, loaded.getZoom());
    }

    @Test
    @DisplayName("a missing settings file yields defaults, not an error")
    void missingSettingsDefault(@TempDir Path dir) {
        BrowserStore store = new BrowserStore(dir);
        BrowserSettings loaded = store.loadSettings();
        assertEquals(BrowserSettings.DEFAULT_HOME_PAGE, loaded.getHomePage());
        assertEquals(SearchEngine.DUCKDUCKGO, loaded.getSearchEngine());
    }

    @Test
    @DisplayName("a corrupt settings file degrades to defaults")
    void corruptSettingsDefault(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("settings.json"), "{ not valid json ");
        BrowserStore store = new BrowserStore(dir);
        assertEquals(BrowserSettings.DEFAULT_HOME_PAGE, store.loadSettings().getHomePage());
    }

    @Test
    @DisplayName("bookmarks round-trip through JSON")
    void bookmarksRoundTrip(@TempDir Path dir) {
        BrowserStore store = new BrowserStore(dir);
        List<Bookmark> bookmarks = new ArrayList<>();
        bookmarks.add(new Bookmark("Example", "https://example.com", "Work"));
        store.saveBookmarks(bookmarks);

        List<Bookmark> loaded = store.loadBookmarks();
        assertEquals(1, loaded.size());
        assertEquals("Example", loaded.get(0).getTitle());
        assertEquals("https://example.com", loaded.get(0).getUrl());
        assertEquals("Work", loaded.get(0).getFolder());
    }

    @Test
    @DisplayName("history is trimmed to the limit on save")
    void historyTrimmed(@TempDir Path dir) {
        BrowserStore store = new BrowserStore(dir);
        List<HistoryEntry> history = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            history.add(new HistoryEntry("https://" + i + ".com", "n" + i));
        }
        store.saveHistory(history, 2);
        assertEquals(2, store.loadHistory().size());

        store.saveHistory(history, 0);
        assertTrue(store.loadHistory().isEmpty(), "a limit of 0 keeps none");
    }

    @Test
    @DisplayName("downloads and the session round-trip through JSON")
    void downloadsAndSession(@TempDir Path dir) {
        BrowserStore store = new BrowserStore(dir);
        List<DownloadRecord> downloads = new ArrayList<>();
        DownloadRecord d = new DownloadRecord("https://a.com/f.zip", "f.zip");
        d.markComplete("/tmp/f.zip", 10L);
        downloads.add(d);
        store.saveDownloads(downloads);
        assertEquals(1, store.loadDownloads().size());
        assertEquals(DownloadRecord.Status.COMPLETE, store.loadDownloads().get(0).getStatus());

        List<String> session = List.of("https://a.com", "https://b.com");
        store.saveSession(session);
        assertEquals(session, store.loadSession());
    }

    @Test
    @DisplayName("missing list files load as empty lists")
    void missingListsAreEmpty(@TempDir Path dir) {
        BrowserStore store = new BrowserStore(dir);
        assertTrue(store.loadBookmarks().isEmpty());
        assertTrue(store.loadHistory().isEmpty());
        assertTrue(store.loadDownloads().isEmpty());
        assertTrue(store.loadSession().isEmpty());
    }

    @Test
    @DisplayName("getConfigDir reports the directory in use")
    void configDir(@TempDir Path dir) {
        assertEquals(dir, new BrowserStore(dir).getConfigDir());
    }
}
