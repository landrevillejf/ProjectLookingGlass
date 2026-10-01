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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless tests for {@link VideoConferenceStore}: JSON round-trips of rooms,
 * contacts, history and settings into a temp directory, defensive recovery from
 * missing or corrupt files, and history trimming. No X display is touched.
 */
class VideoConferenceStoreTest {

    @Test
    @DisplayName("rooms round-trip through JSON")
    void roomsRoundTrip(@TempDir Path dir) {
        VideoConferenceStore store = new VideoConferenceStore(dir);
        ConferenceRoom r = new ConferenceRoom("Team Sync", "jitsi.example.com");
        r.setModerator(true);
        r.setStartAudioMuted(1);
        r.setNotes("weekly");
        r.recordJoin();

        store.saveRooms(List.of(r));
        List<ConferenceRoom> loaded = new VideoConferenceStore(dir).loadRooms();

        assertEquals(1, loaded.size());
        ConferenceRoom got = loaded.get(0);
        assertEquals("Team Sync", got.getName());
        assertEquals("jitsi.example.com", got.getDomain());
        assertTrue(got.isModerator());
        assertEquals(1, got.getStartAudioMuted());
        assertEquals("weekly", got.getNotes());
        assertEquals(1, got.getJoinCount());
        assertEquals(r.getId(), got.getId());
    }

    @Test
    @DisplayName("contacts and settings round-trip through JSON")
    void contactsAndSettingsRoundTrip(@TempDir Path dir) {
        VideoConferenceStore store = new VideoConferenceStore(dir);
        Contact c = new Contact("Ada", "ada@example.com");
        c.setFavorite(true);
        store.saveContacts(List.of(c));

        VideoConferenceSettings s = new VideoConferenceSettings();
        s.setDefaultDomain("jitsi.example.com");
        s.setDisplayName("Grace");
        s.setEmail("grace@example.com");
        s.setLaunchMode(VideoConferenceSettings.LaunchMode.EXTERNAL_COMMAND);
        s.setExternalCommand("jitsi %URL");
        s.setHistoryLimit(7);
        store.saveSettings(s);

        VideoConferenceStore reload = new VideoConferenceStore(dir);
        List<Contact> contacts = reload.loadContacts();
        assertEquals(1, contacts.size());
        assertEquals("Ada", contacts.get(0).getName());
        assertTrue(contacts.get(0).isFavorite());

        VideoConferenceSettings got = reload.loadSettings();
        assertEquals("jitsi.example.com", got.getDefaultDomain());
        assertEquals("Grace", got.getDisplayName());
        assertEquals(VideoConferenceSettings.LaunchMode.EXTERNAL_COMMAND, got.getLaunchMode());
        assertEquals("jitsi %URL", got.getExternalCommand());
        assertEquals(7, got.getHistoryLimit());
    }

    @Test
    @DisplayName("history is trimmed to the newest N entries on save")
    void historyTrimming(@TempDir Path dir) {
        VideoConferenceStore store = new VideoConferenceStore(dir);
        List<CallHistoryEntry> h = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            h.add(new CallHistoryEntry("id" + i, "room" + i, "url" + i,
                    CallHistoryEntry.Outcome.LAUNCHED));
        }
        store.saveHistory(h, 3);
        List<CallHistoryEntry> loaded = new VideoConferenceStore(dir).loadHistory();
        assertEquals(3, loaded.size());
        assertEquals("room0", loaded.get(0).getRoomName(), "newest first is retained");
        assertEquals("room2", loaded.get(2).getRoomName());
    }

    @Test
    @DisplayName("a zero history limit stores nothing")
    void historyLimitZero(@TempDir Path dir) {
        VideoConferenceStore store = new VideoConferenceStore(dir);
        store.saveHistory(List.of(new CallHistoryEntry("id", "room", "url",
                CallHistoryEntry.Outcome.LAUNCHED)), 0);
        assertTrue(new VideoConferenceStore(dir).loadHistory().isEmpty());
    }

    @Test
    @DisplayName("missing files yield empty lists and default settings")
    void missingFilesAreSafe(@TempDir Path dir) {
        VideoConferenceStore store = new VideoConferenceStore(dir.resolve("nope"));
        assertTrue(store.loadRooms().isEmpty());
        assertTrue(store.loadContacts().isEmpty());
        assertTrue(store.loadHistory().isEmpty());
        VideoConferenceSettings s = store.loadSettings();
        assertEquals(VideoConferenceSettings.DEFAULT_DOMAIN, s.getDefaultDomain());
    }

    @Test
    @DisplayName("a corrupt JSON file is recovered as empty, never thrown")
    void corruptFileIsRecovered(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("rooms.json"), "{not json",
                StandardCharsets.UTF_8);
        VideoConferenceStore store = new VideoConferenceStore(dir);
        assertTrue(store.loadRooms().isEmpty());
        assertFalse(store.getConfigDir().resolve("rooms.json").toFile().isDirectory());
    }

    @Test
    @DisplayName("the default directory honours the override system property")
    void defaultDirOverride() {
        String prev = System.getProperty(VideoConferenceStore.DIR_PROPERTY);
        try {
            System.setProperty(VideoConferenceStore.DIR_PROPERTY, "/tmp/vc-test-dir");
            assertEquals(Path.of("/tmp/vc-test-dir"), VideoConferenceStore.defaultConfigDir());
        } finally {
            if (prev == null) {
                System.clearProperty(VideoConferenceStore.DIR_PROPERTY);
            } else {
                System.setProperty(VideoConferenceStore.DIR_PROPERTY, prev);
            }
        }
    }
}
