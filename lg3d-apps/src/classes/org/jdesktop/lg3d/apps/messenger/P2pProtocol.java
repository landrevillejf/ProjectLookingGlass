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
package org.jdesktop.lg3d.apps.messenger;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jdesktop.lg3d.apps.p2p.FileTransfer;
import org.jdesktop.lg3d.apps.p2p.IdentityStore;
import org.jdesktop.lg3d.apps.p2p.LanDiscovery;
import org.jdesktop.lg3d.apps.p2p.P2pMessage;
import org.jdesktop.lg3d.apps.p2p.P2pNode;
import org.jdesktop.lg3d.apps.p2p.TrustDecision;

/**
 * The native, in-process <b>peer-to-peer</b> backend: encrypted direct chat,
 * presence and file transfer between LG3D nodes with no server in between, built
 * on the reusable {@code org.jdesktop.lg3d.apps.p2p} transport (X25519 + a
 * Noise-XX-style handshake + AES-256-GCM, forward-secret and mutually
 * authenticated). It is the second fully native backend alongside
 * {@link IrcProtocol}; the bridge backends still hand off to external clients.
 *
 * <p><strong>Model.</strong> A connection wraps one {@link P2pNode}: it listens for
 * inbound peers, optionally dials the account's {@code host:port}, announces and
 * watches for peers on the LAN, and routes each peer's frames into the Messenger's
 * protocol-neutral {@link ChatMessage}/{@link FileTransferEvent} events. There are
 * no channels; every peer is its own conversation, keyed by a display name derived
 * from its nickname or identity fingerprint. {@link #sendMessage} accepts either a
 * fingerprint or a display name as the target.</p>
 *
 * <p><strong>Trust.</strong> Identity is trust-on-first-use. An account with a
 * pinned {@link AccountConfig#getPeerFingerprint()} accepts <em>only</em> that
 * identity and refuses any other with a loud man-in-the-middle warning; an open
 * account pins each new peer on first contact and records it in the
 * {@link IdentityStore}. A discovered peer is only a hint to connect - its identity
 * is always verified by the handshake, never trusted from the (spoofable)
 * announcement.</p>
 *
 * <p><strong>Threading and headless-safety.</strong> The constructor is inert: it
 * opens no socket and starts no thread. {@link #connect} does its work on a daemon
 * thread and reports through the {@link ProtocolListener}; every listener callback
 * fires on a transport thread, never the Swing EDT, so the panel marshals to the
 * EDT itself. LAN discovery degrades gracefully (it never throws) when multicast is
 * unavailable, leaving manual {@code host:port} dialling.</p>
 *
 * <p><strong>Honest scope.</strong> The mesh suits 1:1 and small groups, not
 * large-scale networks. LAN discovery is subnet-scoped; reaching a peer across the
 * internet needs port-forwarding or a reachable host - there is no NAT
 * hole-punching here.</p>
 */
public class P2pProtocol implements MessengerProtocol, P2pNode.Listener {

    /** The pseudo-channel under which the connected-peer roster is reported. */
    public static final String PEER_ROSTER = "#peers";

    private final IdentityStore identityStore;
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final Map<String, String> transferToPeer = new ConcurrentHashMap<>();

    private volatile Path downloadDir;
    private volatile int listenPort;
    private volatile boolean discoveryEnabled = true;

    private volatile AccountConfig account;
    private volatile ProtocolListener listener;
    private volatile P2pNode node;
    private volatile LanDiscovery discovery;
    private volatile KeyPair identity;
    private volatile boolean connected;

    /** Creates a backend that persists identity and pins under the default store. */
    public P2pProtocol() {
        this(new IdentityStore());
    }

    /**
     * Creates a backend with an explicit identity store (tests point it at a
     * temporary directory).
     *
     * @param identityStore the identity/TOFU store (never null)
     */
    public P2pProtocol(IdentityStore identityStore) {
        this(identityStore, null);
    }

    /**
     * Creates a backend with an explicit identity store and download directory.
     *
     * @param identityStore the identity/TOFU store (never null)
     * @param downloadDir   where received files are written (null uses the node's
     *                      default under the temporary directory)
     */
    public P2pProtocol(IdentityStore identityStore, Path downloadDir) {
        if (identityStore == null) {
            throw new IllegalArgumentException("identityStore is required");
        }
        this.identityStore = identityStore;
        this.downloadDir = downloadDir;
    }

    // ------------------------------------------------------------------
    // MessengerProtocol identity
    // ------------------------------------------------------------------

    @Override public String id() { return "p2p"; }
    @Override public String displayName() { return "P2P (Direct)"; }

    @Override
    public String description() {
        return "Encrypted peer-to-peer chat and file transfer (X25519 + AES-256-GCM), "
                + "no server; direct or LAN-discovered peers.";
    }

    @Override public boolean isNative() { return true; }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHAT, Capability.PRESENCE, Capability.ACTIONS,
                Capability.TLS, Capability.NATIVE, Capability.FILE_TRANSFER);
    }

    // ------------------------------------------------------------------
    // Configuration (before connect)
    // ------------------------------------------------------------------

    /**
     * Sets the fixed TCP port to listen on. {@code 0} (the default) binds an
     * ephemeral port; a fixed port helps when port-forwarding for internet peers.
     *
     * @param port the listen port ({@code 0} for ephemeral)
     */
    public void setListenPort(int port) {
        this.listenPort = Math.max(0, port);
    }

    /** @return the configured listen port ({@code 0} for ephemeral). */
    public int getListenPort() {
        P2pNode n = this.node;
        return (n != null && n.isRunning()) ? n.getPort() : listenPort;
    }

    /** Sets where received files are written (null uses the node default). */
    public void setDownloadDir(Path downloadDir) {
        this.downloadDir = downloadDir;
    }

    /** @return the configured download directory (may be null). */
    public Path getDownloadDir() {
        P2pNode n = this.node;
        return (n != null) ? n.getDownloadDir() : downloadDir;
    }

    /** Enables or disables LAN auto-discovery (on by default). */
    public void setDiscoveryEnabled(boolean discoveryEnabled) {
        this.discoveryEnabled = discoveryEnabled;
    }

    /** @return true if LAN auto-discovery is enabled. */
    public boolean isDiscoveryEnabled() {
        return discoveryEnabled;
    }

    /** @return the identity/TOFU store (for pin display and management). */
    public IdentityStore getIdentityStore() {
        return identityStore;
    }

    /** @return the underlying node once connected, else null. */
    public P2pNode getNode() {
        return node;
    }

    /**
     * Our own identity fingerprint, loading (and if necessary creating) the
     * long-term identity on first use.
     *
     * @return the SHA-256 fingerprint of our static public key
     */
    public String getOurFingerprint() {
        P2pNode n = this.node;
        if (n != null) {
            return n.getFingerprint();
        }
        return identityStore.getOurFingerprint();
    }

    /** @return a snapshot of the connected peers. */
    public List<P2pNode.Peer> getPeers() {
        P2pNode n = this.node;
        return (n == null) ? List.of() : n.getPeers();
    }

    /** @return a snapshot of the peers currently discovered on the LAN. */
    public List<LanDiscovery.DiscoveredPeer> getDiscoveredPeers() {
        LanDiscovery d = this.discovery;
        return (d == null) ? List.of() : d.getPeers();
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void connect(AccountConfig account, ProtocolListener listener) {
        if (!connecting.compareAndSet(false, true)) {
            return; // already connecting or connected
        }
        this.account = account;
        this.listener = listener;
        this.connected = false;
        String host = (account == null) ? "" : account.getHost();
        Thread t = new Thread(this::runConnect, "p2p-connect-" + host);
        t.setDaemon(true);
        t.start();
    }

    private void runConnect() {
        AccountConfig acc = this.account;
        try {
            KeyPair id = identityStore.loadOrCreateIdentity();
            this.identity = id;
            String nick = (acc == null || acc.getNickname().isBlank()) ? null : acc.getNickname();
            P2pNode n = new P2pNode(id, nick, downloadDir, this);
            n.setPeerVerifier(this::isTrusted);
            this.node = n;
            n.start(Math.max(0, listenPort));
            this.connected = true;
            fireConnected();
            fireStatus("P2P node listening on port " + n.getPort());
            fireStatus("Our identity fingerprint: " + n.getFingerprint());
            startDiscovery(n);
            String host = (acc == null) ? "" : acc.getHost();
            if (host != null && !host.isBlank()) {
                dialPeer(host, acc.getEffectivePort());
            }
        } catch (IOException | RuntimeException ex) {
            this.connected = false;
            connecting.set(false);
            String msg = (ex.getMessage() == null) ? ex.toString() : ex.getMessage();
            fireError("P2P connection failed: " + msg);
            fireDisconnected("Connection failed: " + msg);
        }
    }

    private void startDiscovery(P2pNode n) {
        if (!discoveryEnabled) {
            return;
        }
        LanDiscovery d = new LanDiscovery(n.getFingerprint(), n.getNickname(), n::getPort,
                new DiscoveryEvents());
        this.discovery = d;
        if (d.start()) {
            fireStatus("LAN discovery active: peers on this subnet appear automatically.");
        }
        // start() reports and swallows its own errors when multicast is unavailable.
    }

    /**
     * Dials a peer on a daemon thread so the caller (often the EDT) is never
     * blocked by a slow or unreachable host.
     *
     * @param host the peer host
     * @param port the peer port
     */
    public void connectToPeer(String host, int port) {
        if (host == null || host.isBlank()) {
            return;
        }
        Thread t = new Thread(() -> dialPeer(host, port), "p2p-dial-" + host);
        t.setDaemon(true);
        t.start();
    }

    private void dialPeer(String host, int port) {
        P2pNode n = this.node;
        if (n == null) {
            fireError("Not connected; cannot dial " + host + ":" + port);
            return;
        }
        try {
            P2pNode.Peer peer = n.connect(host, port);
            if (peer == null) {
                fireError("Refused " + host + ":" + port + " (identity not trusted)");
            } else {
                fireStatus("Connected to " + host + ":" + port);
            }
        } catch (IOException | GeneralSecurityException ex) {
            String msg = (ex.getMessage() == null) ? ex.toString() : ex.getMessage();
            fireError("Could not reach " + host + ":" + port + ": " + msg);
        }
    }

    @Override
    public boolean isConnected() {
        P2pNode n = this.node;
        return connected && n != null && n.isRunning();
    }

    @Override
    public void disconnect() {
        boolean wasConnected = connecting.getAndSet(false);
        connected = false;
        LanDiscovery d = this.discovery;
        if (d != null) {
            d.close();
            this.discovery = null;
        }
        P2pNode n = this.node;
        if (n != null) {
            n.close();
            this.node = null;
        }
        transferToPeer.clear();
        if (wasConnected) {
            fireDisconnected("Disconnected");
        }
    }

    // ------------------------------------------------------------------
    // Trust (TOFU)
    // ------------------------------------------------------------------

    /**
     * The {@link P2pNode.PeerVerifier} hook: decides whether a freshly-handshaked
     * peer may join the mesh. A pinned account accepts only its pinned identity;
     * an open account pins on first use.
     */
    private boolean isTrusted(String fingerprint) {
        AccountConfig acc = this.account;
        String pinned = (acc == null) ? null : acc.getPeerFingerprint();
        if (pinned != null && !pinned.isBlank()) {
            TrustDecision decision = TrustDecision.evaluate(pinned, fingerprint);
            if (decision == TrustDecision.MATCH) {
                return true;
            }
            fireError("SECURITY WARNING: this account is pinned to " + pinned
                    + " but the peer presented " + fingerprint
                    + ". This could be a man-in-the-middle attack, so the connection was"
                    + " refused. If the peer legitimately changed keys, re-pin it first.");
            return false;
        }
        TrustDecision decision = identityStore.verifyAndRecord(fingerprint, fingerprint, null);
        if (decision == TrustDecision.FIRST_CONTACT) {
            fireStatus("Pinned a new peer on first use: " + fingerprint
                    + ". Compare this fingerprint out-of-band to be sure who it is.");
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Outgoing
    // ------------------------------------------------------------------

    @Override
    public void sendMessage(String target, String text) {
        P2pNode n = this.node;
        if (n == null || text == null) {
            return;
        }
        String fp = resolvePeerFingerprint(target);
        if (fp == null) {
            fireError("No such peer: " + target);
            return;
        }
        if (!n.sendChat(fp, text)) {
            fireError("Peer not connected: " + target);
        }
    }

    @Override
    public void sendAction(String target, String text) {
        P2pNode n = this.node;
        if (n == null || text == null) {
            return;
        }
        String fp = resolvePeerFingerprint(target);
        if (fp == null) {
            fireError("No such peer: " + target);
            return;
        }
        if (!n.sendAction(fp, text)) {
            fireError("Peer not connected: " + target);
        }
    }

    @Override
    public boolean sendFile(String target, Path file) {
        P2pNode n = this.node;
        if (n == null || file == null) {
            return false;
        }
        String fp = resolvePeerFingerprint(target);
        if (fp == null) {
            fireError("No such peer: " + target);
            return false;
        }
        try {
            FileTransfer transfer = n.offerFile(fp, file);
            if (transfer == null) {
                fireError("Peer not connected: " + target);
                return false;
            }
            transferToPeer.put(transfer.getId(), fp);
            fireFileTransfer(n.getPeer(fp), transfer); // surface our own OFFERED state
            return true;
        } catch (IOException ex) {
            fireError("Could not offer file: " + ex.getMessage());
            return false;
        }
    }

    @Override
    public void acceptFile(String transferId) {
        P2pNode n = this.node;
        String fp = (transferId == null) ? null : transferToPeer.get(transferId);
        if (n != null && fp != null) {
            n.acceptFile(fp, transferId);
        }
    }

    @Override
    public void rejectFile(String transferId, String reason) {
        P2pNode n = this.node;
        String fp = (transferId == null) ? null : transferToPeer.get(transferId);
        if (n != null && fp != null) {
            n.rejectFile(fp, transferId, reason);
        }
    }

    @Override
    public void cancelFile(String transferId, String reason) {
        P2pNode n = this.node;
        String fp = (transferId == null) ? null : transferToPeer.get(transferId);
        if (n != null && fp != null) {
            n.cancelFile(fp, transferId, reason);
        }
    }

    /**
     * Resolves a user-facing target (a full fingerprint, a colon-free fingerprint
     * or prefix, or a peer display name) to a connected peer's fingerprint.
     *
     * @param target the target as typed/known by the UI
     * @return the peer fingerprint, or null if no connected peer matches
     */
    private String resolvePeerFingerprint(String target) {
        P2pNode n = this.node;
        if (n == null || target == null || target.isBlank()) {
            return null;
        }
        if (n.getPeer(target) != null) {
            return target;
        }
        String t = target.trim();
        for (P2pNode.Peer p : n.getPeers()) {
            if (t.equals(p.getFingerprint()) || t.equalsIgnoreCase(displayName(p))
                    || (p.getNickname() != null && t.equalsIgnoreCase(p.getNickname()))) {
                return p.getFingerprint();
            }
        }
        String canon = canonical(t);
        if (!canon.isEmpty()) {
            for (P2pNode.Peer p : n.getPeers()) {
                String fp = canonical(p.getFingerprint());
                if (fp.equals(canon) || fp.startsWith(canon)) {
                    return p.getFingerprint();
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // P2pNode.Listener: map transport events to protocol-neutral events
    // ------------------------------------------------------------------

    @Override
    public void onPeerConnected(P2pNode.Peer peer) {
        String display = displayName(peer);
        fireMessage(new ChatMessage(display, display,
                "Peer connected: " + display + " (" + peer.getFingerprint() + ")",
                ChatMessage.Kind.JOIN));
        fireRoster();
    }

    @Override
    public void onPeerDisconnected(P2pNode.Peer peer, String reason) {
        String display = displayName(peer);
        fireMessage(new ChatMessage(display, display,
                (reason == null || reason.isBlank()) ? "Peer disconnected" : reason,
                ChatMessage.Kind.QUIT));
        fireRoster();
    }

    @Override
    public void onChat(P2pNode.Peer peer, P2pMessage message) {
        String display = displayName(peer);
        ChatMessage.Kind kind = (message.getKind() == P2pMessage.Kind.ACTION)
                ? ChatMessage.Kind.ACTION : ChatMessage.Kind.PRIVMSG;
        fireMessage(new ChatMessage(display, display, message.getText(), kind));
    }

    @Override
    public void onPresence(P2pNode.Peer peer, String status) {
        // A presence broadcast may have carried a fresh nickname; refresh the roster.
        fireRoster();
    }

    @Override
    public void onInvite(P2pNode.Peer peer, String room, String url) {
        String display = displayName(peer);
        fireMessage(new ChatMessage(display, display,
                "Video-conference invite for room \"" + room + "\": " + url,
                ChatMessage.Kind.NOTICE));
    }

    @Override
    public void onFileOffer(P2pNode.Peer peer, FileTransfer transfer) {
        recordTransfer(peer, transfer);
        fireFileTransfer(peer, transfer);
    }

    @Override
    public void onFileProgress(P2pNode.Peer peer, FileTransfer transfer) {
        recordTransfer(peer, transfer);
        fireFileTransfer(peer, transfer);
    }

    @Override
    public void onFileCompleted(P2pNode.Peer peer, FileTransfer transfer) {
        recordTransfer(peer, transfer);
        fireFileTransfer(peer, transfer);
    }

    @Override
    public void onFileCancelled(P2pNode.Peer peer, FileTransfer transfer) {
        fireFileTransfer(peer, transfer);
        forgetTransfer(transfer);
    }

    @Override
    public void onFileFailed(P2pNode.Peer peer, FileTransfer transfer) {
        fireFileTransfer(peer, transfer);
        forgetTransfer(transfer);
    }

    @Override
    public void onError(String error) {
        fireError(error);
    }

    private void recordTransfer(P2pNode.Peer peer, FileTransfer transfer) {
        if (peer != null && transfer != null) {
            transferToPeer.put(transfer.getId(), peer.getFingerprint());
        }
    }

    private void forgetTransfer(FileTransfer transfer) {
        if (transfer != null) {
            transferToPeer.remove(transfer.getId());
        }
    }

    /** LAN discovery events, kept in an inner class so {@code onError} is unambiguous. */
    private final class DiscoveryEvents implements LanDiscovery.Listener {
        @Override
        public void onPeerFound(LanDiscovery.DiscoveredPeer peer) {
            String name = (peer.getNickname() == null || peer.getNickname().isBlank())
                    ? "peer" : peer.getNickname();
            fireStatus("Discovered peer on the LAN: " + name + " at "
                    + peer.getHostAddress() + ":" + peer.getPort());
        }

        @Override
        public void onPeerLost(LanDiscovery.DiscoveredPeer peer) {
            fireStatus("A discovered peer left the LAN: " + peer.getHostAddress());
        }

        @Override
        public void onError(String error) {
            fireError(error);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The display name for a peer: its nickname, else a short fingerprint tag. */
    static String displayName(P2pNode.Peer peer) {
        if (peer == null) {
            return "peer";
        }
        String nick = peer.getNickname();
        if (nick != null && !nick.isBlank()) {
            return nick;
        }
        String fp = canonical(peer.getFingerprint());
        return "peer-" + fp.substring(0, Math.min(8, fp.length()));
    }

    /** Lower-cases a fingerprint and strips everything that is not a hex digit. */
    private static String canonical(String fingerprint) {
        if (fingerprint == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(fingerprint.length());
        for (int i = 0; i < fingerprint.length(); i++) {
            char c = Character.toLowerCase(fingerprint.charAt(i));
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private void fireFileTransfer(P2pNode.Peer peer, FileTransfer t) {
        ProtocolListener l = this.listener;
        if (l == null || t == null) {
            return;
        }
        FileTransferEvent event = new FileTransferEvent(
                t.getId(), displayName(peer), t.getFileName(), t.getFileSize(),
                t.getBytesTransferred(), mapDirection(t.getDirection()), mapState(t.getState()),
                (t.getLocalPath() == null) ? null : t.getLocalPath().toString(),
                t.getMessage(), t.isVerified());
        l.onFileTransfer(account, event);
    }

    private static FileTransferEvent.Direction mapDirection(FileTransfer.Direction d) {
        return (d == FileTransfer.Direction.SEND)
                ? FileTransferEvent.Direction.SEND : FileTransferEvent.Direction.RECEIVE;
    }

    private static FileTransferEvent.State mapState(FileTransfer.State s) {
        return switch (s) {
            case OFFERED -> FileTransferEvent.State.OFFERED;
            case ACCEPTED -> FileTransferEvent.State.ACCEPTED;
            case IN_PROGRESS -> FileTransferEvent.State.IN_PROGRESS;
            case COMPLETED -> FileTransferEvent.State.COMPLETED;
            case REJECTED -> FileTransferEvent.State.REJECTED;
            case CANCELLED -> FileTransferEvent.State.CANCELLED;
            case FAILED -> FileTransferEvent.State.FAILED;
        };
    }

    // ------------------------------------------------------------------
    // Listener fan-out (null-safe)
    // ------------------------------------------------------------------

    private void fireMessage(ChatMessage msg) {
        ProtocolListener l = this.listener;
        if (l != null) {
            l.onMessage(account, msg);
        }
    }

    private void fireRoster() {
        ProtocolListener l = this.listener;
        if (l == null) {
            return;
        }
        List<String> members = new ArrayList<>();
        P2pNode n = this.node;
        if (n != null) {
            for (P2pNode.Peer p : n.getPeers()) {
                members.add(displayName(p));
            }
        }
        l.onRosterUpdate(account, PEER_ROSTER, members);
    }

    private void fireConnected() {
        ProtocolListener l = this.listener;
        if (l != null) {
            l.onConnected(account);
        }
    }

    private void fireDisconnected(String reason) {
        ProtocolListener l = this.listener;
        if (l != null) {
            l.onDisconnected(account, reason);
        }
    }

    private void fireStatus(String status) {
        ProtocolListener l = this.listener;
        if (l != null) {
            l.onStatus(account, status);
        }
    }

    private void fireError(String error) {
        ProtocolListener l = this.listener;
        if (l != null) {
            l.onError(account, error);
        }
    }
}
