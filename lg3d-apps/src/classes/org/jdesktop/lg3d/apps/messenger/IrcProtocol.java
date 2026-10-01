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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * The native, in-process IRC backend: a complete RFC 2812 client over a plain or
 * TLS socket, with no third-party dependency. It handles registration
 * ({@code PASS}/{@code NICK}/{@code USER}), nickname-in-use recovery, server
 * {@code PING} keep-alive and dead-link detection, {@code PRIVMSG}/{@code NOTICE}
 * (including CTCP {@code ACTION}/{@code VERSION}/{@code PING}/{@code TIME}),
 * {@code JOIN}/{@code PART}/{@code QUIT}/{@code NICK}/{@code TOPIC}, the
 * {@code 001} welcome, {@code 353} name replies and {@code 376} end-of-MOTD,
 * optional NickServ {@code IDENTIFY}, and automatic reconnect with backoff.
 *
 * <p>Security: with {@code useTls} the socket is an {@link SSLSocket} from the
 * default {@link SSLSocketFactory}, so certificates are validated against the
 * JDK trust store; passwords are held in memory only (see {@link AccountConfig})
 * and are never logged.</p>
 *
 * <p>All listener callbacks fire on the reader thread; the UI marshals to the
 * EDT. {@link #sendRaw(String)} and the send helpers are thread-safe.</p>
 */
public class IrcProtocol implements MessengerProtocol {

    /** Numeric replies this client acts on. */
    static final String RPL_WELCOME = "001";
    static final String RPL_TOPIC = "332";
    static final String RPL_NAMREPLY = "353";
    static final String RPL_ENDOFMOTD = "376";
    static final String RPL_MOTD = "372";
    static final String ERR_NICKNAMEINUSE = "433";
    static final String ERR_NO_MOTD = "422";

    private static final int CONNECT_TIMEOUT_MS = 15_000;

    private final Set<String> channels = new LinkedHashSet<>();

    private volatile boolean stopped = true;
    private volatile boolean registered;
    private volatile Socket socket;
    private volatile String currentNick = "";

    private BufferedWriter writer;
    private AccountConfig account;
    private ProtocolListener listener;
    private ScheduledExecutorService scheduler;

    private boolean autoReconnect;
    private int reconnectDelaySeconds = 10;
    private int keepAliveSeconds = 60;

    public IrcProtocol() {
        // defaults: reconnect off (the panel turns it on from settings)
    }

    public void setAutoReconnect(boolean autoReconnect) { this.autoReconnect = autoReconnect; }
    public boolean isAutoReconnect() { return autoReconnect; }
    public void setReconnectDelaySeconds(int s) { this.reconnectDelaySeconds = Math.max(1, s); }
    public void setKeepAliveSeconds(int s) { this.keepAliveSeconds = Math.max(5, s); }

    /** The nick currently in use on the server (may differ after a collision). */
    public String getCurrentNick() { return currentNick; }

    /** The channels this client believes it has joined. */
    public Set<String> getChannels() { return new LinkedHashSet<>(channels); }

    @Override public String id() { return "irc"; }
    @Override public String displayName() { return "IRC"; }

    @Override
    public String description() {
        return "Native IRC client (RFC 2812) over a plain or TLS socket.";
    }

    @Override public boolean isNative() { return true; }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHAT, Capability.CHANNELS, Capability.PRESENCE,
                Capability.TLS, Capability.ACTIONS, Capability.NATIVE);
    }

    @Override
    public void connect(AccountConfig account, ProtocolListener listener) {
        if (!stopped) {
            return; // already connecting/connected
        }
        this.account = account;
        this.listener = listener;
        this.currentNick = account.getNickname();
        this.stopped = false;
        this.registered = false;
        this.channels.clear();
        Thread t = new Thread(this::runConnection, "irc-" + account.getHost());
        t.setDaemon(true);
        t.start();
    }

    private void runConnection() {
        String reason = "Connection closed";
        try {
            Socket s = openSocket();
            this.socket = s;
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(
                    new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            register();
            startKeepAlive();
            fireStatus("Connecting to " + account.getHost() + ":" + account.getEffectivePort());

            String line;
            while (!stopped && (line = in.readLine()) != null) {
                handleLine(line);
            }
            reason = stopped ? "Disconnected" : "Server closed the connection";
        } catch (IOException ex) {
            reason = (ex.getMessage() == null) ? ex.toString() : ex.getMessage();
            if (!stopped) {
                fireError("Connection error: " + reason);
            }
        } finally {
            registered = false;
            closeQuietly();
            boolean wasStopped = stopped;
            fireDisconnected(reason);
            if (!wasStopped && autoReconnect) {
                scheduleReconnect();
            }
        }
    }

    private Socket openSocket() throws IOException {
        int port = account.getEffectivePort();
        Socket s;
        if (account.isUseTls()) {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) f.createSocket();
            ssl.connect(new InetSocketAddress(account.getHost(), port), CONNECT_TIMEOUT_MS);
            ssl.startHandshake();
            s = ssl;
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(account.getHost(), port), CONNECT_TIMEOUT_MS);
        }
        s.setSoTimeout(0);
        return s;
    }

    private void register() throws IOException {
        String pw = account.getServerPassword();
        if (pw != null && !pw.isEmpty()) {
            sendRaw("PASS " + pw);
        }
        String nick = account.getNickname();
        if (nick.isEmpty()) {
            nick = "lg3d" + (int) (Math.random() * 9000 + 1000);
            currentNick = nick;
        }
        sendRaw("NICK " + nick);
        String user = account.getUsername().isEmpty() ? nick : account.getUsername();
        String real = account.getRealName().isEmpty() ? "LG3D Messenger" : account.getRealName();
        sendRaw("USER " + user + " 0 * :" + real);
    }

    private void startKeepAlive() {
        stopScheduler();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "irc-keepalive");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
                () -> sendRaw("PING :lg3d-" + System.currentTimeMillis()),
                keepAliveSeconds, keepAliveSeconds, TimeUnit.SECONDS);
    }

    private void stopScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void scheduleReconnect() {
        stopScheduler();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "irc-reconnect");
            t.setDaemon(true);
            return t;
        });
        stopped = false;
        fireStatus("Reconnecting in " + reconnectDelaySeconds + "s\u2026");
        AccountConfig acc = this.account;
        ProtocolListener lis = this.listener;
        scheduler.schedule(() -> {
            stopScheduler();
            if (!stopped) {
                Thread t = new Thread(this::runConnection, "irc-" + acc.getHost());
                t.setDaemon(true);
                t.start();
            }
        }, reconnectDelaySeconds, TimeUnit.SECONDS);
        // keep a reference so a later disconnect can cancel it
        this.account = acc;
        this.listener = lis;
    }

    // ------------------------------------------------------------------
    // Incoming dispatch
    // ------------------------------------------------------------------

    private void handleLine(String line) {
        IrcMessage m = IrcMessage.parse(line);
        if (m == null) {
            return;
        }
        String cmd = m.getCommand();
        switch (cmd) {
            case "PING" -> sendRaw("PONG :" + m.trailing());
            case "PONG" -> { /* keep-alive ack */ }
            case "PRIVMSG" -> handlePrivmsg(m, ChatMessage.Kind.PRIVMSG);
            case "NOTICE" -> handlePrivmsg(m, ChatMessage.Kind.NOTICE);
            case "JOIN" -> handleJoin(m);
            case "PART" -> fire(
                    new ChatMessage(m.getNick(), m.param(0), m.trailing(), ChatMessage.Kind.PART));
            case "QUIT" -> fire(
                    new ChatMessage(m.getNick(), "", m.trailing(), ChatMessage.Kind.QUIT));
            case "NICK" -> handleNick(m);
            case "TOPIC" -> fire(
                    new ChatMessage(m.getNick(), m.param(0), m.trailing(), ChatMessage.Kind.TOPIC));
            case "ERROR" -> {
                fireError(m.trailing());
            }
            default -> handleNumeric(m);
        }
    }

    private void handleNumeric(IrcMessage m) {
        String cmd = m.getCommand();
        switch (cmd) {
            case RPL_WELCOME -> {
                registered = true;
                currentNick = m.param(0);
                fireConnected();
                String nsp = account.getNickServPassword();
                if (nsp != null && !nsp.isEmpty()) {
                    sendRaw("PRIVMSG NickServ :IDENTIFY " + nsp);
                }
                for (String ch : account.getAutoJoinChannels()) {
                    if (ch != null && !ch.isBlank()) {
                        joinChannel(ch.trim());
                    }
                }
            }
            case ERR_NICKNAMEINUSE -> {
                if (!registered) {
                    currentNick = currentNick + "_";
                    sendRaw("NICK " + currentNick);
                } else {
                    fireError("Nickname in use: " + m.param(1));
                }
            }
            case RPL_NAMREPLY -> {
                String channel = m.param(2);
                List<String> names = new ArrayList<>();
                for (String tok : m.trailing().split("\\s+")) {
                    if (tok.isEmpty()) {
                        continue;
                    }
                    // Strip the @/+/%/&/~ mode prefix.
                    int i = 0;
                    while (i < tok.length() && "@+%&~".indexOf(tok.charAt(i)) >= 0) {
                        i++;
                    }
                    names.add(tok.substring(i));
                }
                if (listener != null) {
                    listener.onRosterUpdate(account, channel, names);
                }
            }
            case RPL_TOPIC -> fire(
                    new ChatMessage("", m.param(1), m.trailing(), ChatMessage.Kind.TOPIC));
            case RPL_MOTD -> fireStatus(m.trailing());
            case RPL_ENDOFMOTD, ERR_NO_MOTD -> fireStatus("Connected to " + account.getHost());
            default -> {
                // Other numerics are informational; surface only errors (4xx/5xx).
                if (cmd.length() == 3 && (cmd.charAt(0) == '4' || cmd.charAt(0) == '5')) {
                    fireError(cmd + " " + m.trailing());
                }
            }
        }
    }

    private void handlePrivmsg(IrcMessage m, ChatMessage.Kind baseKind) {
        String from = m.getNick();
        String rawTarget = m.param(0);
        String body = m.trailing();
        String conversation = conversationFor(m, rawTarget);

        if (IrcCodec.isCtcp(body)) {
            String[] ctcp = IrcCodec.parseCtcp(body);
            switch (ctcp[0]) {
                case "ACTION" -> fire(
                        new ChatMessage(from, conversation, ctcp[1], ChatMessage.Kind.ACTION));
                case "VERSION" -> sendRaw("NOTICE " + from
                        + " :" + IrcCodec.wrapCtcp("VERSION", "LG3D Messenger 1.0"));
                case "TIME" -> sendRaw("NOTICE " + from
                        + " :" + IrcCodec.wrapCtcp("TIME", java.time.ZonedDateTime.now().toString()));
                case "PING" -> sendRaw("NOTICE " + from
                        + " :" + IrcCodec.wrapCtcp("PING", ctcp[1]));
                default -> { /* ignore unknown CTCP */ }
            }
            return;
        }
        fire(new ChatMessage(from, conversation, IrcCodec.stripFormatting(body), baseKind));
    }

    private void handleJoin(IrcMessage m) {
        String channel = m.param(0);
        String nick = m.getNick();
        if (nick.equals(currentNick)) {
            channels.add(channel);
        }
        fire(new ChatMessage(nick, channel, "", ChatMessage.Kind.JOIN));
    }

    private void handleNick(IrcMessage m) {
        String oldNick = m.getNick();
        String newNick = m.param(0);
        if (oldNick.equals(currentNick)) {
            currentNick = newNick;
        }
        fire(new ChatMessage(oldNick, newNick, "", ChatMessage.Kind.NICK));
    }

    /**
     * The conversation a message belongs to: for a private message the peer's
     * nick (the target is us), otherwise the channel target.
     */
    private String conversationFor(IrcMessage m, String rawTarget) {
        if (rawTarget.equalsIgnoreCase(currentNick)) {
            return m.getNick();
        }
        return rawTarget;
    }

    // ------------------------------------------------------------------
    // Outgoing
    // ------------------------------------------------------------------

    @Override
    public void sendMessage(String target, String text) {
        if (target == null || target.isBlank() || text == null) {
            return;
        }
        for (String line : IrcCodec.splitMessage("PRIVMSG", target, text)) {
            sendRaw(line);
        }
    }

    @Override
    public void sendAction(String target, String text) {
        if (target == null || target.isBlank() || text == null) {
            return;
        }
        String ctcp = IrcCodec.wrapCtcp("ACTION", text);
        for (String line : IrcCodec.splitMessage("PRIVMSG", target, ctcp)) {
            sendRaw(line);
        }
    }

    @Override
    public void joinChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return;
        }
        String ch = channel.startsWith("#") || channel.startsWith("&") ? channel : "#" + channel;
        sendRaw("JOIN " + ch);
    }

    @Override
    public void partChannel(String channel, String reason) {
        if (channel == null || channel.isBlank()) {
            return;
        }
        channels.remove(channel);
        if (reason == null || reason.isBlank()) {
            sendRaw("PART " + channel);
        } else {
            sendRaw("PART " + channel + " :" + reason);
        }
    }

    /** Changes our nickname on the server. */
    public void changeNick(String newNick) {
        if (newNick != null && !newNick.isBlank()) {
            sendRaw("NICK " + newNick.trim());
        }
    }

    /** Sends a raw line (used for slash-commands the panel does not model). */
    public void sendRaw(String line) {
        BufferedWriter w = this.writer;
        if (w == null || line == null) {
            return;
        }
        synchronized (this) {
            try {
                w.write(line);
                w.write("\r\n");
                w.flush();
            } catch (IOException ex) {
                // The reader thread will observe the broken socket and reconnect.
            }
        }
    }

    @Override
    public boolean isConnected() {
        return registered && !stopped && socket != null && socket.isConnected() && !socket.isClosed();
    }

    @Override
    public void disconnect() {
        stopped = true;
        if (registered) {
            sendRaw("QUIT :Leaving");
        }
        registered = false;
        channels.clear();
        stopScheduler();
        closeQuietly();
    }

    private void closeQuietly() {
        Socket s = this.socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
        this.socket = null;
        this.writer = null;
    }

    // ------------------------------------------------------------------
    // Listener fan-out (null-safe)
    // ------------------------------------------------------------------

    private void fire(ChatMessage msg) {
        if (listener != null) {
            listener.onMessage(account, msg);
        }
    }

    private void fireConnected() {
        if (listener != null) {
            listener.onConnected(account);
        }
    }

    private void fireDisconnected(String reason) {
        if (listener != null) {
            listener.onDisconnected(account, reason);
        }
    }

    private void fireStatus(String status) {
        if (listener != null) {
            listener.onStatus(account, status);
        }
    }

    private void fireError(String error) {
        if (listener != null) {
            listener.onError(account, error);
        }
    }
}
