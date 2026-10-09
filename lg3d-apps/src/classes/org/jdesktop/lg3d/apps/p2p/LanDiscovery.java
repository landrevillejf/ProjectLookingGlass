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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;

/**
 * LAN auto-discovery over UDP multicast. While running, a node periodically
 * multicasts a {@link DiscoveryPacket} announcing its fingerprint, nickname and TCP
 * listen port, and listens for the same from other nodes on the subnet, surfacing
 * them to a {@link Listener} as {@link DiscoveredPeer}s. A peer that stops
 * announcing expires after a TTL and is reported lost, so the UI's "Discovered
 * peers (LAN)" list reflects who is actually reachable right now.
 *
 * <p><strong>Honest scope.</strong> Discovery is LAN-scoped: the multicast TTL is
 * {@value #MULTICAST_TTL} hop(s), so it does not route beyond the local subnet.
 * Reaching a peer across the internet needs port-forwarding or a reachable host -
 * the UI says so rather than pretending to traverse NAT. And a discovered peer is
 * only a hint to connect: its identity is still verified cryptographically by the
 * handshake and pinned by TOFU, never trusted from the (spoofable) announcement.</p>
 *
 * <p><strong>Threading and headless-safety.</strong> The constructor is inert - it
 * opens no socket and starts no thread - so a headless-constructed owner spawns
 * nothing. {@link #start()} binds the multicast socket; if the environment forbids
 * multicast it reports the problem to the listener and returns {@code false} rather
 * than throwing, so discovery degrades gracefully to manual host:port. The receive
 * loop and the announce/expiry scheduler are daemons. The peer-map and TTL logic
 * ({@link #handlePacket}, {@link #sweepExpired}) are pure and driven directly in
 * tests without any socket.</p>
 */
public final class LanDiscovery implements AutoCloseable {

    /** The administratively-scoped multicast group used for LG3D P2P discovery. */
    public static final String DEFAULT_MULTICAST_GROUP = "239.255.42.99";

    /** The well-known UDP port the discovery group shares. */
    public static final int DEFAULT_PORT = 47700;

    /** Multicast hop limit: 1 keeps announcements on the local subnet. */
    public static final int MULTICAST_TTL = 1;

    /** How often we re-announce ourselves, in milliseconds. */
    public static final long DEFAULT_ANNOUNCE_INTERVAL_MS = 10_000;

    /** A peer not re-announced within this window expires, in milliseconds. */
    public static final long DEFAULT_TTL_MS = 30_000;

    /** Observes discovery events, on the receive/scheduler thread (not the EDT). */
    public interface Listener {
        /** A peer was found, or its nickname/address/port changed. */
        default void onPeerFound(DiscoveredPeer peer) {
        }

        /** A peer expired (TTL) or said goodbye. */
        default void onPeerLost(DiscoveredPeer peer) {
        }

        /** A non-fatal problem (multicast unavailable, a send failure). */
        default void onError(String error) {
        }
    }

    private final String fingerprint;
    private final String nickname;
    private final IntSupplier tcpPortSupplier;
    private final Listener listener;
    private final String groupAddress;
    private final int udpPort;
    private final long announceIntervalMs;

    private final Map<String, DiscoveredPeer> peers = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile long ttlMs;
    private volatile MulticastSocket socket;
    private volatile InetAddress group;
    private volatile Thread receiveThread;
    private volatile ScheduledExecutorService scheduler;

    /**
     * Creates an inert discovery instance using the default group, port and timings.
     *
     * @param fingerprint     our identity fingerprint, stamped into announcements
     * @param nickname        our display name
     * @param tcpPortSupplier supplies our current TCP listen port (may change if the
     *                        node restarts); read at each announcement
     * @param listener        the event sink (may be null)
     */
    public LanDiscovery(String fingerprint, String nickname, IntSupplier tcpPortSupplier,
                        Listener listener) {
        this(fingerprint, nickname, tcpPortSupplier, listener, DEFAULT_MULTICAST_GROUP,
                DEFAULT_PORT, DEFAULT_TTL_MS, DEFAULT_ANNOUNCE_INTERVAL_MS);
    }

    /**
     * Creates an inert discovery instance with full control over the group and
     * timings (used by tests and any deployment that must avoid the defaults).
     *
     * @param fingerprint        our identity fingerprint
     * @param nickname           our display name
     * @param tcpPortSupplier    supplies our current TCP listen port (may be null)
     * @param listener           the event sink (may be null)
     * @param groupAddress       the multicast group to join
     * @param udpPort            the UDP port to bind and multicast to
     * @param ttlMs              the peer expiry window in milliseconds
     * @param announceIntervalMs how often to re-announce, in milliseconds
     */
    public LanDiscovery(String fingerprint, String nickname, IntSupplier tcpPortSupplier,
                        Listener listener, String groupAddress, int udpPort, long ttlMs,
                        long announceIntervalMs) {
        this.fingerprint = (fingerprint == null) ? "" : fingerprint;
        this.nickname = (nickname == null) ? "" : nickname;
        this.tcpPortSupplier = (tcpPortSupplier == null) ? () -> 0 : tcpPortSupplier;
        this.listener = listener;
        this.groupAddress = (groupAddress == null || groupAddress.isBlank())
                ? DEFAULT_MULTICAST_GROUP : groupAddress;
        this.udpPort = udpPort;
        this.ttlMs = (ttlMs <= 0) ? DEFAULT_TTL_MS : ttlMs;
        this.announceIntervalMs = (announceIntervalMs <= 0)
                ? DEFAULT_ANNOUNCE_INTERVAL_MS : announceIntervalMs;
    }

    // ------------------------------------------------------------------
    // Lifecycle (the socket half; headless-guarded)
    // ------------------------------------------------------------------

    /**
     * Binds the multicast socket, joins the group, and starts announcing and
     * listening. Never throws: if multicast is unavailable the problem is reported
     * to the listener and {@code false} is returned, leaving the instance inert.
     *
     * @return true if discovery is now running
     */
    public synchronized boolean start() {
        if (running.get()) {
            return true;
        }
        MulticastSocket s = null;
        try {
            s = new MulticastSocket(udpPort);
            s.setReuseAddress(true);
            s.setTimeToLive(MULTICAST_TTL);
            InetAddress g = InetAddress.getByName(groupAddress);
            if (!g.isMulticastAddress()) {
                throw new IOException("not a multicast group: " + groupAddress);
            }
            NetworkInterface loopback = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
            s.joinGroup(new InetSocketAddress(g, udpPort), loopback);
            this.socket = s;
            this.group = g;
            running.set(true);
            startReceiveLoop(s);
            startScheduler();
            announce(DiscoveryPacket.Type.ANNOUNCE);
            return true;
        } catch (IOException | RuntimeException ex) {
            fireError("LAN discovery unavailable: " + ex.getMessage());
            if (s != null) {
                s.close();
            }
            this.socket = null;
            this.group = null;
            running.set(false);
            return false;
        }
    }

    /** @return true while announcing/listening. */
    public boolean isRunning() {
        return running.get();
    }

    /** Stops discovery: sends a goodbye, leaves the group and releases the socket. */
    @Override
    public synchronized void close() {
        if (running.compareAndSet(true, false)) {
            announce(DiscoveryPacket.Type.GOODBYE); // best-effort, before the socket goes
        }
        ScheduledExecutorService sch = this.scheduler;
        if (sch != null) {
            sch.shutdownNow();
            this.scheduler = null;
        }
        MulticastSocket s = this.socket;
        InetAddress g = this.group;
        if (s != null) {
            try {
                if (g != null && !s.isClosed()) {
                    NetworkInterface loopback =
                            NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
                    s.leaveGroup(new InetSocketAddress(g, udpPort), loopback);
                }
            } catch (IOException | RuntimeException ignored) {
                // best-effort
            }
            s.close();
            this.socket = null;
            this.group = null;
        }
        Thread rt = this.receiveThread;
        if (rt != null) {
            rt.interrupt();
            this.receiveThread = null;
        }
    }

    private void startReceiveLoop(MulticastSocket s) {
        Thread t = new Thread(() -> receiveLoop(s), "p2p-discovery");
        t.setDaemon(true);
        this.receiveThread = t;
        t.start();
    }

    private void receiveLoop(MulticastSocket s) {
        byte[] buf = new byte[DiscoveryPacket.MAX_BYTES];
        while (running.get() && !s.isClosed()) {
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            try {
                s.receive(dp);
            } catch (IOException ex) {
                if (running.get() && !s.isClosed()) {
                    fireError("discovery receive failed: " + ex.getMessage());
                }
                return; // the socket closed or errored: stop listening
            }
            onDatagram(dp.getData(), dp.getOffset(), dp.getLength(), dp.getAddress());
        }
    }

    private void startScheduler() {
        ScheduledExecutorService sch = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "p2p-discovery-timer");
            t.setDaemon(true);
            return t;
        });
        sch.scheduleAtFixedRate(() -> {
            announce(DiscoveryPacket.Type.ANNOUNCE);
            sweepExpired(System.currentTimeMillis());
        }, announceIntervalMs, announceIntervalMs, TimeUnit.MILLISECONDS);
        this.scheduler = sch;
    }

    private void announce(DiscoveryPacket.Type type) {
        MulticastSocket s = this.socket;
        InetAddress g = this.group;
        if (s == null || s.isClosed() || g == null) {
            return;
        }
        try {
            DiscoveryPacket packet = new DiscoveryPacket(type, fingerprint, nickname,
                    tcpPortSupplier.getAsInt(), System.currentTimeMillis());
            byte[] data = packet.encode();
            s.send(new DatagramPacket(data, data.length, g, udpPort));
        } catch (IOException | RuntimeException ex) {
            fireError("discovery announce failed: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Pure logic (driven directly in tests, no socket required)
    // ------------------------------------------------------------------

    /**
     * Decodes one received datagram and folds it into the peer map. A malformed or
     * hostile packet is ignored rather than dropping the listener.
     *
     * @param data   the datagram buffer
     * @param offset the packet start
     * @param length the packet length
     * @param source the sender's address
     */
    void onDatagram(byte[] data, int offset, int length, InetAddress source) {
        try {
            handlePacket(DiscoveryPacket.decode(data, offset, length), source);
        } catch (IOException | RuntimeException ex) {
            // Ignore anything we cannot parse; other apps may share this port.
        }
    }

    /**
     * Folds a decoded packet into the peer map: our own announcements are ignored, a
     * GOODBYE removes the peer immediately, and an ANNOUNCE adds or refreshes it.
     *
     * @param packet the packet (null is ignored)
     * @param source the sender's address
     */
    void handlePacket(DiscoveryPacket packet, InetAddress source) {
        if (packet == null) {
            return;
        }
        String fp = packet.getFingerprint();
        if (fp == null || fp.isBlank() || P2pCrypto.fingerprintsMatch(fp, fingerprint)) {
            return; // nothing to track, or it is us
        }
        String key = canonical(fp);
        if (packet.isGoodbye()) {
            DiscoveredPeer removed = peers.remove(key);
            if (removed != null) {
                firePeerLost(removed);
            }
            return;
        }
        long now = System.currentTimeMillis();
        DiscoveredPeer existing = peers.get(key);
        if (existing == null) {
            DiscoveredPeer peer = new DiscoveredPeer(fp, packet.getNickname(), source,
                    packet.getPort(), now);
            peers.put(key, peer);
            firePeerFound(peer);
        } else if (existing.refresh(packet.getNickname(), source, packet.getPort(), now)) {
            firePeerFound(existing);
        }
    }

    /**
     * Drops every peer not seen within the TTL, reporting each as lost.
     *
     * @param nowMs the current time (a parameter so tests control the clock)
     */
    void sweepExpired(long nowMs) {
        for (Map.Entry<String, DiscoveredPeer> entry : peers.entrySet()) {
            if (nowMs - entry.getValue().getLastSeenMs() > ttlMs) {
                DiscoveredPeer removed = peers.remove(entry.getKey());
                if (removed != null) {
                    firePeerLost(removed);
                }
            }
        }
    }

    /** Overrides the peer TTL (used by tests; a non-positive value is ignored). */
    void setTtlMs(long ttlMs) {
        if (ttlMs > 0) {
            this.ttlMs = ttlMs;
        }
    }

    private static String canonical(String fingerprint) {
        StringBuilder sb = new StringBuilder(fingerprint.length());
        for (int i = 0; i < fingerprint.length(); i++) {
            char c = Character.toLowerCase(fingerprint.charAt(i));
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** @return a snapshot of the currently discovered peers. */
    public List<DiscoveredPeer> getPeers() {
        return new ArrayList<>(peers.values());
    }

    /** @return the number of currently discovered peers. */
    public int getPeerCount() {
        return peers.size();
    }

    /** @return our announced fingerprint. */
    public String getFingerprint() {
        return fingerprint;
    }

    // ------------------------------------------------------------------
    // Listener fan-out (null-safe)
    // ------------------------------------------------------------------

    private void firePeerFound(DiscoveredPeer peer) {
        if (listener != null) {
            listener.onPeerFound(peer);
        }
    }

    private void firePeerLost(DiscoveredPeer peer) {
        if (listener != null) {
            listener.onPeerLost(peer);
        }
    }

    private void fireError(String error) {
        if (listener != null) {
            listener.onError(error);
        }
    }

    // ------------------------------------------------------------------
    // DiscoveredPeer
    // ------------------------------------------------------------------

    /**
     * A peer seen on the LAN: its announced fingerprint, nickname and TCP port, the
     * address its announcement came from, and when it was last seen (for TTL expiry).
     * Mutable only in the fields an announcement refreshes; handed to the
     * {@link Listener}.
     */
    public static final class DiscoveredPeer {
        private final String fingerprint;
        private volatile String nickname;
        private volatile InetAddress address;
        private volatile int port;
        private volatile long lastSeenMs;

        DiscoveredPeer(String fingerprint, String nickname, InetAddress address, int port,
                       long lastSeenMs) {
            this.fingerprint = fingerprint;
            this.nickname = nickname;
            this.address = address;
            this.port = port;
            this.lastSeenMs = lastSeenMs;
        }

        /**
         * Updates the mutable fields on a fresh announcement.
         *
         * @return true if a user-visible field (nickname/address/port) changed
         */
        boolean refresh(String nickname, InetAddress address, int port, long nowMs) {
            boolean changed = !Objects.equals(this.nickname, nickname)
                    || !Objects.equals(this.address, address)
                    || this.port != port;
            this.nickname = nickname;
            this.address = address;
            this.port = port;
            this.lastSeenMs = nowMs;
            return changed;
        }

        /** @return the announced identity fingerprint. */
        public String getFingerprint() {
            return fingerprint;
        }

        /** @return the announced display name (may be blank). */
        public String getNickname() {
            return nickname;
        }

        /** @return the address the announcement arrived from. */
        public InetAddress getAddress() {
            return address;
        }

        /** @return the address as a dotted-quad/host string, for display and dialling. */
        public String getHostAddress() {
            InetAddress a = this.address;
            return (a == null) ? "" : a.getHostAddress();
        }

        /** @return the peer's TCP listen port. */
        public int getPort() {
            return port;
        }

        /** @return when this peer was last seen, in epoch milliseconds. */
        public long getLastSeenMs() {
            return lastSeenMs;
        }

        @Override
        public String toString() {
            String name = (nickname == null || nickname.isBlank()) ? "peer" : nickname;
            return "DiscoveredPeer[" + name + " " + getHostAddress() + ":" + port + " "
                    + fingerprint + "]";
        }
    }
}
