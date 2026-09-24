/**
 * Project Looking Glass
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.dbmanager.session;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jdesktop.lg3d.dbmanager.jdbc.ConnectionProvider;
import org.jdesktop.lg3d.dbmanager.model.AppSettings;
import org.jdesktop.lg3d.dbmanager.model.ConnectionProfile;
import org.jdesktop.lg3d.dbmanager.model.DbDriver;
import org.jdesktop.lg3d.dbmanager.model.DriverRegistry;
import org.jdesktop.lg3d.dbmanager.model.ProfileStore;

/**
 * The application controller: owns the persisted profiles and settings, the
 * driver registry, and the set of currently open {@link DbSession}s.
 *
 * <p>The UI talks to the database exclusively through this class, which keeps
 * persistence, connection lifecycle and driver resolution in one place. It is
 * not thread-safe; the UI serializes access (connections are opened from a
 * worker and the resulting session is then handed back to the EDT).</p>
 */
public final class ConnectionManager {

    private final ProfileStore store;
    private final DriverRegistry drivers;
    private final ConnectionProvider provider;
    private final List<ConnectionProfile> profiles = new ArrayList<>();
    private final Map<String, DbSession> sessions = new LinkedHashMap<>();
    private AppSettings settings;

    /** Creates a manager backed by the default {@link ProfileStore}. */
    public ConnectionManager() {
        this(new ProfileStore());
    }

    /**
     * Creates a manager backed by an explicit store (used by the tests).
     *
     * @param store the persistence backend
     */
    public ConnectionManager(ProfileStore store) {
        this.store = store;
        this.drivers = new DriverRegistry();
        this.settings = store.loadSettings();
        this.profiles.addAll(store.loadProfiles());
        this.drivers.setCustomDrivers(store.loadCustomDrivers());
        this.provider = new ConnectionProvider(drivers);
    }

    public ProfileStore getStore() {
        return store;
    }

    public DriverRegistry getDrivers() {
        return drivers;
    }

    public AppSettings getSettings() {
        return settings;
    }

    /** @return an unmodifiable view of the saved profiles. */
    public List<ConnectionProfile> getProfiles() {
        return List.copyOf(profiles);
    }

    /**
     * Finds a saved profile by id.
     *
     * @param id the profile id
     * @return the profile, or empty
     */
    public Optional<ConnectionProfile> getProfile(String id) {
        return profiles.stream().filter(p -> p.getId().equals(id)).findFirst();
    }

    /**
     * Adds a new profile and persists the list.
     *
     * @param profile the profile to add
     */
    public void addProfile(ConnectionProfile profile) {
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
    public void updateProfile(ConnectionProfile profile) {
        if (profile == null) {
            return;
        }
        profiles.removeIf(p -> p.getId().equals(profile.getId()));
        profiles.add(profile);
        store.saveProfiles(profiles);
    }

    /**
     * Removes a profile, disconnecting it first if open, and persists the list.
     *
     * @param id the profile id
     */
    public void removeProfile(String id) {
        disconnect(id);
        profiles.removeIf(p -> p.getId().equals(id));
        store.saveProfiles(profiles);
    }

    /**
     * Opens a connection for a profile.
     *
     * @param profileId the saved profile id
     * @param password  an explicit password, or {@code null} to use the stored one
     * @return the live session
     * @throws SQLException              when the driver cannot connect
     * @throws IllegalArgumentException  when the profile id is unknown
     */
    public DbSession connect(String profileId, String password) throws SQLException {
        ConnectionProfile saved = getProfile(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown profile: " + profileId));
        return connect(saved, password);
    }

    /**
     * Opens a connection for a profile instance (which need not be saved yet).
     *
     * @param profile  the profile to connect
     * @param password an explicit password, or {@code null} to use the profile's
     * @return the live session
     * @throws SQLException when the driver cannot connect
     */
    public DbSession connect(ConnectionProfile profile, String password) throws SQLException {
        ConnectionProfile work = profile.copy();
        if (password != null) {
            work.setPassword(password);
        }
        if (work.getDriverClass() == null || work.getDriverClass().isBlank()) {
            drivers.findById(work.getDriverId())
                    .map(DbDriver::getDriverClass)
                    .filter(c -> !c.isBlank())
                    .ifPresent(work::setDriverClass);
        }
        Connection conn = provider.open(work, settings);
        DbSession session = new DbSession(conn, work, settings);
        sessions.put(work.getId(), session);
        return session;
    }

    /**
     * Returns the open session for a profile.
     *
     * @param profileId the profile id
     * @return the session, or {@code null} when not connected
     */
    public DbSession getSession(String profileId) {
        DbSession s = sessions.get(profileId);
        if (s != null && !s.isConnected()) {
            sessions.remove(profileId);
            return null;
        }
        return s;
    }

    /**
     * Reports whether a profile currently has an open session.
     *
     * @param profileId the profile id
     * @return {@code true} when connected
     */
    public boolean isConnected(String profileId) {
        return getSession(profileId) != null;
    }

    /**
     * Closes and forgets a profile's session.
     *
     * @param profileId the profile id
     */
    public void disconnect(String profileId) {
        DbSession s = sessions.remove(profileId);
        if (s != null) {
            s.close();
        }
    }

    /** @return all currently open sessions. */
    public Collection<DbSession> getOpenSessions() {
        return List.copyOf(sessions.values());
    }

    /** Persists new application settings and adopts them for future connections. */
    public void saveSettings(AppSettings newSettings) {
        this.settings = (newSettings != null) ? newSettings : new AppSettings();
        store.saveSettings(this.settings);
    }

    /** Registers and persists a user-added custom driver. */
    public void addCustomDriver(DbDriver driver) {
        drivers.addCustomDriver(driver);
        store.saveCustomDrivers(drivers.getCustomDrivers());
    }

    /** Removes and un-persists a custom driver. */
    public void removeCustomDriver(String id) {
        if (drivers.removeCustomDriver(id)) {
            store.saveCustomDrivers(drivers.getCustomDrivers());
        }
    }

    /** Closes every open session; called when the panel is disposed. */
    public void shutdown() {
        for (DbSession s : sessions.values()) {
            s.close();
        }
        sessions.clear();
    }
}
