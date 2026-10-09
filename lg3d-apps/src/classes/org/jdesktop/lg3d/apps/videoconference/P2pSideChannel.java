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

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jdesktop.lg3d.apps.p2p.FileTransfer;
import org.jdesktop.lg3d.apps.p2p.IdentityStore;
import org.jdesktop.lg3d.apps.p2p.LanDiscovery;
import org.jdesktop.lg3d.apps.p2p.P2pMessage;
import org.jdesktop.lg3d.apps.p2p.P2pNode;

/**
 * The Video Conference app's encrypted peer-to-peer <em>side-channel</em>: a thin
 * controller over the shared {@code apps.p2p} transport ({@link P2pNode} +
 * {@link LanDiscovery} + {@link IdentityStore}) that carries meeting
 * <b>signaling</b>, <b>chat</b> and <b>file transfer</b> between peers.
 *
 * <p><b>Boundary (by design):</b> the real audio/video session still runs through
 * Jitsi Meet in the system browser via {@link JitsiUrlBuilder} &mdash; this class
 * never touches a webcam, a codec or the media path. It exists so peers can find
 * each other on the LAN, exchange an encrypted meeting invite (a share URL),
 * chat and swap files without a server.</p>
 *
 * <p><b>Headless-safe:</b> construction is inert. No socket is bound and no thread
 * is started until {@link #start(String, int)} is called, so the panel that owns
 * this can be built in the headless test JVM. Every {@link Listener} callback
 * fires on the node's I/O thread; the UI marshals onto the EDT.</p>
 */
public final class P2pSideChannel implements P2pNode.Listener, AutoCloseable {

    /**
     * The event sink for the side-channel. Callbacks arrive on the transport's
     * I/O thread, never the EDT, so a Swing caller must marshal before touching a
     * widget. Every method has an empty default so a partial sink stays terse.
     */
    public interface Listener {
        /** The connected-peer or discovered-peer set changed. */
        default void onPeersChanged() {
        }

        /** A chat or {@code /me}-style action arrived from {@code peerName}. */
        default void onChat(String peerName, String text, boolean action) {
        }

        /** A meeting invite arrived: {@code room} and its Jitsi share {@code url}. */
        default void onInvite(String peerName, String room, String url) {
        }

        /** A peer offered us a file; call {@link #acceptFile}/{@link #rejectFile}. */
        default void onFileOffer(String peerName, FileTransfer transfer) {
        }

        /** Bytes moved on a transfer. */
        default void onFileProgress(String peerName, FileTransfer transfer) {
        }

        /** A transfer finished and (for a receive) verified. */
        default void onFileComplete(String peerName, FileTransfer transfer) {
        }

        /** A transfer was rejected, cancelled or failed. */
        default void onFileClosed(String peerName, FileTransfer transfer) {
        }

        /** A human-readable status line worth showing. */
        default void onStatus(String status) {
        }

        /** A non-fatal problem worth surfacing. */
        default void onError(String error) {
        }
    }

    private final IdentityStore identityStore;
    private final Path downloadDir;
    private final Listener listener;
    /** transferId -> the fingerprint of the peer that owns it (for accept/reject). */
    private final Map<String, String> transferToPeer = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile P2pNode node;
    private volatile LanDiscovery discovery;
    private volatile boolean discoveryEnabled = true;

    /**
     * Creates an inert side-channel. Nothing is bound or started until
     * {@link #start(String, int)}.
     *
     * @param identityStore the long-term identity store (never null)
     * @param downloadDir   where received files land (null falls back to a temp dir)
     * @param listener      the event sink (may be null)
     */
    public P2pSideChannel(IdentityStore identityStore, Path downloadDir, Listener listener) {
        if (identityStore == null) {
            throw new IllegalArgumentException("identityStore is required");
        }
        this.identityStore = identityStore;
        this.downloadDir = downloadDir;
        this.listener = listener;
    }

    /** Enables or disables LAN multicast announce/listen (default on). */
    public void setDiscoveryEnabled(boolean discoveryEnabled) {
        this.discoveryEnabled = discoveryEnabled;
    }

    /** @return true if LAN discovery will be started alongside the node. */
    public boolean isDiscoveryEnabled() {
        return discoveryEnabled;
    }

    /**
     * Loads (or creates) our identity, starts listening and, optionally, begins
     * LAN discovery. Idempotent: a running channel is left untouched. Never
     * throws; a failure is reported to the listener and {@code false} returned.
     *
     * @param nickname   our display name (blank falls back to a fingerprint tag)
     * @param listenPort the TCP port to bind ({@code <= 0} for an ephemeral port)
     * @return true if the channel is now running
     */
    public synchronized boolean start(String nickname, int listenPort) {
        if (running.get()) {
            return true;
        }
        try {
            KeyPair id = identityStore.loadOrCreateIdentity();
            P2pNode n = new P2pNode(id, nickname, downloadDir, this);
            n.start(Math.max(0, listenPort));
            this.node = n;
            this.running.set(true);
            if (discoveryEnabled) {
                startDiscovery(n);
            }
            fireStatus("P2P listening on port " + n.getPort()
                    + " \u2014 fingerprint " + n.getFingerprint());
            return true;
        } catch (IOException | RuntimeException ex) {
            this.node = null;
            this.running.set(false);
            fireError("Could not start the P2P side-channel: " + ex.getMessage());
            return false;
        }
    }

    private void startDiscovery(P2pNode n) {
        LanDiscovery d = new LanDiscovery(n.getFingerprint(), n.getNickname(), n::getPort,
                new DiscoveryEvents());
        if (d.start()) {
            this.discovery = d;
        }
    }

    /** @return true while the node's inbound server is up. */
    public boolean isRunning() {
        P2pNode n = this.node;
        return running.get() && n != null && n.isRunning();
    }

    /** @return the TCP port we listen on, or -1 when not started. */
    public int getPort() {
        P2pNode n = this.node;
        return (n == null) ? -1 : n.getPort();
    }

    /** @return the SHA-256 fingerprint of our static key, or null when not started. */
    public String getOurFingerprint() {
        P2pNode n = this.node;
        return (n == null) ? null : n.getFingerprint();
    }

    /** @return the identity store backing this channel. */
    public IdentityStore getIdentityStore() {
        return identityStore;
    }

    /** @return the underlying node, or null when not started (test/inspection seam). */
    public P2pNode getNode() {
        return node;
    }

    /** @return the currently connected peers (never null). */
    public List<P2pNode.Peer> getPeers() {
        P2pNode n = this.node;
        return (n == null) ? List.of() : n.getPeers();
    }

    /** @return the peers discovered on the LAN (never null). */
    public List<LanDiscovery.DiscoveredPeer> getDiscoveredPeers() {
        LanDiscovery d = this.discovery;
        return (d == null) ? List.of() : d.getPeers();
    }

    /**
     * Dials a discovered peer on a daemon thread so the caller (the EDT) never
     * blocks on a handshake.
     *
     * @param host the peer host
     * @param port the peer port
     * @return true if a connection attempt was dispatched
     */
    public boolean connectToPeer(String host, int port) {
        P2pNode n = this.node;
        if (n == null || host == null || host.isBlank()) {
            return false;
        }
        Thread t = new Thread(() -> {
            try {
                n.connect(host, port);
            } catch (IOException | java.security.GeneralSecurityException | RuntimeException ex) {
                fireError("Could not reach " + host + ":" + port + " \u2014 " + ex.getMessage());
            }
        }, "vc-p2p-connect");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** Sends chat to one peer; false when not running or the peer is unknown. */
    public boolean sendChat(String fingerprint, String text) {
        P2pNode n = this.node;
        return n != null && n.sendChat(fingerprint, text);
    }

    /** Broadcasts chat to every connected peer; @return the number reached. */
    public int broadcastChat(String text) {
        P2pNode n = this.node;
        return (n == null) ? 0 : n.broadcastChat(text);
    }

    /** Sends a meeting invite to one peer. */
    public boolean sendInvite(String fingerprint, String room, String url) {
        P2pNode n = this.node;
        return n != null && n.sendInvite(fingerprint, room, url);
    }

    /** Sends a meeting invite to every connected peer; @return the number reached. */
    public int broadcastInvite(String room, String url) {
        P2pNode n = this.node;
        if (n == null) {
            return 0;
        }
        int sent = 0;
        for (P2pNode.Peer p : n.getPeers()) {
            if (n.sendInvite(p.getFingerprint(), room, url)) {
                sent++;
            }
        }
        return sent;
    }

    /** Offers a file to one peer; false when not running or the offer failed. */
    public boolean offerFile(String fingerprint, Path file) {
        P2pNode n = this.node;
        if (n == null || file == null) {
            return false;
        }
        try {
            return n.offerFile(fingerprint, file) != null;
        } catch (IOException ex) {
            fireError("Could not offer the file: " + ex.getMessage());
            return false;
        }
    }

    /** Accepts an inbound offer by transfer id. */
    public void acceptFile(String transferId) {
        P2pNode n = this.node;
        String fp = transferToPeer.get(transferId);
        if (n != null && fp != null) {
            n.acceptFile(fp, transferId);
        }
    }

    /** Rejects an inbound offer by transfer id. */
    public void rejectFile(String transferId, String reason) {
        P2pNode n = this.node;
        String fp = transferToPeer.get(transferId);
        if (n != null && fp != null) {
            n.rejectFile(fp, transferId, reason);
        }
    }

    /** Stops discovery, closes every peer link and releases the socket. Idempotent. */
    @Override
    public synchronized void close() {
        running.set(false);
        LanDiscovery d = this.discovery;
        this.discovery = null;
        if (d != null) {
            d.close();
        }
        P2pNode n = this.node;
        this.node = null;
        if (n != null) {
            n.close();
        }
        transferToPeer.clear();
    }

    // ------------------------------------------------------------------
    // P2pNode.Listener (fires on the transport I/O thread)
    // ------------------------------------------------------------------

    @Override
    public void onPeerConnected(P2pNode.Peer peer) {
        firePeers();
        fireStatus(displayName(peer) + " connected");
    }

    @Override
    public void onPeerDisconnected(P2pNode.Peer peer, String reason) {
        firePeers();
        fireStatus(displayName(peer) + " left");
    }

    @Override
    public void onChat(P2pNode.Peer peer, P2pMessage message) {
        if (listener != null) {
            listener.onChat(displayName(peer), message.getText(),
                    message.getKind() == P2pMessage.Kind.ACTION);
        }
    }

    @Override
    public void onInvite(P2pNode.Peer peer, String room, String url) {
        if (listener != null) {
            listener.onInvite(displayName(peer), room, url);
        }
    }

    @Override
    public void onFileOffer(P2pNode.Peer peer, FileTransfer transfer) {
        transferToPeer.put(transfer.getId(), peer.getFingerprint());
        if (listener != null) {
            listener.onFileOffer(displayName(peer), transfer);
        }
    }

    @Override
    public void onFileProgress(P2pNode.Peer peer, FileTransfer transfer) {
        if (listener != null) {
            listener.onFileProgress(displayName(peer), transfer);
        }
    }

    @Override
    public void onFileCompleted(P2pNode.Peer peer, FileTransfer transfer) {
        transferToPeer.remove(transfer.getId());
        if (listener != null) {
            listener.onFileComplete(displayName(peer), transfer);
        }
    }

    @Override
    public void onFileCancelled(P2pNode.Peer peer, FileTransfer transfer) {
        transferToPeer.remove(transfer.getId());
        if (listener != null) {
            listener.onFileClosed(displayName(peer), transfer);
        }
    }

    @Override
    public void onFileFailed(P2pNode.Peer peer, FileTransfer transfer) {
        transferToPeer.remove(transfer.getId());
        if (listener != null) {
            listener.onFileClosed(displayName(peer), transfer);
        }
    }

    @Override
    public void onError(String error) {
        fireError(error);
    }

    /**
     * LAN discovery events, kept in a separate adapter so its {@code onError(String)}
     * is never ambiguous with {@link P2pNode.Listener#onError(String)}.
     */
    private final class DiscoveryEvents implements LanDiscovery.Listener {
        @Override
        public void onPeerFound(LanDiscovery.DiscoveredPeer peer) {
            firePeers();
        }

        @Override
        public void onPeerLost(LanDiscovery.DiscoveredPeer peer) {
            firePeers();
        }

        @Override
        public void onError(String error) {
            fireError(error);
        }
    }

    private void firePeers() {
        if (listener != null) {
            listener.onPeersChanged();
        }
    }

    private void fireStatus(String status) {
        if (listener != null) {
            listener.onStatus(status);
        }
    }

    private void fireError(String error) {
        if (listener != null) {
            listener.onError(error);
        }
    }

    /** A short, human label for a peer: its nickname, else a fingerprint tag. */
    public static String displayName(P2pNode.Peer peer) {
        if (peer == null) {
            return "peer";
        }
        String nick = peer.getNickname();
        if (nick != null && !nick.isBlank()) {
            return nick;
        }
        String tag = peer.getFingerprint().replace(":", "");
        return "peer-" + tag.substring(0, Math.min(8, tag.length()));
    }
}
