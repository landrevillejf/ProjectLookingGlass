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
package org.jdesktop.lg3d.apps.p2p;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for {@link P2pMessage}, the CONTROL-plane bean. They assert
 * each factory sets the right discriminant and fields, that a message survives a
 * JSON round-trip, that nulls are omitted (compact JSON), that unknown fields from
 * a newer peer are ignored rather than fatal, that malformed/empty JSON decodes to
 * null instead of throwing, and that a message survives a full
 * {@link SecureFrame#control(P2pMessage)} seal-and-parse cycle.
 */
class P2pMessageTest {

    private static P2pMessage roundTrip(P2pMessage m) {
        return P2pMessage.fromJsonBytes(m.toJsonBytes());
    }

    @Test
    @DisplayName("chat and action factories set kind, sender and body")
    void chatAndAction() {
        P2pMessage chat = P2pMessage.chat("alice", "hi");
        assertEquals(P2pMessage.Kind.CHAT, chat.getKind());
        assertEquals("alice", chat.getFrom());
        assertEquals("hi", chat.getText());

        P2pMessage action = P2pMessage.action("bob", "waves");
        assertEquals(P2pMessage.Kind.ACTION, action.getKind());
        assertEquals("waves", action.getText());

        P2pMessage back = roundTrip(chat);
        assertEquals(P2pMessage.Kind.CHAT, back.getKind());
        assertEquals("alice", back.getFrom());
        assertEquals("hi", back.getText());
        assertTrue(back.getEpochMs() > 0);
    }

    @Test
    @DisplayName("presence carries the sender fingerprint")
    void presence() {
        P2pMessage p = P2pMessage.presence("carol", "available", "aa:bb:cc");
        assertEquals(P2pMessage.Kind.PRESENCE, p.getKind());
        assertEquals("available", p.getText());
        assertEquals("aa:bb:cc", p.getFingerprint());
        assertEquals("aa:bb:cc", roundTrip(p).getFingerprint());
    }

    @Test
    @DisplayName("typing round-trips its boolean flag")
    void typing() {
        P2pMessage t = P2pMessage.typing("dave", true);
        assertEquals(P2pMessage.Kind.TYPING, t.getKind());
        assertTrue(t.isTyping());
        assertTrue(roundTrip(t).isTyping());
        assertFalse(P2pMessage.typing("dave", false).isTyping());
        // A message with no typing flag reads as false, never null.
        assertFalse(roundTrip(P2pMessage.ping()).isTyping());
    }

    @Test
    @DisplayName("the file-transfer control plane round-trips every field")
    void fileControlPlane() {
        P2pMessage offer = P2pMessage.fileOffer("erin", "tx-1", "report.pdf", 12345L, "deadbeef");
        assertEquals(P2pMessage.Kind.FILE_OFFER, offer.getKind());
        assertEquals("tx-1", offer.getTransferId());
        assertEquals("report.pdf", offer.getFileName());
        assertEquals(12345L, offer.getFileSize());
        assertEquals("deadbeef", offer.getFileHash());
        P2pMessage offerBack = roundTrip(offer);
        assertEquals("report.pdf", offerBack.getFileName());
        assertEquals(12345L, offerBack.getFileSize());
        assertEquals("deadbeef", offerBack.getFileHash());

        assertEquals(P2pMessage.Kind.FILE_ACCEPT, roundTrip(P2pMessage.fileAccept("erin", "tx-1")).getKind());
        P2pMessage reject = roundTrip(P2pMessage.fileReject("erin", "tx-1", "no thanks"));
        assertEquals(P2pMessage.Kind.FILE_REJECT, reject.getKind());
        assertEquals("no thanks", reject.getText());

        P2pMessage progress = roundTrip(P2pMessage.fileProgress("erin", "tx-1", 6000L));
        assertEquals(P2pMessage.Kind.FILE_PROGRESS, progress.getKind());
        assertEquals(6000L, progress.getBytesDone());

        P2pMessage end = roundTrip(P2pMessage.fileEnd("erin", "tx-1", "cafebabe"));
        assertEquals(P2pMessage.Kind.FILE_END, end.getKind());
        assertEquals("cafebabe", end.getFileHash());

        P2pMessage cancel = roundTrip(P2pMessage.fileCancel("erin", "tx-1", "user aborted"));
        assertEquals(P2pMessage.Kind.FILE_CANCEL, cancel.getKind());
        assertEquals("user aborted", cancel.getText());
    }

    @Test
    @DisplayName("invite carries the room and the ready-to-open share URL")
    void invite() {
        P2pMessage inv = P2pMessage.invite("frank", "lg3d-standup",
                "https://meet.jit.si/lg3d-standup#config.startWithVideoMuted=true");
        assertEquals(P2pMessage.Kind.INVITE, inv.getKind());
        assertEquals("lg3d-standup", inv.getRoom());
        assertTrue(inv.getUrl().startsWith("https://meet.jit.si/"));
        P2pMessage back = roundTrip(inv);
        assertEquals("lg3d-standup", back.getRoom());
        assertEquals(inv.getUrl(), back.getUrl());
    }

    @Test
    @DisplayName("ping, pong and bye are distinguished by kind")
    void keepAliveAndBye() {
        assertEquals(P2pMessage.Kind.PING, roundTrip(P2pMessage.ping()).getKind());
        assertEquals(P2pMessage.Kind.PONG, roundTrip(P2pMessage.pong()).getKind());
        P2pMessage bye = roundTrip(P2pMessage.bye("going offline"));
        assertEquals(P2pMessage.Kind.BYE, bye.getKind());
        assertEquals("going offline", bye.getText());
    }

    @Test
    @DisplayName("null optional fields are omitted from the JSON")
    void compactJson() {
        String json = new String(P2pMessage.ping().toJsonBytes(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"kind\""), json);
        assertFalse(json.contains("transferId"), "unset fields must be omitted: " + json);
        assertFalse(json.contains("fileName"), json);
        // epochMs is always present.
        assertTrue(json.contains("epochMs"), json);
    }

    @Test
    @DisplayName("unknown fields from a newer peer are ignored, not fatal")
    void forwardCompatible() {
        String json = "{\"kind\":\"CHAT\",\"from\":\"gina\",\"text\":\"hi\","
                + "\"someFutureField\":42,\"anotherNew\":\"x\"}";
        P2pMessage m = P2pMessage.fromJsonBytes(json.getBytes(StandardCharsets.UTF_8));
        assertNotNull(m);
        assertEquals(P2pMessage.Kind.CHAT, m.getKind());
        assertEquals("gina", m.getFrom());
        assertEquals("hi", m.getText());
    }

    @Test
    @DisplayName("malformed, empty and null JSON decode to null")
    void malformedJson() {
        assertNull(P2pMessage.fromJsonBytes(null));
        assertNull(P2pMessage.fromJsonBytes(new byte[0]));
        assertNull(P2pMessage.fromJsonBytes("not json".getBytes(StandardCharsets.UTF_8)));
        assertNull(P2pMessage.fromJsonBytes("{".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("numeric getters are null-safe")
    void nullSafeGetters() {
        P2pMessage m = new P2pMessage();
        assertEquals(P2pMessage.Kind.CHAT, m.getKind());
        assertEquals(0L, m.getFileSize());
        assertEquals(0L, m.getBytesDone());
        assertFalse(m.isTyping());
        // A null kind falls back to CHAT rather than NPE-ing downstream.
        m.setKind(null);
        assertEquals(P2pMessage.Kind.CHAT, m.getKind());
        assertNotNull(m.toString());
    }

    @Test
    @DisplayName("a message survives a SecureFrame.control seal-and-parse cycle")
    void throughSecureFrame() {
        P2pMessage original = P2pMessage.fileOffer("heidi", "tx-9", "movie.mkv", 999L, "ff00");
        SecureFrame frame = SecureFrame.control(original);
        assertEquals(SecureFrame.Type.CONTROL, frame.getType());
        P2pMessage parsed = frame.toMessage();
        assertNotNull(parsed);
        assertEquals(P2pMessage.Kind.FILE_OFFER, parsed.getKind());
        assertEquals("movie.mkv", parsed.getFileName());
        assertEquals(999L, parsed.getFileSize());
        // A null message makes an empty CONTROL frame that parses back to null.
        assertNull(SecureFrame.control(null).toMessage());
    }
}
