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

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A saved connection/account: everything needed to reach one chat network over
 * one {@link MessengerProtocol}. Persisted as JSON by {@link MessengerStore}.
 *
 * <p><b>Security:</b> the password fields are {@link JsonIgnore} and therefore
 * <em>never written to disk</em>. A secret lives only in memory for the lifetime
 * of the session and is entered (or re-entered) at connect time; the persistent
 * record keeps just a {@code passwordPrompt} flag so the UI knows to ask.</p>
 */
public class AccountConfig {

    /** Well-known default ports per transport, used when {@code port <= 0}. */
    public static final int PORT_PLAIN = 6667;
    public static final int PORT_TLS = 6697;

    private String id = UUID.randomUUID().toString();
    private String name = "";
    private String protocolId = "irc";
    private String host = "";
    private int port = PORT_TLS;
    private boolean useTls = true;
    private String nickname = "";
    private String username = "";
    private String realName = "";
    private List<String> autoJoinChannels = new ArrayList<>();
    private boolean autoConnect;
    private boolean passwordPrompt;

    // In-memory only, never persisted (see the class javadoc).
    private transient String serverPassword = "";
    private transient String nickServPassword = "";

    public AccountConfig() {
    }

    public AccountConfig(String name, String protocolId, String host, String nickname) {
        setName(name);
        setProtocolId(protocolId);
        setHost(host);
        setNickname(nickname);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = (id == null || id.isBlank())
            ? UUID.randomUUID().toString() : id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = (name == null) ? "" : name; }

    public String getProtocolId() { return protocolId; }
    public void setProtocolId(String protocolId) {
        this.protocolId = (protocolId == null || protocolId.isBlank()) ? "irc" : protocolId;
    }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = (host == null) ? "" : host.trim(); }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    /** The effective port: the configured one, else the transport default. */
    @JsonIgnore
    public int getEffectivePort() {
        return (port > 0) ? port : (useTls ? PORT_TLS : PORT_PLAIN);
    }

    public boolean isUseTls() { return useTls; }
    public void setUseTls(boolean useTls) { this.useTls = useTls; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) {
        this.nickname = (nickname == null) ? "" : nickname.trim();
    }

    public String getUsername() { return username; }
    public void setUsername(String username) {
        this.username = (username == null) ? "" : username.trim();
    }

    public String getRealName() { return realName; }
    public void setRealName(String realName) { this.realName = (realName == null) ? "" : realName; }

    public List<String> getAutoJoinChannels() {
        if (autoJoinChannels == null) {
            autoJoinChannels = new ArrayList<>();
        }
        return autoJoinChannels;
    }

    public void setAutoJoinChannels(List<String> channels) {
        this.autoJoinChannels = (channels == null) ? new ArrayList<>() : new ArrayList<>(channels);
    }

    public boolean isAutoConnect() { return autoConnect; }
    public void setAutoConnect(boolean autoConnect) { this.autoConnect = autoConnect; }

    public boolean isPasswordPrompt() { return passwordPrompt; }
    public void setPasswordPrompt(boolean passwordPrompt) { this.passwordPrompt = passwordPrompt; }

    /** The in-memory server password (never persisted). */
    @JsonIgnore
    public String getServerPassword() {
        return (serverPassword == null) ? "" : serverPassword;
    }

    public void setServerPassword(String serverPassword) {
        this.serverPassword = (serverPassword == null) ? "" : serverPassword;
    }

    /** The in-memory NickServ/account password (never persisted). */
    @JsonIgnore
    public String getNickServPassword() {
        return (nickServPassword == null) ? "" : nickServPassword;
    }

    public void setNickServPassword(String nickServPassword) {
        this.nickServPassword = (nickServPassword == null) ? "" : nickServPassword;
    }

    /**
     * A deep copy with the same id (so history links hold) but independent
     * collections. The transient secrets are copied too, for in-session edits.
     *
     * @return the copy
     */
    public AccountConfig copy() {
        AccountConfig c = new AccountConfig();
        c.id = this.id;
        c.name = this.name;
        c.protocolId = this.protocolId;
        c.host = this.host;
        c.port = this.port;
        c.useTls = this.useTls;
        c.nickname = this.nickname;
        c.username = this.username;
        c.realName = this.realName;
        c.autoJoinChannels = new ArrayList<>(getAutoJoinChannels());
        c.autoConnect = this.autoConnect;
        c.passwordPrompt = this.passwordPrompt;
        c.serverPassword = this.serverPassword;
        c.nickServPassword = this.nickServPassword;
        return c;
    }

    @Override
    public String toString() {
        String label = (name == null || name.isBlank()) ? nickname : name;
        if (label == null || label.isBlank()) {
            label = protocolId + "://" + host;
        }
        return label;
    }
}
