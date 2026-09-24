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
package org.jdesktop.lg3d.dbmanager.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.SQLException;
import org.jdesktop.lg3d.dbmanager.model.AppSettings;
import org.jdesktop.lg3d.dbmanager.model.ConnectionProfile;
import org.jdesktop.lg3d.dbmanager.model.DbDriver;
import org.jdesktop.lg3d.dbmanager.model.ProfileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link ConnectionManager} against a temp-dir {@link ProfileStore}
 * and a real H2 in-memory database: profile CRUD with persistence, connect /
 * disconnect / session lookup, driver-class resolution from the registry,
 * settings persistence, custom-driver persistence and shutdown.
 */
class ConnectionManagerTest {

    private ConnectionManager manager;
    private Path dir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.dir = tempDir;
        this.manager = new ConnectionManager(new ProfileStore(tempDir));
    }

    private static ConnectionProfile h2Profile(String name, String db) {
        ConnectionProfile p = new ConnectionProfile(name, "jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1");
        p.setDriverId("h2");
        p.setUser("sa");
        return p;
    }

    @Test
    @DisplayName("starts with no profiles and default settings")
    void emptyStart() {
        assertThat(manager.getProfiles()).isEmpty();
        assertThat(manager.getSettings()).isNotNull();
        assertThat(manager.getDrivers()).isNotNull();
        assertThat(manager.getStore().getConfigDir()).isEqualTo(dir);
    }

    @Test
    @DisplayName("addProfile persists and is findable by id")
    void addProfilePersists() {
        ConnectionProfile p = h2Profile("one", "cm_add");
        manager.addProfile(p);
        assertThat(manager.getProfiles()).hasSize(1);
        assertThat(manager.getProfile(p.getId())).contains(p);
        assertThat(manager.getProfile("nope")).isEmpty();

        // A fresh manager over the same dir reloads the profile.
        ConnectionManager reloaded = new ConnectionManager(new ProfileStore(dir));
        assertThat(reloaded.getProfiles()).hasSize(1);
        assertThat(reloaded.getProfiles().get(0).getName()).isEqualTo("one");
    }

    @Test
    @DisplayName("addProfile/updateProfile ignore null")
    void nullProfilesIgnored() {
        manager.addProfile(null);
        manager.updateProfile(null);
        assertThat(manager.getProfiles()).isEmpty();
    }

    @Test
    @DisplayName("updateProfile replaces by id and persists")
    void updateProfileReplaces() {
        ConnectionProfile p = h2Profile("before", "cm_upd");
        manager.addProfile(p);
        ConnectionProfile edited = p.copy();
        edited.setName("after");
        manager.updateProfile(edited);
        assertThat(manager.getProfiles()).hasSize(1);
        assertThat(manager.getProfile(p.getId()).orElseThrow().getName()).isEqualTo("after");
    }

    @Test
    @DisplayName("connect opens a session, getSession/isConnected report it, disconnect closes it")
    void connectAndDisconnect() throws SQLException {
        ConnectionProfile p = h2Profile("live", "cm_conn");
        manager.addProfile(p);

        DbSession session = manager.connect(p.getId(), null);
        assertThat(session).isNotNull();
        assertThat(session.isConnected()).isTrue();
        assertThat(manager.isConnected(p.getId())).isTrue();
        assertThat(manager.getSession(p.getId())).isSameAs(session);
        assertThat(manager.getOpenSessions()).hasSize(1);

        manager.disconnect(p.getId());
        assertThat(manager.isConnected(p.getId())).isFalse();
        assertThat(manager.getSession(p.getId())).isNull();
        assertThat(manager.getOpenSessions()).isEmpty();
    }

    @Test
    @DisplayName("connect resolves a blank driver class from the registry")
    void connectResolvesDriverClass() throws SQLException {
        ConnectionProfile p = h2Profile("nodriver", "cm_driver");
        p.setDriverClass(""); // must be filled from the h2 registry entry
        DbSession s = manager.connect(p, null);
        assertThat(s.isConnected()).isTrue();
        assertThat(s.getProfile().getDriverClass()).isEqualTo("org.h2.Driver");
        manager.shutdown();
    }

    @Test
    @DisplayName("connect with an explicit password overrides the stored one")
    void connectWithExplicitPassword() throws SQLException {
        ConnectionProfile p = h2Profile("pw", "cm_pw");
        p.setPassword("stored");
        DbSession s = manager.connect(p, "override");
        assertThat(s.isConnected()).isTrue();
        manager.shutdown();
    }

    @Test
    @DisplayName("connect on an unknown id throws IllegalArgumentException")
    void connectUnknownId() {
        assertThatThrownBy(() -> manager.connect("missing-id", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown profile");
    }

    @Test
    @DisplayName("connect surfaces a SQLException for an unreachable database")
    void connectFailure() {
        ConnectionProfile p = new ConnectionProfile("bad", "jdbc:nosuchdb:xyz");
        assertThatThrownBy(() -> manager.connect(p, null))
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("removeProfile disconnects an open session and unpersists")
    void removeProfileDisconnects() throws SQLException {
        ConnectionProfile p = h2Profile("gone", "cm_rm");
        manager.addProfile(p);
        manager.connect(p.getId(), null);
        assertThat(manager.isConnected(p.getId())).isTrue();

        manager.removeProfile(p.getId());
        assertThat(manager.getProfiles()).isEmpty();
        assertThat(manager.isConnected(p.getId())).isFalse();
    }

    @Test
    @DisplayName("getSession drops a session whose connection died")
    void getSessionDropsDeadConnection() throws SQLException {
        ConnectionProfile p = h2Profile("dies", "cm_dead");
        manager.addProfile(p);
        DbSession s = manager.connect(p.getId(), null);
        s.getConnection().close(); // simulate the connection dropping
        assertThat(manager.getSession(p.getId())).isNull();
        assertThat(manager.isConnected(p.getId())).isFalse();
    }

    @Test
    @DisplayName("saveSettings adopts and persists the new settings")
    void saveSettings() {
        AppSettings s = new AppSettings();
        s.setMaxRows(77);
        manager.saveSettings(s);
        assertThat(manager.getSettings().getMaxRows()).isEqualTo(77);

        ConnectionManager reloaded = new ConnectionManager(new ProfileStore(dir));
        assertThat(reloaded.getSettings().getMaxRows()).isEqualTo(77);

        manager.saveSettings(null);
        assertThat(manager.getSettings().getMaxRows()).isEqualTo(1000);
    }

    @Test
    @DisplayName("custom drivers are registered and persisted; removal unpersists")
    void customDrivers() {
        DbDriver d = new DbDriver("oracle", "Oracle", "oracle.jdbc.OracleDriver",
                "jdbc:oracle:thin:@//h:1521/s", false, true, null);
        manager.addCustomDriver(d);
        assertThat(manager.getDrivers().findById("oracle")).isPresent();

        ConnectionManager reloaded = new ConnectionManager(new ProfileStore(dir));
        assertThat(reloaded.getDrivers().findById("oracle")).isPresent();

        manager.removeCustomDriver("oracle");
        assertThat(manager.getDrivers().findById("oracle")).isEmpty();
        // Removing an unknown id is a no-op.
        manager.removeCustomDriver("nope");

        // The removal persisted an empty custom set.
        ConnectionManager reloaded2 = new ConnectionManager(new ProfileStore(dir));
        assertThat(reloaded2.getDrivers().findById("oracle")).isEmpty();
    }

    @Test
    @DisplayName("shutdown closes every open session")
    void shutdownClosesAll() throws SQLException {
        ConnectionProfile a = h2Profile("a", "cm_sd_a");
        ConnectionProfile b = h2Profile("b", "cm_sd_b");
        manager.addProfile(a);
        manager.addProfile(b);
        DbSession sa = manager.connect(a.getId(), null);
        DbSession sb = manager.connect(b.getId(), null);

        manager.shutdown();
        assertThat(sa.isConnected()).isFalse();
        assertThat(sb.isConnected()).isFalse();
        assertThat(manager.getOpenSessions()).isEmpty();
    }

    @Test
    @DisplayName("the no-arg constructor builds against the default store")
    void noArgConstructor() {
        String previous = System.getProperty(ProfileStore.DIR_PROPERTY);
        try {
            System.setProperty(ProfileStore.DIR_PROPERTY, dir.resolve("default").toString());
            ConnectionManager m = new ConnectionManager();
            assertThat(m.getProfiles()).isEmpty();
        } finally {
            if (previous == null) {
                System.clearProperty(ProfileStore.DIR_PROPERTY);
            } else {
                System.setProperty(ProfileStore.DIR_PROPERTY, previous);
            }
        }
    }
}
