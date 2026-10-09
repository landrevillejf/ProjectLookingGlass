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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure {@link DiscoveryPacket} codec: an announce and a goodbye
 * round-trip exactly (including a unicode nickname and a zero port), a packet decodes
 * from an offset within a larger buffer (as a datagram delivers it), and every
 * malformed shape - null, truncated, oversized, out-of-bounds slice, a foreign
 * version and an out-of-range port - is rejected with an {@link IOException} rather
 * than parsed into garbage. Value semantics (equals/hashCode) and the accessors are
 * covered too.
 */
class DiscoveryPacketTest {

    @Test
    @DisplayName("an announce round-trips through the wire format")
    void announceRoundTrip() throws IOException {
        DiscoveryPacket packet = DiscoveryPacket.announce("AA:BB:CC", "alice", 47001);
        byte[] wire = packet.encode();
        DiscoveryPacket decoded = DiscoveryPacket.decode(wire);
        assertEquals(packet, decoded);
        assertEquals(DiscoveryPacket.Type.ANNOUNCE, decoded.getType());
        assertFalse(decoded.isGoodbye());
        assertEquals("AA:BB:CC", decoded.getFingerprint());
        assertEquals("alice", decoded.getNickname());
        assertEquals(47001, decoded.getPort());
        assertEquals(packet.getEpochMs(), decoded.getEpochMs());
        assertTrue(decoded.getEpochMs() > 0);
    }

    @Test
    @DisplayName("a goodbye round-trips and reports isGoodbye")
    void goodbyeRoundTrip() throws IOException {
        DiscoveryPacket packet = DiscoveryPacket.goodbye("FF:00", "bob", 1);
        DiscoveryPacket decoded = DiscoveryPacket.decode(packet.encode());
        assertEquals(DiscoveryPacket.Type.GOODBYE, decoded.getType());
        assertTrue(decoded.isGoodbye());
        assertEquals("bob", decoded.getNickname());
        assertEquals(1, decoded.getPort());
        assertEquals(packet, decoded);
    }

    @Test
    @DisplayName("a unicode nickname and empty fields survive the round-trip")
    void unicodeAndEmpty() throws IOException {
        DiscoveryPacket uni = new DiscoveryPacket(DiscoveryPacket.Type.ANNOUNCE,
                "f0:9f", "tr\u00e4nds \u2764", 65535, 123L);
        DiscoveryPacket decoded = DiscoveryPacket.decode(uni.encode());
        assertEquals("tr\u00e4nds \u2764", decoded.getNickname());
        assertEquals(65535, decoded.getPort());
        assertEquals(123L, decoded.getEpochMs());

        DiscoveryPacket empty = new DiscoveryPacket(DiscoveryPacket.Type.ANNOUNCE, null, null, 0, 0L);
        DiscoveryPacket emptyDecoded = DiscoveryPacket.decode(empty.encode());
        assertEquals("", emptyDecoded.getFingerprint() == null ? "" : emptyDecoded.getFingerprint());
        assertEquals(0, emptyDecoded.getPort());
    }

    @Test
    @DisplayName("a packet decodes from an offset inside a larger buffer")
    void decodesFromOffset() throws IOException {
        byte[] packet = DiscoveryPacket.announce("AA:BB", "alice", 47001).encode();
        byte[] buffer = new byte[3 + packet.length + 5];
        buffer[0] = 0x11;
        buffer[1] = 0x22;
        buffer[2] = 0x33;
        System.arraycopy(packet, 0, buffer, 3, packet.length);
        for (int i = 0; i < 5; i++) {
            buffer[3 + packet.length + i] = (byte) 0xFF; // trailing garbage
        }
        DiscoveryPacket decoded = DiscoveryPacket.decode(buffer, 3, packet.length);
        assertEquals("alice", decoded.getNickname());
        assertEquals(47001, decoded.getPort());
    }

    @Test
    @DisplayName("malformed packets are rejected")
    void rejectsMalformed() {
        assertThrows(IOException.class, () -> DiscoveryPacket.decode(null));
        assertThrows(IOException.class, () -> DiscoveryPacket.decode(new byte[0]));
        // Shorter than the smallest well-formed packet.
        assertThrows(IOException.class, () -> DiscoveryPacket.decode(new byte[DiscoveryPacket.MIN_BYTES - 1]));
        // Larger than the cap.
        assertThrows(IOException.class, () -> DiscoveryPacket.decode(new byte[DiscoveryPacket.MAX_BYTES + 1]));
        // A slice whose bounds run off the end of the buffer.
        byte[] some = DiscoveryPacket.announce("a", "b", 1).encode();
        assertThrows(IOException.class, () -> DiscoveryPacket.decode(some, 0, some.length + 10));
        assertThrows(IOException.class, () -> DiscoveryPacket.decode(some, -1, 4));
    }

    @Test
    @DisplayName("a truncated packet is rejected when reading its strings")
    void rejectsTruncated() {
        byte[] wire = DiscoveryPacket.announce("AA:BB:CC:DD", "alice", 47001).encode();
        byte[] cut = new byte[wire.length - 3]; // lop off part of the nickname
        System.arraycopy(wire, 0, cut, 0, cut.length);
        assertThrows(IOException.class, () -> DiscoveryPacket.decode(cut));
    }

    @Test
    @DisplayName("a foreign version byte is rejected")
    void rejectsForeignVersion() {
        byte[] wire = DiscoveryPacket.announce("AA:BB", "alice", 47001).encode();
        wire[0] = (byte) (DiscoveryPacket.VERSION + 1);
        IOException ex = assertThrows(IOException.class, () -> DiscoveryPacket.decode(wire));
        assertTrue(ex.getMessage().contains("version"), ex.getMessage());
    }

    @Test
    @DisplayName("an out-of-range port is rejected")
    void rejectsBadPort() {
        byte[] negative = new DiscoveryPacket(DiscoveryPacket.Type.ANNOUNCE, "a", "b", -1, 0L).encode();
        IOException ex = assertThrows(IOException.class, () -> DiscoveryPacket.decode(negative));
        assertTrue(ex.getMessage().contains("port"), ex.getMessage());
    }

    @Test
    @DisplayName("value semantics: equals, hashCode and toString")
    void valueSemantics() {
        DiscoveryPacket a = new DiscoveryPacket(DiscoveryPacket.Type.ANNOUNCE, "fp", "nick", 1, 100L);
        DiscoveryPacket b = new DiscoveryPacket(DiscoveryPacket.Type.ANNOUNCE, "fp", "nick", 1, 100L);
        DiscoveryPacket differentPort = new DiscoveryPacket(DiscoveryPacket.Type.ANNOUNCE, "fp", "nick", 2, 100L);
        DiscoveryPacket goodbye = new DiscoveryPacket(DiscoveryPacket.Type.GOODBYE, "fp", "nick", 1, 100L);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a, a);
        assertNotEquals(a, differentPort);
        assertNotEquals(a, goodbye);
        assertNotEquals(a, "not a packet");
        assertNotEquals(a, null);
        assertTrue(a.toString().contains("ANNOUNCE"));
        assertTrue(goodbye.toString().contains("GOODBYE"));
    }

    @Test
    @DisplayName("a null type defaults to ANNOUNCE")
    void nullTypeDefaultsToAnnounce() throws IOException {
        DiscoveryPacket packet = new DiscoveryPacket(null, "fp", "nick", 1, 1L);
        assertEquals(DiscoveryPacket.Type.ANNOUNCE, packet.getType());
        assertFalse(packet.isGoodbye());
        assertEquals(DiscoveryPacket.Type.ANNOUNCE, DiscoveryPacket.decode(packet.encode()).getType());
    }
}
