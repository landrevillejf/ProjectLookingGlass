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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single entry in the recent-calls log: which room was joined, the exact URL
 * that was launched, when, and how it ended.
 *
 * <p>Because the audio/video session itself runs in the launched meeting client
 * (browser or external app), this client cannot measure the true on-call
 * duration; {@link #getDurationSeconds()} therefore records the time the launch
 * dialog was open / the meeting window was in the foreground when known, and is
 * {@code 0} when unknown. The {@link Outcome} captures the launch result.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CallHistoryEntry {

    /** How a join attempt ended. */
    public enum Outcome {
        /** The meeting URL was handed to the browser / external client. */
        LAUNCHED,
        /** No browser or external command was available; the URL was shown. */
        URL_SHOWN,
        /** Launching the meeting client failed. */
        LAUNCH_FAILED,
        /** The user cancelled before joining. */
        CANCELLED
    }

    private String roomId = "";
    private String roomName = "";
    private String url = "";
    private long joinedAtEpochMs = System.currentTimeMillis();
    private long durationSeconds;
    private Outcome outcome = Outcome.LAUNCHED;

    public CallHistoryEntry() {
    }

    /**
     * Creates a history entry for a join.
     *
     * @param roomId   the saved room id (may be empty for an ad-hoc room)
     * @param roomName the room name
     * @param url      the meeting URL that was launched
     * @param outcome  how the launch ended
     */
    public CallHistoryEntry(String roomId, String roomName, String url, Outcome outcome) {
        this.roomId = (roomId == null) ? "" : roomId;
        this.roomName = (roomName == null) ? "" : roomName;
        this.url = (url == null) ? "" : url;
        this.outcome = (outcome == null) ? Outcome.LAUNCHED : outcome;
    }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = (roomId == null) ? "" : roomId; }

    public String getRoomName() { return roomName; }
    public void setRoomName(String roomName) { this.roomName = (roomName == null) ? "" : roomName; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = (url == null) ? "" : url; }

    public long getJoinedAtEpochMs() { return joinedAtEpochMs; }
    public void setJoinedAtEpochMs(long v) { this.joinedAtEpochMs = v; }

    public long getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(long durationSeconds) { this.durationSeconds = durationSeconds; }

    public Outcome getOutcome() { return outcome; }
    public void setOutcome(Outcome outcome) {
        this.outcome = (outcome == null) ? Outcome.LAUNCHED : outcome;
    }

    @Override
    public String toString() {
        return (roomName == null || roomName.isEmpty()) ? url : roomName;
    }
}
