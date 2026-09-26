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
package org.jdesktop.lg3d.ftpclient.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Objects;
import java.util.UUID;

/**
 * A saved remote site: everything needed to reach one FTP / FTPS / SFTP server.
 *
 * <p>This is a plain Jackson-serializable bean (no-arg constructor plus
 * getters/setters) persisted by {@link ProfileStore} as JSON. It deliberately
 * carries no live connection; opening one is the job of the {@code net} layer,
 * so a profile is cheap to create, copy, edit and store.</p>
 *
 * <p>The {@code password} is optional and, by default, not persisted: a profile
 * keeps it only when {@link #isSavePassword()} is {@code true}, in which case
 * {@link ProfileStore} stores an obfuscated (not strongly encrypted) form. The
 * {@code id} is a stable identifier assigned on construction so profiles can be
 * renamed without losing their identity. The default protocol is {@link Protocol#FTPS}
 * so a freshly created site is secure unless the user deliberately downgrades it.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SiteProfile {

    /** Default character encoding used for the control channel and file names. */
    public static final String DEFAULT_ENCODING = "UTF-8";

    private String id;
    private String name = "";
    private Protocol protocol = Protocol.FTPS;
    private String host = "";
    private int port;
    private String user = "";
    private String password;
    private boolean savePassword;
    private String remoteDir = "";
    private TransferMode transferMode = TransferMode.PASSIVE;
    private String encoding = DEFAULT_ENCODING;

    /** Creates a profile with a fresh random id and the secure default protocol. */
    public SiteProfile() {
        this.id = UUID.randomUUID().toString();
    }

    /**
     * Creates a profile with the given display name, protocol and host.
     *
     * @param name     human-readable label shown in the site list
     * @param protocol the wire protocol; {@code null} falls back to {@link Protocol#FTPS}
     * @param host     the server host name or address
     */
    public SiteProfile(String name, Protocol protocol, String host) {
        this();
        this.name = Objects.requireNonNullElse(name, "");
        this.protocol = (protocol == null) ? Protocol.FTPS : protocol;
        this.host = Objects.requireNonNullElse(host, "");
    }

    /** @return the stable identifier, never {@code null}. */
    public String getId() {
        return id;
    }

    /** Restores the id (used by Jackson); regenerates one when absent. */
    public void setId(String id) {
        this.id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNullElse(name, "");
    }

    /** @return the wire protocol, never {@code null}. */
    public Protocol getProtocol() {
        return protocol;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = (protocol == null) ? Protocol.FTPS : protocol;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = Objects.requireNonNullElse(host, "").trim();
    }

    /** @return the explicit port, or {@code 0} to use the protocol default. */
    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = (port < 0 || port > 65535) ? 0 : port;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = Objects.requireNonNullElse(user, "");
    }

    /** @return the password, or {@code null} when it is not stored. */
    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /** @return {@code true} to persist the (obfuscated) password. */
    public boolean isSavePassword() {
        return savePassword;
    }

    public void setSavePassword(boolean savePassword) {
        this.savePassword = savePassword;
    }

    /** @return the initial remote directory to enter after connect (may be empty). */
    public String getRemoteDir() {
        return remoteDir;
    }

    public void setRemoteDir(String remoteDir) {
        this.remoteDir = Objects.requireNonNullElse(remoteDir, "");
    }

    /** @return the FTP data-connection mode, never {@code null}. */
    public TransferMode getTransferMode() {
        return transferMode;
    }

    public void setTransferMode(TransferMode transferMode) {
        this.transferMode = (transferMode == null) ? TransferMode.PASSIVE : transferMode;
    }

    /** @return the control-channel / file-name encoding, never {@code null}. */
    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = (encoding == null || encoding.isBlank()) ? DEFAULT_ENCODING : encoding;
    }

    /**
     * The effective port: the explicit {@link #getPort()} when set, else the
     * protocol's conventional default.
     *
     * @return the port to connect to
     */
    @JsonIgnore
    public int resolvePort() {
        return (port > 0) ? port : protocol.getDefaultPort();
    }

    /** @return {@code true} when this site's protocol encrypts the session. */
    @JsonIgnore
    public boolean isSecure() {
        return protocol.isSecure();
    }

    /** @return {@code true} when the minimum fields needed to connect are present. */
    @JsonIgnore
    public boolean isConnectable() {
        return host != null && !host.isBlank();
    }

    /** @return a defensive copy of this profile. */
    public SiteProfile copy() {
        SiteProfile p = new SiteProfile();
        p.id = this.id;
        p.name = this.name;
        p.protocol = this.protocol;
        p.host = this.host;
        p.port = this.port;
        p.user = this.user;
        p.password = this.password;
        p.savePassword = this.savePassword;
        p.remoteDir = this.remoteDir;
        p.transferMode = this.transferMode;
        p.encoding = this.encoding;
        return p;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SiteProfile other)) {
            return false;
        }
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "SiteProfile[" + name + " -> " + protocol.name() + "://" + host + "]";
    }
}
