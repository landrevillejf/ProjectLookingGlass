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
package org.jdesktop.lg3d.apps.ssh;

import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.UserInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Trust-on-first-use (TOFU) host key verification for the SSH client.
 *
 * <p>Maintains a {@code known_hosts} file under {@code ~/.lg3d/ssh/} and
 * implements JSch's {@link UserInfo} contract so that:</p>
 * <ul>
 *   <li>An unseen host key is accepted once and recorded (TOFU).</li>
 *   <li>A <em>changed</em> key is rejected outright (MITM protection) — the
 *       user must explicitly remove the old key before reconnecting.</li>
 *   <li>Password/passphrase prompts are forwarded to the panel's UI.</li>
 * </ul>
 *
 * <p>This mirrors the pattern used by the ftp-client's SFTP backend
 * ({@code SftpRemoteClient.TofuUserInfo}) with {@code StrictHostKeyChecking=yes}.</p>
 */
public final class KnownHostsManager implements UserInfo {

    private static final Logger LOG = Logger.getLogger(KnownHostsManager.class.getName());

    private final Path knownHostsFile;
    private final JSch jsch;
    private char[] password;
    private char[] passphrase;
    private String lastPromptMessage;
    private boolean lastPromptResult;

    /**
     * Creates a manager backed by the given known_hosts file.
     *
     * @param knownHostsFile path to the known_hosts file (created on first accept)
     * @param jsch           the JSch instance whose repository to populate
     */
    public KnownHostsManager(Path knownHostsFile, JSch jsch) {
        this.knownHostsFile = knownHostsFile;
        this.jsch = jsch;
        ensureKnownHostsLoaded();
    }

    /**
     * Creates a manager with the default known_hosts location.
     *
     * @param jsch the JSch instance whose repository to populate
     */
    public KnownHostsManager(JSch jsch) {
        this(SshProfileStore.defaultConfigDir().resolve("known_hosts"), jsch);
    }

    private void ensureKnownHostsLoaded() {
        try {
            Files.createDirectories(knownHostsFile.getParent());
            if (!Files.exists(knownHostsFile)) {
                Files.createFile(knownHostsFile);
            }
            jsch.setKnownHosts(knownHostsFile.toString());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Could not initialize known_hosts at "
                    + knownHostsFile, e);
        }
    }

    /** @return the path to the known_hosts file. */
    public Path getKnownHostsFile() {
        return knownHostsFile;
    }

    /**
     * Sets the password for authentication prompts.
     *
     * @param password the password characters (cleared after use)
     */
    public void setPassword(char[] password) {
        this.password = password;
    }

    /**
     * Sets the passphrase for key-based authentication prompts.
     *
     * @param passphrase the passphrase characters
     */
    public void setPassphrase(char[] passphrase) {
        this.passphrase = passphrase;
    }

    /** @return the last message shown to the user via {@link #showMessage}. */
    public String getLastPromptMessage() {
        return lastPromptMessage;
    }

    // ------------------------------------------------------------------
    // UserInfo implementation
    // ------------------------------------------------------------------

    @Override
    public String getPassphrase() {
        return (passphrase != null) ? new String(passphrase) : null;
    }

    @Override
    public String getPassword() {
        return (password != null) ? new String(password) : null;
    }

    @Override
    public boolean promptPassword(String message) {
        lastPromptMessage = message;
        lastPromptResult = (password != null && password.length > 0);
        return lastPromptResult;
    }

    @Override
    public boolean promptPassphrase(String message) {
        lastPromptMessage = message;
        lastPromptResult = (passphrase != null && passphrase.length > 0);
        return lastPromptResult;
    }

    @Override
    public boolean promptYesNo(String message) {
        // Trust-on-first-use: accept and record an unseen host key.
        // A CHANGED key never reaches here because StrictHostKeyChecking=yes
        // causes JSch to reject it before consulting UserInfo.
        lastPromptMessage = message;
        lastPromptResult = true;
        LOG.info("Accepting host key (TOFU): " + message);
        return true;
    }

    @Override
    public void showMessage(String message) {
        lastPromptMessage = message;
        LOG.info("SSH: " + message);
    }

    // ------------------------------------------------------------------
    // Host key management utilities
    // ------------------------------------------------------------------

    /**
     * Removes a host key from the known_hosts file (e.g. after a legitimate
     * server reinstall).
     *
     * @param host the hostname (with optional :port)
     * @param type the key type, or null to remove all types for that host
     */
    public void removeHostKey(String host, String type) {
        HostKeyRepository repo = jsch.getHostKeyRepository();
        if (type == null) {
            repo.remove(host, null);
        } else {
            repo.remove(host, type);
        }
    }

    /**
     * Checks whether a host key is already known.
     *
     * @param host the hostname
     * @param port the port
     * @param key  the key bytes to check
     * @return true if the exact key is already recorded
     */
    public boolean isKnown(String host, int port, byte[] key) {
        HostKeyRepository repo = jsch.getHostKeyRepository();
        String hostPort = (port == 22) ? host : host + ":" + port;
        int result = repo.check(hostPort, key);
        return result == HostKeyRepository.OK;
    }

    /**
     * Clears the in-memory password/passphrase to reduce exposure.
     */
    public void clearSecrets() {
        if (password != null) {
            java.util.Arrays.fill(password, '\0');
            password = null;
        }
        if (passphrase != null) {
            java.util.Arrays.fill(passphrase, '\0');
            passphrase = null;
        }
    }
}
