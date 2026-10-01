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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link JitsiUrlBuilder}, the pure conference-URL logic of
 * the Video Conference app: room-name sanitisation, domain normalisation and
 * resolution, friendly name generation, share/join URL construction with the
 * {@code #config.*} / {@code #userInfo.*} fragment, RFC 3986 percent-encoding,
 * effective mute resolution and external-command templating. No network, no AWT
 * and no X display are touched.
 */
class JitsiUrlBuilderTest {

    private static VideoConferenceSettings defaults() {
        return new VideoConferenceSettings();
    }

    // ------------------------------------------------------------------
    // Room-name sanitisation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sanitizeRoom keeps only URL-safe characters")
    void sanitizeRoomDropsUnsafeChars() {
        assertEquals("MyRoom", JitsiUrlBuilder.sanitizeRoom("My Room!!"));
        assertEquals("caf", JitsiUrlBuilder.sanitizeRoom("caf\u00e9"));
        assertEquals("a.b_c-d", JitsiUrlBuilder.sanitizeRoom("a.b_c-d"));
        assertEquals("", JitsiUrlBuilder.sanitizeRoom("***"));
        assertEquals("", JitsiUrlBuilder.sanitizeRoom(null));
        assertEquals("", JitsiUrlBuilder.sanitizeRoom(""));
    }

    // ------------------------------------------------------------------
    // Domain handling
    // ------------------------------------------------------------------

    @Test
    @DisplayName("normalizeDomain strips scheme, path and whitespace")
    void normalizeDomain() {
        assertEquals("meet.jit.si", JitsiUrlBuilder.normalizeDomain("https://meet.jit.si/"));
        assertEquals("meet.jit.si", JitsiUrlBuilder.normalizeDomain("http://meet.jit.si/room"));
        assertEquals("jitsi.example.com", JitsiUrlBuilder.normalizeDomain("  jitsi.example.com  "));
        assertEquals("", JitsiUrlBuilder.normalizeDomain(null));
        assertEquals("", JitsiUrlBuilder.normalizeDomain("   "));
    }

    @Test
    @DisplayName("resolveDomain prefers the room domain, else the default")
    void resolveDomain() {
        ConferenceRoom room = new ConferenceRoom("R", "");
        assertEquals("meet.jit.si", JitsiUrlBuilder.resolveDomain(room, defaults()));
        room.setDomain("jitsi.example.com");
        assertEquals("jitsi.example.com", JitsiUrlBuilder.resolveDomain(room, defaults()));
        // A null settings object still resolves to the built-in default.
        assertEquals("jitsi.example.com", JitsiUrlBuilder.resolveDomain(room, null));
        assertEquals("meet.jit.si", JitsiUrlBuilder.resolveDomain(null, null));
    }

    // ------------------------------------------------------------------
    // Friendly name generation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("generateRoomName produces a URL-safe, non-empty name")
    void generateRoomName() {
        for (int i = 0; i < 20; i++) {
            String name = JitsiUrlBuilder.generateRoomName();
            assertNotNull(name);
            assertFalse(name.isEmpty());
            assertTrue(name.matches("[A-Za-z]+-[0-9]{4}"),
                    "unexpected room name shape: " + name);
            assertEquals(name, JitsiUrlBuilder.sanitizeRoom(name),
                    "a generated name must already be URL-safe");
        }
    }

    // ------------------------------------------------------------------
    // Share URL
    // ------------------------------------------------------------------

    @Test
    @DisplayName("buildShareUrl is the bare room link with no fragment")
    void buildShareUrl() {
        ConferenceRoom room = new ConferenceRoom("Team Sync", "");
        assertEquals("https://meet.jit.si/TeamSync",
                JitsiUrlBuilder.buildShareUrl(room, defaults()));
        room.setDomain("jitsi.example.com");
        assertEquals("https://jitsi.example.com/TeamSync",
                JitsiUrlBuilder.buildShareUrl(room, defaults()));
        assertNull(JitsiUrlBuilder.buildShareUrl(new ConferenceRoom("", ""), defaults()),
                "an empty room name has no share URL");
        assertNull(JitsiUrlBuilder.buildShareUrl(null, defaults()));
    }

    // ------------------------------------------------------------------
    // Join URL
    // ------------------------------------------------------------------

    @Test
    @DisplayName("buildJoinUrl emits config flags for the default settings")
    void buildJoinUrlDefaults() {
        ConferenceRoom room = new ConferenceRoom("Room", "");
        // Default settings: audio on, video muted, pre-join skipped, no identity.
        assertEquals("https://meet.jit.si/Room"
                        + "#config.startWithAudioMuted=false"
                        + "&config.startWithVideoMuted=true"
                        + "&config.prejoinPageEnabled=false",
                JitsiUrlBuilder.buildJoinUrl(room, defaults()));
    }

    @Test
    @DisplayName("buildJoinUrl percent-encodes the display name and e-mail")
    void buildJoinUrlWithIdentity() {
        VideoConferenceSettings s = defaults();
        s.setDisplayName("John Doe");
        s.setEmail("a@b.com");
        ConferenceRoom room = new ConferenceRoom("Room", "");
        String url = JitsiUrlBuilder.buildJoinUrl(room, s);
        assertTrue(url.contains("userInfo.displayName.%22John%20Doe%22"),
                "display name must be quoted and percent-encoded: " + url);
        assertTrue(url.contains("userInfo.email.%22a%40b.com%22"),
                "e-mail must be quoted and percent-encoded: " + url);
    }

    @Test
    @DisplayName("buildJoinUrl honours room-level mute overrides")
    void buildJoinUrlRoomOverrides() {
        VideoConferenceSettings s = defaults();
        s.setStartWithAudioMuted(false);
        s.setStartWithVideoMuted(false);
        ConferenceRoom room = new ConferenceRoom("Room", "");
        room.setStartAudioMuted(1); // force muted
        room.setStartVideoMuted(1); // force muted
        String url = JitsiUrlBuilder.buildJoinUrl(room, s);
        assertTrue(url.contains("config.startWithAudioMuted=true"), url);
        assertTrue(url.contains("config.startWithVideoMuted=true"), url);
    }

    @Test
    @DisplayName("buildJoinUrl omits the pre-join flag when the page is kept")
    void buildJoinUrlKeepsPrejoin() {
        VideoConferenceSettings s = defaults();
        s.setDisablePrejoinPage(false);
        ConferenceRoom room = new ConferenceRoom("Room", "");
        String url = JitsiUrlBuilder.buildJoinUrl(room, s);
        assertFalse(url.contains("prejoinPageEnabled"), url);
    }

    @Test
    @DisplayName("buildJoinUrl returns null for an empty room name")
    void buildJoinUrlEmptyRoom() {
        assertNull(JitsiUrlBuilder.buildJoinUrl(new ConferenceRoom("", ""), defaults()));
        assertNull(JitsiUrlBuilder.buildJoinUrl(null, defaults()));
    }

    // ------------------------------------------------------------------
    // Mute resolution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("mute resolution falls back to settings when the room inherits")
    void resolveMuted() {
        VideoConferenceSettings s = defaults();
        s.setStartWithAudioMuted(true);
        s.setStartWithVideoMuted(false);
        ConferenceRoom inherit = new ConferenceRoom("R", "");
        assertTrue(JitsiUrlBuilder.resolveAudioMuted(inherit, s));
        assertFalse(JitsiUrlBuilder.resolveVideoMuted(inherit, s));

        inherit.setStartAudioMuted(0);
        inherit.setStartVideoMuted(1);
        assertFalse(JitsiUrlBuilder.resolveAudioMuted(inherit, s));
        assertTrue(JitsiUrlBuilder.resolveVideoMuted(inherit, s));

        // Null settings resolve to the built-in defaults (audio on, video muted).
        assertFalse(JitsiUrlBuilder.resolveAudioMuted(new ConferenceRoom("R", ""), null));
        assertTrue(JitsiUrlBuilder.resolveVideoMuted(new ConferenceRoom("R", ""), null));
    }

    // ------------------------------------------------------------------
    // External command
    // ------------------------------------------------------------------

    @Test
    @DisplayName("buildExternalCommand substitutes or appends the URL")
    void buildExternalCommand() {
        assertEquals("jitsi https://x/y",
                JitsiUrlBuilder.buildExternalCommand("jitsi %URL", "https://x/y"));
        assertEquals("jitsi https://x/y",
                JitsiUrlBuilder.buildExternalCommand("jitsi", "https://x/y"));
        assertEquals("jitsi https://x/y",
                JitsiUrlBuilder.buildExternalCommand("jitsi ", "https://x/y"));
        assertEquals("", JitsiUrlBuilder.buildExternalCommand("", "https://x/y"));
        assertEquals("", JitsiUrlBuilder.buildExternalCommand(null, "https://x/y"));
    }

    // ------------------------------------------------------------------
    // Percent encoding
    // ------------------------------------------------------------------

    @Test
    @DisplayName("percentEncode follows RFC 3986 (space is %20, not +)")
    void percentEncode() {
        assertEquals("a%20b", JitsiUrlBuilder.percentEncode("a b"));
        assertEquals("~._-", JitsiUrlBuilder.percentEncode("~._-"));
        assertEquals("%2F", JitsiUrlBuilder.percentEncode("/"));
        assertEquals("%22", JitsiUrlBuilder.percentEncode("\""));
        assertEquals("", JitsiUrlBuilder.percentEncode(null));
        // A multi-byte UTF-8 character becomes one %XX per byte.
        assertEquals("%C3%A9", JitsiUrlBuilder.percentEncode("\u00e9"));
    }
}
