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

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A bridge backend for chat networks that require an external or native stack
 * this project does not (and cannot reliably) bundle &mdash; XMPP, Matrix,
 * Telegram, WhatsApp, Signal, SMS, SIP. Rather than pretend to speak the wire
 * protocol in-process, a bridge opens the network's official client or web app
 * (a deep link in the system browser, or an external command) and reports the
 * hand-off honestly through the {@link ProtocolListener}.
 *
 * <p>This is the same design boundary the Video Conference app draws around
 * WebRTC: the desktop owns the address book, the launcher and the transcript of
 * what it can see, while the real-time session runs in the tool built for it. A
 * bridge is {@link #isNative() non-native} and advertises no
 * {@link MessengerProtocol.Capability#NATIVE}, so the UI can label it and route
 * "connect" to the hand-off.</p>
 */
public class BridgeProtocol implements MessengerProtocol {

    private final String id;
    private final String displayName;
    private final String description;
    /** Deep-link template; may contain {@code %TARGET}, {@code %HOST}, {@code %NICK}. */
    private final String urlTemplate;
    /** External command template; may contain {@code %TARGET}, {@code %HOST}, {@code %NICK}. */
    private final String commandTemplate;

    private volatile boolean connected;
    private AccountConfig account;
    private ProtocolListener listener;

    /**
     * @param id              the stable protocol id
     * @param displayName     the human-readable name
     * @param description     how the bridge reaches the network
     * @param urlTemplate     a browser deep link, or null
     * @param commandTemplate an external command, or null (URL wins if both set)
     */
    public BridgeProtocol(String id, String displayName, String description,
                          String urlTemplate, String commandTemplate) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.urlTemplate = urlTemplate;
        this.commandTemplate = commandTemplate;
    }

    @Override public String id() { return id; }
    @Override public String displayName() { return displayName; }
    @Override public String description() { return description; }
    @Override public boolean isNative() { return false; }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.CHAT);
    }

    /**
     * Substitutes the {@code %TARGET}/{@code %HOST}/{@code %NICK} placeholders in
     * a template. Pure and unit-testable.
     *
     * @param template the template, may be null
     * @param account  the account supplying host/nick
     * @param target   the conversation target (may be null)
     * @return the expanded string, or "" for a null/blank template
     */
    public static String expand(String template, AccountConfig account, String target) {
        if (template == null || template.isBlank()) {
            return "";
        }
        String host = (account == null) ? "" : account.getHost();
        String nick = (account == null) ? "" : account.getNickname();
        String tgt = (target == null) ? "" : target;
        return template.replace("%TARGET", tgt)
                .replace("%HOST", host)
                .replace("%NICK", nick);
    }

    /** The deep link this bridge would open for {@code target} (may be ""). */
    public String buildUrl(String target) {
        return expand(urlTemplate, account, target);
    }

    /** The external command this bridge would run for {@code target} (may be ""). */
    public String buildCommand(String target) {
        return expand(commandTemplate, account, target);
    }

    @Override
    public void connect(AccountConfig account, ProtocolListener listener) {
        this.account = account;
        this.listener = listener;
        fireStatus("Opening the " + displayName + " client\u2026");
        boolean opened = launch("");
        this.connected = true;
        fireConnected();
        fire(new ChatMessage(null, "", opened
                ? displayName + " opened in its own client. This session runs there."
                : "Could not open " + displayName + " automatically "
                        + "(no browser or client available); launch it manually.",
                ChatMessage.Kind.SYSTEM));
    }

    @Override
    public void disconnect() {
        connected = false;
        fireDisconnected("Bridge closed");
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void sendMessage(String target, String text) {
        // A bridge cannot inject text into the external session; the best it can
        // do is land the user on that conversation. Be explicit about it.
        boolean opened = launch(target);
        fire(new ChatMessage(null, target == null ? "" : target, opened
                ? "Opened " + displayName + " at \"" + target + "\" \u2014 send from there."
                : "Open " + displayName + " manually to message \"" + target + "\".",
                ChatMessage.Kind.SYSTEM));
    }

    /**
     * Opens the deep link (preferred) or runs the external command. Headless-safe
     * and never throws.
     *
     * @param target the conversation target for placeholder expansion
     * @return true if a browser/command was dispatched
     */
    private boolean launch(String target) {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }
        String url = buildUrl(target);
        if (!url.isBlank()) {
            try {
                if (Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI.create(url));
                    return true;
                }
            } catch (Exception ex) {
                // fall through to the external command
            }
        }
        String cmd = buildCommand(target);
        if (!cmd.isBlank()) {
            try {
                new ProcessBuilder(splitCommand(cmd)).start();
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
        return false;
    }

    /**
     * Splits a command line on whitespace, honouring double quotes. Shared with
     * the panel's external-command handling.
     *
     * @param command the command string
     * @return the argument tokens
     */
    static List<String> splitCommand(String command) {
        List<String> out = new ArrayList<>();
        if (command == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(ch) && !inQuotes) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
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

    private void fire(ChatMessage msg) {
        if (listener != null) {
            listener.onMessage(account, msg);
        }
    }
}
