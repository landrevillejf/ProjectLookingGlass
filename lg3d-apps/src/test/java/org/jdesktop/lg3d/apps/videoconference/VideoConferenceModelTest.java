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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the video-conference model beans: null-safety, copy
 * semantics, join bookkeeping, display strings and the settings defaults /
 * clamps. These are pure POJOs, so no AWT or X display is touched.
 */
class VideoConferenceModelTest {

    @Test
    @DisplayName("ConferenceRoom normalises nulls and records joins")
    void conferenceRoom() {
        ConferenceRoom r = new ConferenceRoom();
        r.setName(null);
        r.setDomain(null);
        r.setNotes(null);
        assertEquals("", r.getName());
        assertEquals("", r.getDomain());
        assertEquals("", r.getNotes());
        assertEquals("(unnamed room)", r.toString());

        r.setName("Standup");
        assertEquals("Standup", r.toString());
        assertEquals(0, r.getJoinCount());
        r.recordJoin();
        r.recordJoin();
        assertEquals(2, r.getJoinCount());
        assertTrue(r.getLastJoinedAtEpochMs() > 0);
    }

    @Test
    @DisplayName("ConferenceRoom.copy is independent but keeps the id")
    void conferenceRoomCopy() {
        ConferenceRoom r = new ConferenceRoom("Room", "jitsi.example.com");
        r.setModerator(true);
        r.setJoinCount(3);
        ConferenceRoom c = r.copy();
        assertNotEquals(r, c);
        assertEquals(r.getId(), c.getId(), "the id is copied so history links hold");
        assertEquals("Room", c.getName());
        assertEquals("jitsi.example.com", c.getDomain());
        assertTrue(c.isModerator());
        assertEquals(3, c.getJoinCount());
        c.setName("Other");
        assertEquals("Room", r.getName(), "the copy must not alias the original");
    }

    @Test
    @DisplayName("CallHistoryEntry defaults and null outcome are safe")
    void callHistoryEntry() {
        CallHistoryEntry e = new CallHistoryEntry();
        assertEquals(CallHistoryEntry.Outcome.LAUNCHED, e.getOutcome());
        e.setOutcome(null);
        assertEquals(CallHistoryEntry.Outcome.LAUNCHED, e.getOutcome());
        assertEquals("", e.getRoomId());

        CallHistoryEntry full = new CallHistoryEntry("id", "Room", "https://x/Room",
                CallHistoryEntry.Outcome.URL_SHOWN);
        assertEquals("Room", full.toString());
        assertEquals(CallHistoryEntry.Outcome.URL_SHOWN, full.getOutcome());
        assertEquals("https://x/Room",
                new CallHistoryEntry("id", "", "https://x/Room", null).toString());
    }

    @Test
    @DisplayName("settings defaults, domain fallback, clamps and copy")
    void settings() {
        VideoConferenceSettings s = new VideoConferenceSettings();
        assertEquals(VideoConferenceSettings.DEFAULT_DOMAIN, s.getDefaultDomain());
        assertEquals(VideoConferenceSettings.LaunchMode.BROWSER, s.getLaunchMode());
        assertTrue(s.isStartWithVideoMuted(), "camera-off on join is the safer default");

        s.setDefaultDomain("   ");
        assertEquals(VideoConferenceSettings.DEFAULT_DOMAIN, s.getDefaultDomain(),
                "a blank domain falls back to the default");

        s.setHistoryLimit(-5);
        assertEquals(0, s.getHistoryLimit(), "a negative limit clamps to zero");

        s.setLaunchMode(null);
        assertEquals(VideoConferenceSettings.LaunchMode.BROWSER, s.getLaunchMode());

        s.setDefaultDomain("jitsi.example.com");
        s.setDisplayName("Grace");
        VideoConferenceSettings c = s.copy();
        assertNotEquals(s, c);
        assertEquals("jitsi.example.com", c.getDefaultDomain());
        assertEquals("Grace", c.getDisplayName());
        assertNotNull(c);
    }

    @Test
    @DisplayName("NullCameraCapture reports unavailable and never starts")
    void nullCameraCapture() {
        CameraCapture cap = new CameraCapture.NullCameraCapture();
        assertEquals("none", cap.backendName());
        assertTrue(!cap.isAvailable());
        assertTrue(cap.listDevices().isEmpty());
        assertTrue(!cap.start(null));
        assertTrue(!cap.isRunning());
        cap.stop();
        // detect() with no backend class on the path yields the null backend.
        CameraCapture detected = CameraCapture.detect();
        assertNotNull(detected);
        assertTrue(!detected.isAvailable());
        assertSame(CameraCapture.NullCameraCapture.class, detected.getClass());
    }
}
