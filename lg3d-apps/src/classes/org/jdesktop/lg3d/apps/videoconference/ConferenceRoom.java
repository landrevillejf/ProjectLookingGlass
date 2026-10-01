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
import java.util.UUID;

/**
 * A saved video-conference room. A room is nothing more than a name on a
 * conferencing domain (by default a Jitsi Meet deployment): two people who
 * agree on the same {@code domain + name} land in the same meeting, so a room
 * bean is a lightweight, shareable handle rather than a server-side entity.
 *
 * <p>The bean is a plain Jackson-serializable POJO (no AWT, no Java&nbsp;3D) so
 * the whole model layer stays unit-testable headless and can be persisted by
 * {@link VideoConferenceStore}. Per-room overrides (moderator, lock, mute-on-
 * join) default to "inherit from settings" so the common case needs no
 * configuration.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConferenceRoom {

    /** Sentinel meaning "inherit this option from the application settings". */
    public static final int INHERIT = -1;

    private String id = UUID.randomUUID().toString();
    private String name = "";
    private String domain = "";
    private boolean moderator;
    private boolean locked;

    /** {@link #INHERIT} to follow settings, else 0 (off) / 1 (on). */
    private int startAudioMuted = INHERIT;
    /** {@link #INHERIT} to follow settings, else 0 (off) / 1 (on). */
    private int startVideoMuted = INHERIT;

    private String notes = "";
    private long createdAtEpochMs = System.currentTimeMillis();
    private long lastJoinedAtEpochMs;
    private int joinCount;

    public ConferenceRoom() {
    }

    /**
     * Creates a room with a name on the given domain.
     *
     * @param name   the meeting room name
     * @param domain the conferencing domain (empty = use the default)
     */
    public ConferenceRoom(String name, String domain) {
        this.name = (name == null) ? "" : name;
        this.domain = (domain == null) ? "" : domain;
    }

    /** @return a stable identifier for this saved room. */
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    /** @return the meeting room name (sanitized before it reaches a URL). */
    public String getName() { return name; }
    public void setName(String name) { this.name = (name == null) ? "" : name; }

    /** @return the conferencing domain, or empty to use the default. */
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = (domain == null) ? "" : domain; }

    /** @return true when this user should join as a moderator. */
    public boolean isModerator() { return moderator; }
    public void setModerator(boolean moderator) { this.moderator = moderator; }

    /** @return true when the room should be created locked (password). */
    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    /** @return {@link #INHERIT}, 0 (join with audio on) or 1 (muted). */
    public int getStartAudioMuted() { return startAudioMuted; }
    public void setStartAudioMuted(int v) { this.startAudioMuted = v; }

    /** @return {@link #INHERIT}, 0 (join with video on) or 1 (muted). */
    public int getStartVideoMuted() { return startVideoMuted; }
    public void setStartVideoMuted(int v) { this.startVideoMuted = v; }

    /** @return free-form notes about the room. */
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = (notes == null) ? "" : notes; }

    public long getCreatedAtEpochMs() { return createdAtEpochMs; }
    public void setCreatedAtEpochMs(long v) { this.createdAtEpochMs = v; }

    public long getLastJoinedAtEpochMs() { return lastJoinedAtEpochMs; }
    public void setLastJoinedAtEpochMs(long v) { this.lastJoinedAtEpochMs = v; }

    /** @return how many times this room has been joined from this client. */
    public int getJoinCount() { return joinCount; }
    public void setJoinCount(int joinCount) { this.joinCount = joinCount; }

    /** Records a join: bumps the counter and stamps the last-joined time. */
    public void recordJoin() {
        this.joinCount++;
        this.lastJoinedAtEpochMs = System.currentTimeMillis();
    }

    /**
     * @return an independent copy of this room (a fresh {@link #getId()} is
     *         <em>not</em> assigned; the id is copied so history links hold).
     */
    public ConferenceRoom copy() {
        ConferenceRoom c = new ConferenceRoom();
        c.id = this.id;
        c.name = this.name;
        c.domain = this.domain;
        c.moderator = this.moderator;
        c.locked = this.locked;
        c.startAudioMuted = this.startAudioMuted;
        c.startVideoMuted = this.startVideoMuted;
        c.notes = this.notes;
        c.createdAtEpochMs = this.createdAtEpochMs;
        c.lastJoinedAtEpochMs = this.lastJoinedAtEpochMs;
        c.joinCount = this.joinCount;
        return c;
    }

    @Override
    public String toString() {
        return (name == null || name.isEmpty()) ? "(unnamed room)" : name;
    }
}
