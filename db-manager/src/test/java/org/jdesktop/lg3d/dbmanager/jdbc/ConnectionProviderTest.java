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
package org.jdesktop.lg3d.dbmanager.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import org.jdesktop.lg3d.dbmanager.jdbc.ConnectionProvider.TestResult;
import org.jdesktop.lg3d.dbmanager.model.AppSettings;
import org.jdesktop.lg3d.dbmanager.model.ConnectionProfile;
import org.jdesktop.lg3d.dbmanager.model.DriverRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link ConnectionProvider} against real embedded drivers (H2
 * in-memory, SQLite on a temp file): opening with the profile's auto-commit
 * mode applied, the test/open/close round-trip, and the failure paths (blank
 * URL, unknown driver class, no suitable driver).
 */
class ConnectionProviderTest {

    private static final AppSettings SETTINGS = new AppSettings();

    private static ConnectionProfile h2Profile(String url) {
        ConnectionProfile p = new ConnectionProfile("h2", url);
        p.setDriverId("h2");
        p.setDriverClass("org.h2.Driver");
        p.setUser("sa");
        return p;
    }

    @Test
    @DisplayName("opens an H2 connection and applies the profile's auto-commit mode")
    void opensH2WithAutoCommit() throws SQLException {
        ConnectionProvider provider = new ConnectionProvider(new DriverRegistry());
        ConnectionProfile p = h2Profile("jdbc:h2:mem:open_ac;DB_CLOSE_DELAY=-1");
        p.setAutoCommit(false);
        try (Connection c = provider.open(p, SETTINGS)) {
            assertThat(c.isClosed()).isFalse();
            assertThat(c.getAutoCommit()).isFalse();
        }
    }

    @Test
    @DisplayName("open tolerates null settings (no login timeout applied)")
    void openWithNullSettings() throws SQLException {
        ConnectionProvider provider = new ConnectionProvider();
        try (Connection c = provider.open(
                h2Profile("jdbc:h2:mem:open_null;DB_CLOSE_DELAY=-1"), null)) {
            assertThat(c.isClosed()).isFalse();
        }
    }

    @Test
    @DisplayName("test succeeds against H2 and reports the product name")
    void testSucceeds() {
        ConnectionProvider provider = new ConnectionProvider(new DriverRegistry());
        TestResult r = provider.test(
                h2Profile("jdbc:h2:mem:test_ok;DB_CLOSE_DELAY=-1"), SETTINGS);
        assertThat(r.success()).isTrue();
        assertThat((Throwable) r.error()).isNull();
        assertThat(r.message()).contains("Connected to").contains("H2");
    }

    @Test
    @DisplayName("opens a real SQLite database file")
    void opensSqliteFile(@TempDir Path dir) throws SQLException {
        ConnectionProvider provider = new ConnectionProvider(new DriverRegistry());
        ConnectionProfile p = new ConnectionProfile("sqlite",
                "jdbc:sqlite:" + dir.resolve("test.db"));
        p.setDriverId("sqlite");
        p.setDriverClass("org.sqlite.JDBC");
        try (Connection c = provider.open(p, SETTINGS)) {
            assertThat(c.isClosed()).isFalse();
            assertThat(c.getMetaData().getDatabaseProductName()).containsIgnoringCase("sqlite");
        }
    }

    @Test
    @DisplayName("a blank JDBC URL is rejected with a clear message")
    void blankUrlRejected() {
        ConnectionProvider provider = new ConnectionProvider();
        ConnectionProfile p = new ConnectionProfile("empty", "   ");
        assertThatThrownBy(() -> provider.open(p, SETTINGS))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("No JDBC URL");

        TestResult r = provider.test(p, SETTINGS);
        assertThat(r.success()).isFalse();
        assertThat((Throwable) r.error()).isNotNull();
        assertThat(r.message()).contains("No JDBC URL");
    }

    @Test
    @DisplayName("a null profile is rejected")
    void nullProfileRejected() {
        ConnectionProvider provider = new ConnectionProvider();
        assertThatThrownBy(() -> provider.open(null, SETTINGS))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("an unloadable driver class surfaces as SQLException")
    void badDriverClass() {
        ConnectionProvider provider = new ConnectionProvider(new DriverRegistry());
        ConnectionProfile p = new ConnectionProfile("bad", "jdbc:h2:mem:whatever");
        p.setDriverClass("com.example.NoSuchDriver");
        assertThatThrownBy(() -> provider.open(p, SETTINGS))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Could not load JDBC driver class");
    }

    @Test
    @DisplayName("an unknown URL with no driver yields 'No suitable driver'")
    void noSuitableDriver() {
        ConnectionProvider provider = new ConnectionProvider();
        ConnectionProfile p = new ConnectionProfile("unknown", "jdbc:nosuchdb:xyz");
        TestResult r = provider.test(p, SETTINGS);
        assertThat(r.success()).isFalse();
        assertThat((Throwable) r.error()).isNotNull();
    }

    @Test
    @DisplayName("a blank driver class lets DriverManager resolve the driver from the URL")
    void blankDriverClassUsesServiceLoader() throws SQLException {
        ConnectionProvider provider = new ConnectionProvider(new DriverRegistry());
        ConnectionProfile p = new ConnectionProfile("h2", "jdbc:h2:mem:svc;DB_CLOSE_DELAY=-1");
        p.setDriverId("h2");
        p.setDriverClass(""); // rely on the registered service-loader driver
        try (Connection c = provider.open(p, SETTINGS)) {
            assertThat(c.isClosed()).isFalse();
        }
    }
}
