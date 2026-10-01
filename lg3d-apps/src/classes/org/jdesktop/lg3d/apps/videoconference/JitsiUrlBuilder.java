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

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Builds Jitsi Meet room URLs from a {@link ConferenceRoom} plus the
 * application {@link VideoConferenceSettings}.
 *
 * <p>This is the one piece of real "conferencing protocol" in the client and it
 * is deliberately pure and side-effect free (no AWT, no I/O, no network) so it
 * is exhaustively unit-testable. The audio/video session itself is carried by
 * the launched Jitsi Meet client (WebRTC in a browser or an external softphone);
 * this class only produces the correct, correctly-encoded deep link that puts
 * the user in the right room with the right identity and mute state.</p>
 *
 * <p>Jitsi reads configuration from the URL <em>fragment</em>:
 * {@code https://<domain>/<room>#config.<key>=<value>&userInfo.<key>.%22<value>%22}.
 * Boolean config values are lower-case; user-info values are percent-encoded and
 * wrapped in percent-encoded double quotes.</p>
 */
public final class JitsiUrlBuilder {

    /** Characters that survive room-name sanitization (URL-unreserved subset). */
    private static final String ROOM_SAFE =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-";

    /** Adjectives + nouns for friendly generated room names. */
    private static final String[] ADJECTIVES = {
        "Amber", "Azure", "Brave", "Calm", "Cedar", "Coral", "Crimson", "Crystal",
        "Emerald", "Golden", "Iron", "Jade", "Lively", "Noble", "Onyx", "Opal",
        "Quiet", "Rapid", "Ruby", "Silver", "Solar", "Swift", "Umber", "Vivid"
    };
    private static final String[] NOUNS = {
        "Anchor", "Beacon", "Canyon", "Comet", "Delta", "Echo", "Falcon", "Fjord",
        "Grove", "Harbor", "Lantern", "Meadow", "Nebula", "Orbit", "Pinnacle",
        "Quartz", "Ridge", "Summit", "Tundra", "Vertex", "Willow", "Zenith"
    };

    private static final SecureRandom RANDOM = new SecureRandom();

    private JitsiUrlBuilder() {
    }

    // ------------------------------------------------------------------
    // Room names & domains
    // ------------------------------------------------------------------

    /**
     * Reduces a free-text room name to a Jitsi-safe, URL-safe token: keeps
     * {@code [A-Za-z0-9._-]} and drops everything else (spaces, punctuation,
     * accents, emoji). The result is what actually appears in the URL path.
     *
     * @param raw the room name as typed by the user
     * @return the sanitized token, or an empty string if nothing usable remains
     */
    public static String sanitizeRoom(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ROOM_SAFE.indexOf(ch) >= 0) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /**
     * Normalizes a domain: trims whitespace, strips a leading scheme
     * ({@code http://} / {@code https://}) and any trailing slash or path.
     *
     * @param domain the raw domain text
     * @return a bare host suitable for a URL authority, or empty when blank
     */
    public static String normalizeDomain(String domain) {
        if (domain == null) {
            return "";
        }
        String d = domain.trim();
        int scheme = d.indexOf("://");
        if (scheme >= 0) {
            d = d.substring(scheme + 3);
        }
        int slash = d.indexOf('/');
        if (slash >= 0) {
            d = d.substring(0, slash);
        }
        // Drop any user-info or port-looking leftovers are kept (port is valid);
        // only strip a trailing '@' credential separator's left side is unusual,
        // so leave the remainder untouched.
        return d.trim();
    }

    /**
     * Resolves the domain a room joins on: the room's own domain when set,
     * otherwise the application default.
     *
     * @param room     the room
     * @param settings the application settings
     * @return the effective normalized domain (never blank)
     */
    public static String resolveDomain(ConferenceRoom room, VideoConferenceSettings settings) {
        VideoConferenceSettings s = (settings == null) ? new VideoConferenceSettings() : settings;
        String roomDomain = (room == null) ? "" : normalizeDomain(room.getDomain());
        return roomDomain.isEmpty() ? s.getDefaultDomain() : roomDomain;
    }

    /**
     * Generates a friendly, hard-to-guess room name for an ad-hoc meeting, e.g.
     * {@code AmberFalcon-4821}. Collisions are astronomically unlikely and the
     * name is easy to read aloud when inviting someone by phone.
     *
     * @return a new random room name
     */
    public static String generateRoomName() {
        String adj = ADJECTIVES[RANDOM.nextInt(ADJECTIVES.length)];
        String noun = NOUNS[RANDOM.nextInt(NOUNS.length)];
        int n = 1000 + RANDOM.nextInt(9000);
        return adj + noun + "-" + n;
    }

    // ------------------------------------------------------------------
    // URL construction
    // ------------------------------------------------------------------

    /**
     * Builds the clean, shareable room link with no personal configuration:
     * {@code https://<domain>/<room>}. This is the URL to copy and send to
     * invitees so they join without inheriting this user's identity or mute
     * state.
     *
     * @param room     the room
     * @param settings the application settings (for the default domain)
     * @return the bare room URL, or {@code null} when the room name is empty
     */
    public static String buildShareUrl(ConferenceRoom room, VideoConferenceSettings settings) {
        String path = sanitizeRoom((room == null) ? null : room.getName());
        if (path.isEmpty()) {
            return null;
        }
        return "https://" + resolveDomain(room, settings) + "/" + path;
    }

    /**
     * Builds the full deep link this user joins with: the room path plus a
     * {@code #config.*} / {@code #userInfo.*} fragment encoding the effective
     * mute state, pre-join behaviour, display name and e-mail.
     *
     * @param room     the room
     * @param settings the application settings
     * @return the join URL, or {@code null} when the room name is empty
     */
    public static String buildJoinUrl(ConferenceRoom room, VideoConferenceSettings settings) {
        String path = sanitizeRoom((room == null) ? null : room.getName());
        if (path.isEmpty()) {
            return null;
        }
        VideoConferenceSettings s = (settings == null) ? new VideoConferenceSettings() : settings;
        String base = "https://" + resolveDomain(room, settings) + "/" + path;

        boolean audioMuted = resolveAudioMuted(room, s);
        boolean videoMuted = resolveVideoMuted(room, s);

        StringBuilder frag = new StringBuilder();
        appendConfig(frag, "startWithAudioMuted", Boolean.toString(audioMuted));
        appendConfig(frag, "startWithVideoMuted", Boolean.toString(videoMuted));
        if (s.isDisablePrejoinPage()) {
            appendConfig(frag, "prejoinPageEnabled", "false");
        }
        if (s.isEnableWelcomePage()) {
            appendConfig(frag, "enableWelcomePage", "true");
        }
        String displayName = (s.getDisplayName() == null) ? "" : s.getDisplayName().trim();
        if (!displayName.isEmpty()) {
            appendUserInfo(frag, "displayName", displayName);
        }
        String email = (s.getEmail() == null) ? "" : s.getEmail().trim();
        if (!email.isEmpty()) {
            appendUserInfo(frag, "email", email);
        }
        return (frag.length() == 0) ? base : base + "#" + frag;
    }

    /** @return true when audio should start muted (room override wins). */
    public static boolean resolveAudioMuted(ConferenceRoom room, VideoConferenceSettings settings) {
        VideoConferenceSettings s = (settings == null) ? new VideoConferenceSettings() : settings;
        if (room != null && room.getStartAudioMuted() != ConferenceRoom.INHERIT) {
            return room.getStartAudioMuted() == 1;
        }
        return s.isStartWithAudioMuted();
    }

    /** @return true when video should start muted (room override wins). */
    public static boolean resolveVideoMuted(ConferenceRoom room, VideoConferenceSettings settings) {
        VideoConferenceSettings s = (settings == null) ? new VideoConferenceSettings() : settings;
        if (room != null && room.getStartVideoMuted() != ConferenceRoom.INHERIT) {
            return room.getStartVideoMuted() == 1;
        }
        return s.isStartWithVideoMuted();
    }

    /**
     * Substitutes {@link VideoConferenceSettings#URL_TOKEN} in the external
     * command template with the meeting URL. When the template has no token the
     * URL is appended as a trailing argument.
     *
     * @param template the external command template
     * @param url      the meeting URL
     * @return the concrete command string, or empty when the template is blank
     */
    public static String buildExternalCommand(String template, String url) {
        if (template == null || template.isBlank()) {
            return "";
        }
        String safeUrl = (url == null) ? "" : url;
        if (template.contains(VideoConferenceSettings.URL_TOKEN)) {
            return template.replace(VideoConferenceSettings.URL_TOKEN, safeUrl);
        }
        String t = template.trim();
        return t + (t.endsWith(" ") ? "" : " ") + safeUrl;
    }

    // ------------------------------------------------------------------
    // Encoding helpers
    // ------------------------------------------------------------------

    private static void appendConfig(StringBuilder frag, String key, String value) {
        if (frag.length() > 0) {
            frag.append('&');
        }
        frag.append("config.").append(key).append('=').append(value);
    }

    private static void appendUserInfo(StringBuilder frag, String key, String value) {
        if (frag.length() > 0) {
            frag.append('&');
        }
        // Jitsi expects userInfo.<key>."<percent-encoded value>" where the
        // surrounding double quotes are themselves percent-encoded (%22).
        frag.append("userInfo.").append(key).append('.')
                .append(percentEncode("\"" + value + "\""));
    }

    /**
     * Percent-encodes a string for use in a URL fragment: every byte outside the
     * RFC 3986 unreserved set ({@code A-Za-z0-9-._~}) becomes {@code %XX} of its
     * UTF-8 encoding. Space becomes {@code %20} (not {@code +}).
     *
     * @param value the text to encode
     * @return the percent-encoded form
     */
    public static String percentEncode(String value) {
        if (value == null) {
            return "";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            int c = b & 0xFF;
            boolean unreserved =
                    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (unreserved) {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }
}
