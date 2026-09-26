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
package org.jdesktop.lg3d.ftpclient.session;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jdesktop.lg3d.ftpclient.model.AppSettings;
import org.jdesktop.lg3d.ftpclient.model.ProfileStore;
import org.jdesktop.lg3d.ftpclient.model.SiteProfile;
import org.jdesktop.lg3d.ftpclient.net.RemoteClient;
import org.jdesktop.lg3d.ftpclient.net.RemoteClientFactory;
import org.jdesktop.lg3d.ftpclient.net.RemoteEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The application controller: owns the persisted site profiles and settings, the
 * single active {@link RemoteClient} session, and the {@link TransferManager}
 * that runs the queue against it.
 *
 * <p>The UI talks to the server exclusively through this class, so persistence,
 * connection lifecycle and transport selection live in one place. Unlike a
 * database tool it keeps just one connection at a time - an FTP/SFTP browser has
 * one "current site" - and the transfer manager borrows that connection through
 * a supplier rather than holding its own.</p>
 *
 * <p>It is not thread-safe with respect to connect/disconnect; the UI opens and
 * closes the session from a worker thread and then hands the live client back to
 * the EDT. The transport is created by an injectable {@link RemoteClientFactory}
 * so tests can substitute a mock without opening a socket.</p>
 */
public final class ConnectionManager {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionManager.class);

    private final ProfileStore store;
    private final RemoteClientFactory factory;
    private final List<SiteProfile> profiles = new ArrayList<>();
    private final TransferManager transfers;
    private AppSettings settings;

    private RemoteClient client;
    private SiteProfile activeProfile;

    /** Creates a manager backed by the default store and a real client factory. */
    public ConnectionManager() {
        this(new ProfileStore());
    }

    /**
     * Creates a manager backed by an explicit store (used by the tests).
     *
     * @param store the persistence backend
     */
    public ConnectionManager(ProfileStore store) {
        this(store, new RemoteClientFactory());
    }

    /**
     * Creates a manager with an explicit store and client factory (used by the
     * tests to inject a mock transport).
     *
     * @param store   the persistence backend
     * @param factory builds a {@link RemoteClient} for a profile
     */
    public ConnectionManager(ProfileStore store, RemoteClientFactory factory) {
        this.store = Objects.requireNonNull(store, "store");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.settings = store.loadSettings();
        this.profiles.addAll(store.loadProfiles());
        this.transfers = new TransferManager(this::getClient, this.settings);
    }

    public ProfileStore getStore() {
        return store;
    }

    public AppSettings getSettings() {
        return settings;
    }

    /** @return the transfer queue manager bound to this session. */
    public TransferManager getTransfers() {
        return transfers;
    }

    // ------------------------------------------------------------------
    // profiles
    // ------------------------------------------------------------------

    /** @return an unmodifiable view of the saved profiles. */
    public List<SiteProfile> getProfiles() {
        return List.copyOf(profiles);
    }

    /**
     * Finds a saved profile by id.
     *
     * @param id the profile id
     * @return the profile, or empty
     */
    public Optional<SiteProfile> getProfile(String id) {
        return profiles.stream().filter(p -> p.getId().equals(id)).findFirst();
    }

    /**
     * Adds a new profile and persists the list.
     *
     * @param profile the profile to add
     */
    public void addProfile(SiteProfile profile) {
        if (profile == null) {
            return;
        }
        profiles.add(profile);
        store.saveProfiles(profiles);
    }

    /**
     * Replaces an existing profile (matched by id) and persists the list.
     *
     * @param profile the updated profile
     */
    public void updateProfile(SiteProfile profile) {
        if (profile == null) {
            return;
        }
        profiles.removeIf(p -> p.getId().equals(profile.getId()));
        profiles.add(profile);
        store.saveProfiles(profiles);
    }

    /**
     * Removes a profile, disconnecting first if it is the active site, and
     * persists the list.
     *
     * @param id the profile id
     */
    public void removeProfile(String id) {
        if (activeProfile != null && activeProfile.getId().equals(id)) {
            disconnect();
        }
        profiles.removeIf(p -> p.getId().equals(id));
        store.saveProfiles(profiles);
    }

    // ------------------------------------------------------------------
    // settings
    // ------------------------------------------------------------------

    /** Persists new settings and adopts them for the session and transfers. */
    public void saveSettings(AppSettings newSettings) {
        this.settings = (newSettings != null) ? newSettings : new AppSettings();
        transfers.setSettings(this.settings);
        store.saveSettings(this.settings);
    }

    // ------------------------------------------------------------------
    // connection
    // ------------------------------------------------------------------

    /**
     * Connects to a saved profile.
     *
     * @param profileId the saved profile id
     * @param password  an explicit password, or {@code null} to use the stored one
     * @return the live client
     * @throws IOException              when the server is unreachable or login fails
     * @throws IllegalArgumentException when the profile id is unknown
     */
    public RemoteClient connect(String profileId, String password) throws IOException {
        SiteProfile saved = getProfile(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown profile: " + profileId));
        return connect(saved, password);
    }

    /**
     * Connects to a profile instance (which need not be saved yet). Any existing
     * session is closed first, so there is only ever one active site.
     *
     * @param profile  the site to connect
     * @param password an explicit password, or {@code null} to use the profile's
     * @return the live client
     * @throws IOException when the server is unreachable or login fails
     */
    public RemoteClient connect(SiteProfile profile, String password) throws IOException {
        Objects.requireNonNull(profile, "profile");
        disconnect();
        SiteProfile work = profile.copy();
        if (password != null) {
            work.setPassword(password);
        }
        RemoteClient c = factory.create(work, settings);
        try {
            c.connect();
        } catch (IOException | RuntimeException e) {
            safeClose(c);
            throw e;
        }
        this.client = c;
        this.activeProfile = work;
        LOG.debug("Connected to {}", work);
        return c;
    }

    /** @return the active client, or {@code null} when not connected. */
    public RemoteClient getClient() {
        if (client != null && !client.isConnected()) {
            // The transport dropped (server timeout, network loss); forget it.
            safeClose(client);
            client = null;
            activeProfile = null;
        }
        return client;
    }

    /** @return the profile of the active session, or {@code null}. */
    public SiteProfile getActiveProfile() {
        return activeProfile;
    }

    /** @return {@code true} while a session is established. */
    public boolean isConnected() {
        return getClient() != null;
    }

    /** Closes and forgets the active session; safe to call when not connected. */
    public void disconnect() {
        RemoteClient c = client;
        client = null;
        activeProfile = null;
        if (c != null) {
            safeClose(c);
        }
    }

    // ------------------------------------------------------------------
    // remote operations (delegate to the active client)
    // ------------------------------------------------------------------

    /**
     * Lists a remote directory on the active session.
     *
     * @param path the absolute directory
     * @return the entries
     * @throws IOException on a protocol error or when not connected
     */
    public List<RemoteEntry> list(String path) throws IOException {
        return requireClient().list(path);
    }

    /**
     * Resolves the current remote working directory.
     *
     * @return the absolute path
     * @throws IOException on a protocol error or when not connected
     */
    public String getWorkingDirectory() throws IOException {
        return requireClient().getWorkingDirectory();
    }

    /**
     * Changes the remote working directory.
     *
     * @param path the absolute directory
     * @throws IOException on a protocol error or when not connected
     */
    public void changeDirectory(String path) throws IOException {
        requireClient().changeDirectory(path);
    }

    /**
     * Creates a remote directory.
     *
     * @param path the absolute directory
     * @throws IOException on a protocol error or when not connected
     */
    public void makeDirectory(String path) throws IOException {
        requireClient().makeDirectory(path);
    }

    /**
     * Renames or moves a remote path.
     *
     * @param from the current absolute path
     * @param to   the new absolute path
     * @throws IOException on a protocol error or when not connected
     */
    public void rename(String from, String to) throws IOException {
        requireClient().rename(from, to);
    }

    /**
     * Deletes a remote file.
     *
     * @param path the absolute file path
     * @throws IOException on a protocol error or when not connected
     */
    public void delete(String path) throws IOException {
        requireClient().delete(path);
    }

    /**
     * Removes a remote directory.
     *
     * @param path the absolute directory
     * @throws IOException on a protocol error or when not connected
     */
    public void removeDirectory(String path) throws IOException {
        requireClient().removeDirectory(path);
    }

    /**
     * Reports whether a remote path exists.
     *
     * @param path the absolute path
     * @return {@code true} when the server reports it
     * @throws IOException on a protocol error or when not connected
     */
    public boolean exists(String path) throws IOException {
        return requireClient().exists(path);
    }

    /**
     * Reports the size of a remote file.
     *
     * @param path the absolute file path
     * @return the size in bytes, or {@link RemoteEntry#UNKNOWN_SIZE}
     * @throws IOException on a protocol error or when not connected
     */
    public long size(String path) throws IOException {
        return requireClient().size(path);
    }

    /** Cancels every queued or running transfer and closes the session. */
    public void shutdown() {
        for (TransferJob job : transfers.getQueue()) {
            job.requestCancel();
        }
        disconnect();
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private RemoteClient requireClient() throws IOException {
        RemoteClient c = getClient();
        if (c == null) {
            throw new IOException("Not connected to a server");
        }
        return c;
    }

    private static void safeClose(RemoteClient c) {
        try {
            c.close();
        } catch (RuntimeException e) {
            LOG.debug("Ignoring error while closing the client", e);
        }
    }
}
