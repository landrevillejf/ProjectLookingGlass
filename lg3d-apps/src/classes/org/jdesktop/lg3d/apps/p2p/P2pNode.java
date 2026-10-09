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
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One participant in the encrypted peer-to-peer mesh: a long-term identity, an
 * inbound {@link P2pServer}, outbound dialling, and a peer map. A node owns every
 * {@link SecureChannel} it establishes and routes each channel's frames to a
 * per-peer {@link FileTransferManager} and to the node {@link Listener}, so the
 * two apps (Messenger, Video Conference) see peers, chat, presence, invites and
 * file transfers rather than sockets.
 *
 * <p><strong>Identity.</strong> Each node holds a static X25519 {@link KeyPair};
 * the SHA-256 fingerprint of its public key is the node's identity and the key by
 * which peers are tracked. The caller supplies the keypair so persistence stays
 * outside this class (the app store's {@code IdentityStore} owns it at rest).</p>
 *
 * <p><strong>Trust.</strong> A {@link PeerVerifier} hook is consulted with a
 * peer's fingerprint right after the handshake and before it joins the mesh; the
 * default verifier accepts everyone, and the TOFU pinning layer installs a real
 * one. An untrusted peer is closed immediately and never added to the map.</p>
 *
 * <p><strong>Threading.</strong> The constructor is inert (headless-safe: it
 * opens no socket and starts no thread). {@link #start(int)} binds the server;
 * {@link #connect} dials out. Listener callbacks fire on channel reader threads,
 * the server accept threads or file-transfer workers - never the Swing EDT - so
 * callers marshal to the EDT before touching any widget. All threads are daemons.</p>
 *
 * <p>The mesh suits 1:1 and small groups, not large-scale networks; that honest
 * boundary is documented for the UI. Reachability is direct connections plus LAN
 * auto-discovery (see {@code LanDiscovery}); internet peers need port-forwarding
 * or a reachable host - there is no NAT hole-punching here.</p>
 */
public final class P2pNode implements SecureChannel.Listener, AutoCloseable {

    /**
     * The shared handshake prologue. Binding every channel to this context stops a
     * channel established for one purpose being replayed into another, and both
     * sides must agree on it (they do - it is a constant of the transport).
     */
    public static final byte[] PROLOGUE = "lg3d-p2p-v1".getBytes(StandardCharsets.UTF_8);

    /** The presence status a node announces when it establishes or refreshes a link. */
    public static final String STATUS_ONLINE = "online";

    private static final String DEFAULT_NICKNAME = "peer";

    /** Observes node-level events. Every method is a no-op by default. */
    public interface Listener {
        /** A peer joined the mesh (inbound or outbound). */
        default void onPeerConnected(Peer peer) {
        }

        /** A peer left the mesh; {@code reason} is human-readable. */
        default void onPeerDisconnected(Peer peer, String reason) {
        }

        /** A CHAT or ACTION message arrived (see {@link P2pMessage#getKind()}). */
        default void onChat(Peer peer, P2pMessage message) {
        }

        /** A presence/status broadcast arrived. */
        default void onPresence(Peer peer, String status) {
        }

        /** A typing notification arrived. */
        default void onTyping(Peer peer, boolean typing) {
        }

        /** A video-conference invite arrived. */
        default void onInvite(Peer peer, String room, String url) {
        }

        /** A peer offered us a file; call the node's accept/reject to respond. */
        default void onFileOffer(Peer peer, FileTransfer transfer) {
        }

        /** Bytes moved on a transfer. */
        default void onFileProgress(Peer peer, FileTransfer transfer) {
        }

        /** A transfer finished and (for a receive) verified. */
        default void onFileCompleted(Peer peer, FileTransfer transfer) {
        }

        /** A transfer was rejected or cancelled. */
        default void onFileCancelled(Peer peer, FileTransfer transfer) {
        }

        /** A transfer failed (I/O error or checksum mismatch). */
        default void onFileFailed(Peer peer, FileTransfer transfer) {
        }

        /** A recoverable problem worth surfacing (a rejected peer, a bad frame). */
        default void onError(String error) {
        }
    }

    /** Decides whether a peer with a given fingerprint may join the mesh. */
    public interface PeerVerifier {
        /**
         * @param fingerprint the SHA-256 fingerprint of the peer's static key
         * @return true to admit the peer, false to reject and close the link
         */
        boolean isTrusted(String fingerprint);
    }

    private final KeyPair identity;
    private final String fingerprint;
    private final Path downloadDir;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final Map<SecureChannel, Peer> byChannel = new ConcurrentHashMap<>();
    private final Map<String, String> pendingNicknames = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile String nickname;
    private volatile PeerVerifier verifier;
    private volatile P2pServer server;

    /**
     * Creates an inert node. No socket is opened and no thread is started until
     * {@link #start(int)} or {@link #connect}.
     *
     * @param identity    the node's long-term static keypair (never null)
     * @param nickname    our display name (blank falls back to a fingerprint tag)
     * @param downloadDir where received files are written (null falls back to a
     *                    {@code lg3d-p2p} dir under the temporary directory)
     * @param listener    the event sink (may be null)
     */
    public P2pNode(KeyPair identity, String nickname, Path downloadDir, Listener listener) {
        if (identity == null) {
            throw new IllegalArgumentException("identity is required");
        }
        this.identity = identity;
        this.fingerprint = fingerprintOf(identity);
        this.nickname = normalizeNickname(nickname);
        this.downloadDir = (downloadDir == null) ? defaultDownloadDir() : downloadDir;
        this.listener = listener;
    }

    private static String fingerprintOf(KeyPair identity) {
        try {
            return P2pCrypto.fingerprint(identity.getPublic());
        } catch (GeneralSecurityException ex) {
            // SHA-256 is mandated by the platform, so this is unreachable in practice.
            throw new IllegalStateException("Cannot fingerprint the identity key", ex);
        }
    }

    private static Path defaultDownloadDir() {
        return Paths.get(System.getProperty("java.io.tmpdir", "."), "lg3d-p2p");
    }

    private String normalizeNickname(String candidate) {
        if (candidate != null && !candidate.isBlank()) {
            return candidate.trim();
        }
        String tag = fingerprint.replace(":", "");
        return DEFAULT_NICKNAME + "-" + tag.substring(0, Math.min(8, tag.length()));
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Starts listening on an ephemeral port. */
    public void start() throws IOException {
        start(0);
    }

    /**
     * Starts the inbound server. Idempotent: a running node is left untouched.
     *
     * @param port the TCP port to listen on ({@code 0} for an ephemeral port)
     * @throws IOException if the port cannot be bound
     */
    public synchronized void start(int port) throws IOException {
        if (running.get()) {
            return;
        }
        server = new P2pServer(port, this::acceptInbound, this::adoptInbound, this::fireError);
        running.set(true);
    }

    private SecureChannel acceptInbound(Socket socket) throws IOException, GeneralSecurityException {
        return SecureChannel.accept(socket, identity, PROLOGUE, this, random);
    }

    private void adoptInbound(SecureChannel channel) {
        registerChannel(channel, true);
    }

    /** @return true while the inbound server is running. */
    public boolean isRunning() {
        P2pServer s = this.server;
        return running.get() && s != null && s.isRunning();
    }

    /** @return the port this node listens on, or -1 if it is not started. */
    public int getPort() {
        P2pServer s = this.server;
        return (s == null) ? -1 : s.getPort();
    }

    /**
     * Dials a peer and adds it to the mesh on success.
     *
     * @param host the peer host
     * @param port the peer port
     * @return the new {@link Peer}, or null if the peer was rejected as untrusted
     * @throws IOException              if the connection cannot be made
     * @throws GeneralSecurityException if the handshake fails
     */
    public Peer connect(String host, int port) throws IOException, GeneralSecurityException {
        SecureChannel channel = SecureChannel.connect(host, port, identity, PROLOGUE, this, random);
        return registerChannel(channel, false);
    }

    /**
     * Adopts an established channel: verifies trust, builds the peer and its
     * file-transfer manager, replaces any stale peer with the same identity, and
     * announces our presence so the peer learns our nickname.
     */
    private Peer registerChannel(SecureChannel channel, boolean inbound) {
        String peerFingerprint = channel.getRemoteFingerprint();
        if (peerFingerprint == null || peerFingerprint.isBlank()) {
            channel.close("Peer presented no identity");
            fireError("Rejected a peer that presented no identity");
            return null;
        }
        PeerVerifier v = this.verifier;
        if (v != null && !v.isTrusted(peerFingerprint)) {
            pendingNicknames.remove(peerFingerprint);
            channel.close("Peer not trusted");
            fireError("Rejected untrusted peer " + peerFingerprint);
            return null;
        }
        Peer peer = new Peer(channel, peerFingerprint, inbound);
        peer.setFileTransferManager(
                new FileTransferManager(channel, nickname, downloadDir, new PeerFiles(peer)));
        byChannel.put(channel, peer);
        Peer old = peers.put(peerFingerprint, peer);
        if (old != null && old.getChannel() != channel) {
            byChannel.remove(old.getChannel());
            FileTransferManager oldFtm = old.getFileTransferManager();
            if (oldFtm != null) {
                oldFtm.close();
            }
            old.getChannel().close("Superseded by a new connection");
        }
        // A one-shot PRESENCE frame can reach the reader thread before this peer is
        // registered (above), so its nickname is buffered by fingerprint and applied
        // here rather than lost. Draining after byChannel.put means any frame that
        // buffered before this point is captured; a frame that buffers after it is
        // caught by the re-check in onControl.
        String pending = pendingNicknames.remove(peerFingerprint);
        if (pending != null && !pending.isBlank()
                && (peer.getNickname() == null || peer.getNickname().isBlank())) {
            peer.setNickname(pending);
        }
        channel.sendControl(P2pMessage.presence(nickname, STATUS_ONLINE, fingerprint));
        firePeerConnected(peer);
        // The link can die while we are registering it: onClosed then reaped
        // nothing (byChannel.put above had not run yet) and the dead peer would
        // leak in the mesh forever. Re-check once after registration and reap
        // here; a close racing after this check is reaped by onClosed itself.
        if (!channel.isOpen()) {
            onClosed(channel, "Link closed during registration");
        }
        return peer;
    }

    /** Removes one peer from the mesh and closes its link. */
    public void disconnect(String peerFingerprint) {
        Peer peer = (peerFingerprint == null) ? null : peers.get(peerFingerprint);
        if (peer != null) {
            peer.getChannel().sendControl(P2pMessage.bye("Disconnected"));
            peer.getChannel().close("Disconnected locally");
        }
    }

    /** Stops the server and closes every peer link. Idempotent and never throws. */
    @Override
    public void close() {
        running.set(false);
        P2pServer s = this.server;
        if (s != null) {
            s.close();
            this.server = null;
        }
        for (Peer peer : new ArrayList<>(peers.values())) {
            FileTransferManager ftm = peer.getFileTransferManager();
            if (ftm != null) {
                ftm.close();
            }
            peer.getChannel().sendControl(P2pMessage.bye("Node shutting down"));
            peer.getChannel().close("Node shutting down");
        }
        peers.clear();
        byChannel.clear();
        pendingNicknames.clear();
    }

    // ------------------------------------------------------------------
    // Identity and configuration
    // ------------------------------------------------------------------

    /** @return the node's long-term static keypair. */
    public KeyPair getIdentity() {
        return identity;
    }

    /** @return the SHA-256 fingerprint of our static key - our identity. */
    public String getFingerprint() {
        return fingerprint;
    }

    /** @return our display name. */
    public String getNickname() {
        return nickname;
    }

    /**
     * Changes our display name and re-announces presence to every connected peer.
     *
     * @param nickname the new name (blank falls back to a fingerprint tag)
     */
    public void setNickname(String nickname) {
        this.nickname = normalizeNickname(nickname);
        for (Peer peer : peers.values()) {
            peer.getChannel().sendControl(P2pMessage.presence(this.nickname, STATUS_ONLINE, fingerprint));
        }
    }

    /** @return the directory received files are written to. */
    public Path getDownloadDir() {
        return downloadDir;
    }

    /**
     * Installs the trust hook consulted before a peer joins the mesh. A null
     * verifier restores the default accept-everyone behaviour.
     *
     * @param verifier the verifier (may be null)
     */
    public void setPeerVerifier(PeerVerifier verifier) {
        this.verifier = verifier;
    }

    // ------------------------------------------------------------------
    // Peer map
    // ------------------------------------------------------------------

    /** @return the peer with this fingerprint, or null. */
    public Peer getPeer(String peerFingerprint) {
        return (peerFingerprint == null) ? null : peers.get(peerFingerprint);
    }

    /** @return a snapshot of every connected peer. */
    public List<Peer> getPeers() {
        return new ArrayList<>(peers.values());
    }

    /** @return the number of connected peers. */
    public int getPeerCount() {
        return peers.size();
    }

    private Peer peerFor(SecureChannel channel) {
        Peer peer = byChannel.get(channel);
        if (peer == null) {
            // A frame can arrive before registration completes; fall back to the
            // channel's own view of the remote identity.
            peer = peers.get(channel.getRemoteFingerprint());
        }
        return peer;
    }

    // ------------------------------------------------------------------
    // Messaging
    // ------------------------------------------------------------------

    /** Sends a chat message to one peer. @return true if the peer is connected. */
    public boolean sendChat(String peerFingerprint, String text) {
        return sendControl(peerFingerprint, P2pMessage.chat(nickname, text));
    }

    /** Sends a {@code /me} action to one peer. @return true if the peer is connected. */
    public boolean sendAction(String peerFingerprint, String text) {
        return sendControl(peerFingerprint, P2pMessage.action(nickname, text));
    }

    /** Sends a typing notification to one peer. @return true if the peer is connected. */
    public boolean sendTyping(String peerFingerprint, boolean typing) {
        return sendControl(peerFingerprint, P2pMessage.typing(nickname, typing));
    }

    /** Sends a video-conference invite to one peer. @return true if connected. */
    public boolean sendInvite(String peerFingerprint, String room, String url) {
        return sendControl(peerFingerprint, P2pMessage.invite(nickname, room, url));
    }

    /** Fans a chat message out to every connected peer. @return the peers reached. */
    public int broadcastChat(String text) {
        P2pMessage message = P2pMessage.chat(nickname, text);
        int sent = 0;
        for (Peer peer : peers.values()) {
            if (peer.getChannel().isOpen()) {
                peer.getChannel().sendControl(message);
                sent++;
            }
        }
        return sent;
    }

    private boolean sendControl(String peerFingerprint, P2pMessage message) {
        Peer peer = getPeer(peerFingerprint);
        if (peer == null || !peer.getChannel().isOpen()) {
            return false;
        }
        peer.getChannel().sendControl(message);
        return true;
    }

    // ------------------------------------------------------------------
    // File transfer
    // ------------------------------------------------------------------

    /**
     * Offers a local file to one peer.
     *
     * @param peerFingerprint the destination peer
     * @param file            the file to send
     * @return the pending transfer, or null if the peer is not connected
     * @throws IOException if the file is missing/unreadable or cannot be hashed
     */
    public FileTransfer offerFile(String peerFingerprint, Path file) throws IOException {
        Peer peer = getPeer(peerFingerprint);
        FileTransferManager ftm = (peer == null) ? null : peer.getFileTransferManager();
        if (ftm == null) {
            return null;
        }
        return ftm.offerFile(file);
    }

    /** Accepts an offered file from a peer. */
    public void acceptFile(String peerFingerprint, String transferId) {
        FileTransferManager ftm = managerFor(peerFingerprint);
        if (ftm != null) {
            ftm.accept(transferId);
        }
    }

    /** Rejects an offered file from a peer. */
    public void rejectFile(String peerFingerprint, String transferId, String reason) {
        FileTransferManager ftm = managerFor(peerFingerprint);
        if (ftm != null) {
            ftm.reject(transferId, reason);
        }
    }

    /** Cancels an in-flight transfer with a peer. */
    public void cancelFile(String peerFingerprint, String transferId, String reason) {
        FileTransferManager ftm = managerFor(peerFingerprint);
        if (ftm != null) {
            ftm.cancel(transferId, reason);
        }
    }

    private FileTransferManager managerFor(String peerFingerprint) {
        Peer peer = getPeer(peerFingerprint);
        return (peer == null) ? null : peer.getFileTransferManager();
    }

    // ------------------------------------------------------------------
    // SecureChannel.Listener: route every channel's frames to its peer
    // ------------------------------------------------------------------

    @Override
    public void onControl(SecureChannel channel, P2pMessage message) {
        if (message == null || message.getKind() == null) {
            return;
        }
        Peer peer = peerFor(channel);
        String from = message.getFrom();
        if (peer != null) {
            if (from != null && !from.isBlank()
                    && (peer.getNickname() == null || peer.getNickname().isBlank())) {
                peer.setNickname(from);
            }
        } else if (from != null && !from.isBlank()) {
            // The frame beat registration: remember the name against the remote
            // identity so registerChannel can apply it once the peer exists.
            String remoteFingerprint = channel.getRemoteFingerprint();
            if (remoteFingerprint != null && !remoteFingerprint.isBlank()) {
                pendingNicknames.put(remoteFingerprint, from);
                // Re-check: registration may have completed while we buffered, in
                // which case the drain there already ran and we apply it here.
                Peer registered = peerFor(channel);
                if (registered != null
                        && (registered.getNickname() == null || registered.getNickname().isBlank())) {
                    String buffered = pendingNicknames.remove(remoteFingerprint);
                    if (buffered != null && !buffered.isBlank()) {
                        registered.setNickname(buffered);
                    }
                }
            }
        }
        switch (message.getKind()) {
            case PRESENCE -> {
                if (peer != null && message.getFrom() != null && !message.getFrom().isBlank()) {
                    peer.setNickname(message.getFrom());
                }
                firePresence(peer, message.getText());
            }
            case CHAT, ACTION -> fireChat(peer, message);
            case TYPING -> fireTyping(peer, message.isTyping());
            case INVITE -> fireInvite(peer, message.getRoom(), message.getUrl());
            case BYE -> channel.close("Peer said goodbye: "
                    + (message.getText() == null ? "" : message.getText()));
            case FILE_OFFER, FILE_ACCEPT, FILE_REJECT, FILE_PROGRESS, FILE_END, FILE_CANCEL -> {
                if (peer != null && peer.getFileTransferManager() != null) {
                    peer.getFileTransferManager().handleControl(message);
                }
            }
            default -> {
                // PING/PONG are consumed by the channel; nothing else is routed.
            }
        }
    }

    @Override
    public void onData(SecureChannel channel, byte[] payload) {
        Peer peer = peerFor(channel);
        if (peer != null && peer.getFileTransferManager() != null) {
            peer.getFileTransferManager().handleData(payload);
        }
    }

    @Override
    public void onClosed(SecureChannel channel, String reason) {
        Peer peer = byChannel.remove(channel);
        if (peer == null) {
            return;
        }
        peers.remove(peer.getFingerprint(), peer);
        pendingNicknames.remove(peer.getFingerprint());
        FileTransferManager ftm = peer.getFileTransferManager();
        if (ftm != null) {
            ftm.close();
        }
        firePeerDisconnected(peer, reason);
    }

    @Override
    public void onError(SecureChannel channel, String error) {
        fireError(error);
    }

    // ------------------------------------------------------------------
    // Per-peer file-transfer listener: tags events with the owning peer
    // ------------------------------------------------------------------

    private final class PeerFiles implements FileTransferManager.Listener {
        private final Peer peer;

        PeerFiles(Peer peer) {
            this.peer = peer;
        }

        @Override
        public void onOffer(FileTransfer transfer) {
            if (listener != null) {
                listener.onFileOffer(peer, transfer);
            }
        }

        @Override
        public void onProgress(FileTransfer transfer) {
            if (listener != null) {
                listener.onFileProgress(peer, transfer);
            }
        }

        @Override
        public void onCompleted(FileTransfer transfer) {
            if (listener != null) {
                listener.onFileCompleted(peer, transfer);
            }
        }

        @Override
        public void onCancelled(FileTransfer transfer) {
            if (listener != null) {
                listener.onFileCancelled(peer, transfer);
            }
        }

        @Override
        public void onFailed(FileTransfer transfer) {
            if (listener != null) {
                listener.onFileFailed(peer, transfer);
            }
        }
    }

    // ------------------------------------------------------------------
    // Listener fan-out (null-safe)
    // ------------------------------------------------------------------

    private void firePeerConnected(Peer peer) {
        if (listener != null) {
            listener.onPeerConnected(peer);
        }
    }

    private void firePeerDisconnected(Peer peer, String reason) {
        if (listener != null) {
            listener.onPeerDisconnected(peer, reason);
        }
    }

    private void fireChat(Peer peer, P2pMessage message) {
        if (listener != null) {
            listener.onChat(peer, message);
        }
    }

    private void firePresence(Peer peer, String status) {
        if (listener != null) {
            listener.onPresence(peer, status);
        }
    }

    private void fireTyping(Peer peer, boolean typing) {
        if (listener != null) {
            listener.onTyping(peer, typing);
        }
    }

    private void fireInvite(Peer peer, String room, String url) {
        if (listener != null) {
            listener.onInvite(peer, room, url);
        }
    }

    private void fireError(String error) {
        if (listener != null) {
            listener.onError(error);
        }
    }

    // ------------------------------------------------------------------
    // Peer
    // ------------------------------------------------------------------

    /**
     * One connected peer: its identity fingerprint, learned nickname, the live
     * {@link SecureChannel} and the {@link FileTransferManager} bound to it. Peers
     * are created and owned by the {@link P2pNode}; the nickname may be updated as
     * presence arrives. Instances are handed to the node {@link Listener}.
     */
    public static final class Peer {
        private final SecureChannel channel;
        private final String fingerprint;
        private final boolean inbound;
        private final long connectedAtMs = System.currentTimeMillis();
        private volatile String nickname;
        private volatile FileTransferManager fileTransferManager;

        Peer(SecureChannel channel, String fingerprint, boolean inbound) {
            this.channel = channel;
            this.fingerprint = fingerprint;
            this.inbound = inbound;
        }

        /** @return the SHA-256 fingerprint of the peer's static key - its identity. */
        public String getFingerprint() {
            return fingerprint;
        }

        /** @return the peer's display name, learned from presence/chat (may be blank). */
        public String getNickname() {
            return nickname;
        }

        void setNickname(String nickname) {
            this.nickname = nickname;
        }

        /** @return a {@code host:port} description of the peer, for display. */
        public String getAddress() {
            return channel.getRemoteAddress();
        }

        /** @return the live channel to this peer. */
        public SecureChannel getChannel() {
            return channel;
        }

        /** @return the file-transfer manager bound to this peer's channel. */
        public FileTransferManager getFileTransferManager() {
            return fileTransferManager;
        }

        void setFileTransferManager(FileTransferManager fileTransferManager) {
            this.fileTransferManager = fileTransferManager;
        }

        /** @return true if we dialled out to this peer (false if it dialled in). */
        public boolean isInbound() {
            return inbound;
        }

        /** @return true while the underlying channel is open. */
        public boolean isConnected() {
            return channel.isOpen();
        }

        /** @return when this peer joined the mesh, in epoch milliseconds. */
        public long getConnectedAtMs() {
            return connectedAtMs;
        }

        @Override
        public String toString() {
            String name = (nickname == null || nickname.isBlank()) ? "peer" : nickname;
            return "Peer[" + name + " " + fingerprint + (inbound ? " inbound" : " outbound") + "]";
        }
    }
}
