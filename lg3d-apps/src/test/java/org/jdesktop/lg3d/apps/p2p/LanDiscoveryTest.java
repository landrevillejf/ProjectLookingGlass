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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LanDiscovery}'s pure peer-map and TTL logic, driven directly
 * (no multicast socket required) through {@code onDatagram}/{@code handlePacket}/
 * {@code sweepExpired}: an announcement creates a peer, a repeat refreshes it
 * silently, a changed nickname re-fires, a goodbye and a TTL sweep both remove it
 * and report it lost, our own fingerprint is ignored, and a malformed datagram is
 * dropped without disturbing the map. A final lifecycle test asserts that
 * {@code start()}/{@code close()} never throw and end inert even where multicast is
 * unavailable, so the class is safe to construct headless.
 */
class LanDiscoveryTest {

    private static final String US = "AA:BB:CC:DD";

    @Test
    @DisplayName("an announcement creates a discovered peer")
    void announceCreatesPeer() throws Exception {
        Recorder rec = new Recorder();
        LanDiscovery d = discovery(US, rec);
        InetAddress src = InetAddress.getByName("192.0.2.10");
        d.handlePacket(DiscoveryPacket.announce("11:22:33", "alice", 47001), src);

        assertEquals(1, d.getPeerCount());
        assertEquals(1, rec.found.size());
        LanDiscovery.DiscoveredPeer peer = rec.found.get(0);
        assertEquals("11:22:33", peer.getFingerprint());
        assertEquals("alice", peer.getNickname());
        assertEquals(47001, peer.getPort());
        assertEquals("192.0.2.10", peer.getHostAddress());
        assertEquals(src, peer.getAddress());
        assertTrue(peer.getLastSeenMs() > 0);
        assertTrue(peer.toString().contains("alice"));
        assertEquals(1, d.getPeers().size());
    }

    @Test
    @DisplayName("a repeat announcement refreshes silently; a change re-fires")
    void reannounceAndChange() throws Exception {
        Recorder rec = new Recorder();
        LanDiscovery d = discovery(US, rec);
        InetAddress src = InetAddress.getByName("192.0.2.10");

        d.handlePacket(DiscoveryPacket.announce("11:22", "alice", 100), src);
        d.handlePacket(DiscoveryPacket.announce("11:22", "alice", 100), src);
        assertEquals(1, rec.found.size(), "an identical re-announce must not re-fire");
        assertEquals(1, d.getPeerCount());

        // A changed nickname is user-visible, so it re-fires (still one peer).
        d.handlePacket(DiscoveryPacket.announce("11:22", "alice2", 100), src);
        assertEquals(2, rec.found.size(), "a changed nickname should re-fire onPeerFound");
        assertEquals(1, d.getPeerCount());
        assertEquals("alice2", d.getPeers().get(0).getNickname());
    }

    @Test
    @DisplayName("the fingerprint key is canonical across case and separators")
    void canonicalKey() throws Exception {
        Recorder rec = new Recorder();
        LanDiscovery d = discovery(US, rec);
        InetAddress src = InetAddress.getByName("192.0.2.10");
        d.handlePacket(DiscoveryPacket.announce("aa:bb", "one", 1), src);
        d.handlePacket(DiscoveryPacket.announce("AABB", "one", 1), src); // same identity
        assertEquals(1, d.getPeerCount(), "case/separator variants are the same peer");
    }

    @Test
    @DisplayName("a goodbye removes the peer and reports it lost")
    void goodbyeRemovesPeer() throws Exception {
        Recorder rec = new Recorder();
        LanDiscovery d = discovery(US, rec);
        InetAddress src = InetAddress.getByName("192.0.2.10");
        d.handlePacket(DiscoveryPacket.announce("11:22", "alice", 100), src);
        assertEquals(1, d.getPeerCount());

        d.handlePacket(DiscoveryPacket.goodbye("11:22", "alice", 100), src);
        assertEquals(0, d.getPeerCount());
        assertEquals(1, rec.lost.size());
        assertEquals("alice", rec.lost.get(0).getNickname());

        // A goodbye for an unknown peer is a harmless no-op.
        d.handlePacket(DiscoveryPacket.goodbye("99:99", "ghost", 1), src);
        assertEquals(1, rec.lost.size());
    }

    @Test
    @DisplayName("our own announcements are ignored")
    void selfIgnored() throws Exception {
        Recorder rec = new Recorder();
        LanDiscovery d = discovery(US, rec);
        InetAddress src = InetAddress.getByName("192.0.2.10");
        d.handlePacket(DiscoveryPacket.announce(US, "me", 100), src);
        d.handlePacket(DiscoveryPacket.announce("aa:bb:cc:dd", "me", 100), src); // case variant of US
        assertEquals(0, d.getPeerCount(), "we must never discover ourselves");
        assertTrue(rec.found.isEmpty());
    }

    @Test
    @DisplayName("a blank or null fingerprint is not tracked")
    void blankFingerprintIgnored() throws Exception {
        Recorder rec = new Recorder();
        LanDiscovery d = discovery(US, rec);
        InetAddress src = InetAddress.getByName("192.0.2.10");
        d.handlePacket(null, src);
        d.handlePacket(DiscoveryPacket.announce("", "anon", 100), src);
        d.handlePacket(DiscoveryPacket.announce(null, "anon", 100), src);
        assertEquals(0, d.getPeerCount());
        assertTrue(rec.found.isEmpty());
    }

    @Test
    @DisplayName("a TTL sweep expires a stale peer and keeps a fresh one")
    void ttlExpiry() throws Exception {
        Recorder rec = new Recorder();
        LanDiscovery d = discovery(US, rec);
        InetAddress src = InetAddress.getByName("192.0.2.10");
        d.handlePacket(DiscoveryPacket.announce("11:22", "alice", 100), src);
        d.handlePacket(DiscoveryPacket.announce("33:44", "bob", 200), src);
        assertEquals(2, d.getPeerCount());

        // A generous TTL keeps everyone.
        d.setTtlMs(60_000);
        d.sweepExpired(System.currentTimeMillis());
        assertEquals(2, d.getPeerCount(), "no peer should expire within its TTL");
        assertTrue(rec.lost.isEmpty());

        // A TTL already elapsed for everyone drops them all.
        d.setTtlMs(1);
        d.sweepExpired(System.currentTimeMillis() + 50);
        assertEquals(0, d.getPeerCount(), "stale peers should expire");
        assertEquals(2, rec.lost.size());
    }

    @Test
    @DisplayName("onDatagram decodes a real packet and ignores garbage")
    void onDatagramDispatch() throws Exception {
        Recorder rec = new Recorder();
        LanDiscovery d = discovery(US, rec);
        InetAddress src = InetAddress.getByName("192.0.2.10");

        byte[] wire = DiscoveryPacket.announce("11:22:33", "alice", 47001).encode();
        // Deliver it inside a larger buffer with an offset and trailing slack, as a
        // DatagramPacket would.
        byte[] buffer = new byte[wire.length + 20];
        System.arraycopy(wire, 0, buffer, 4, wire.length);
        d.onDatagram(buffer, 4, wire.length, src);
        assertEquals(1, d.getPeerCount());
        assertEquals("alice", rec.found.get(0).getNickname());

        // Garbage (and a hostile oversize length) must not disturb the map or throw.
        d.onDatagram(new byte[]{1, 2, 3}, 0, 3, src);
        d.onDatagram(new byte[DiscoveryPacket.MAX_BYTES + 1], 0, DiscoveryPacket.MAX_BYTES + 1, src);
        d.onDatagram(null, 0, 0, src);
        assertEquals(1, d.getPeerCount(), "malformed datagrams are ignored");
    }

    @Test
    @DisplayName("accessors and constructor defaults behave")
    void accessorsAndDefaults() {
        Recorder rec = new Recorder();
        LanDiscovery d = new LanDiscovery(US, "me", () -> 4321, rec);
        assertEquals(US, d.getFingerprint());
        assertEquals(0, d.getPeerCount());
        assertTrue(d.getPeers().isEmpty());
        assertFalse(d.isRunning(), "an inert instance is not running");

        // A null/blank group, null supplier and null listener are all tolerated.
        LanDiscovery lenient = new LanDiscovery(null, null, null, null, "  ", 0, 0, 0);
        assertEquals("", lenient.getFingerprint());
        assertNotNull(lenient.getPeers());
        lenient.close(); // safe on an instance that never started
    }

    @Test
    @DisplayName("start()/close() never throw and end inert, even without multicast")
    void startStopIsSafe() {
        Recorder rec = new Recorder();
        // A high, likely-free port and a scoped group; the environment may still
        // refuse multicast, which start() must absorb and report rather than throw.
        LanDiscovery d = new LanDiscovery(US, "me", () -> 4321, rec,
                LanDiscovery.DEFAULT_MULTICAST_GROUP, 47999, 5_000, 1_000);
        boolean started = d.start();
        try {
            assertEquals(started, d.isRunning(), "isRunning reflects start()'s outcome");
            // Starting twice is idempotent.
            assertEquals(started, d.start());
        } finally {
            d.close();
        }
        assertFalse(d.isRunning(), "a closed instance is not running");
        d.close(); // idempotent
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static LanDiscovery discovery(String ourFingerprint, LanDiscovery.Listener listener) {
        return new LanDiscovery(ourFingerprint, "me", () -> 4321, listener);
    }

    private static final class Recorder implements LanDiscovery.Listener {
        final List<LanDiscovery.DiscoveredPeer> found =
                Collections.synchronizedList(new ArrayList<>());
        final List<LanDiscovery.DiscoveredPeer> lost =
                Collections.synchronizedList(new ArrayList<>());
        final List<String> errors = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onPeerFound(LanDiscovery.DiscoveredPeer peer) {
            found.add(peer);
        }

        @Override
        public void onPeerLost(LanDiscovery.DiscoveredPeer peer) {
            lost.add(peer);
        }

        @Override
        public void onError(String error) {
            errors.add(error);
        }
    }
}
