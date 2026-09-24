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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.jdesktop.lg3d.dbmanager.jdbc.QueryResult;
import org.jdesktop.lg3d.dbmanager.model.AppSettings;
import org.jdesktop.lg3d.dbmanager.model.ConnectionProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link DbSession} against a real H2 in-memory connection: query and
 * script execution, transaction control (auto-commit toggle, commit, rollback),
 * server description, the null-argument guards and idempotent close.
 */
class DbSessionTest {

    private static ConnectionProfile h2Profile(String db) {
        ConnectionProfile p = new ConnectionProfile("h2", "jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1");
        p.setDriverId("h2");
        p.setUser("sa");
        return p;
    }

    private static DbSession open(String db, ConnectionProfile p) throws SQLException {
        Connection c = DriverManager.getConnection(p.getJdbcUrl(), "sa", "");
        return new DbSession(c, p, new AppSettings());
    }

    @Test
    @DisplayName("exposes the connection, profile, settings and helpers")
    void exposesCollaborators() throws SQLException {
        ConnectionProfile p = h2Profile("sess_collab");
        try (DbSession s = open("sess_collab", p)) {
            assertThat(s.getConnection()).isNotNull();
            assertThat(s.getProfile()).isSameAs(p);
            assertThat(s.getSettings()).isNotNull();
            assertThat(s.getExecutor()).isNotNull();
            assertThat(s.getMetadata()).isNotNull();
            assertThat(s.isConnected()).isTrue();
        }
    }

    @Test
    @DisplayName("execute and executeScript return results")
    void executesQueries() throws SQLException {
        try (DbSession s = open("sess_exec", h2Profile("sess_exec"))) {
            QueryResult one = s.execute("SELECT 1");
            assertThat(one.isSuccess()).isTrue();
            assertThat(one.getRowCount()).isEqualTo(1);

            List<QueryResult> many = s.executeScript(
                    "CREATE TABLE t (a INT); INSERT INTO t VALUES (1); SELECT a FROM t;");
            assertThat(many).hasSize(3);
            assertThat(many.get(2).getRowCount()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("transaction control toggles auto-commit and commits/rolls back")
    void transactionControl() throws SQLException {
        try (DbSession s = open("sess_tx", h2Profile("sess_tx"))) {
            assertThat(s.isAutoCommit()).isTrue();
            s.execute("CREATE TABLE t (a INT)");
            s.setAutoCommit(false);
            assertThat(s.isAutoCommit()).isFalse();

            s.execute("INSERT INTO t VALUES (1)");
            s.rollback();
            QueryResult afterRollback = s.execute("SELECT COUNT(*) FROM t");
            assertThat(((Number) afterRollback.getRows().get(0).get(0)).intValue()).isZero();

            s.execute("INSERT INTO t VALUES (2)");
            s.commit();
            QueryResult afterCommit = s.execute("SELECT COUNT(*) FROM t");
            assertThat(((Number) afterCommit.getRows().get(0).get(0)).intValue()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("describeServer reports the H2 product")
    void describeServer() throws SQLException {
        try (DbSession s = open("sess_desc", h2Profile("sess_desc"))) {
            assertThat(s.describeServer()).contains("H2");
        }
    }

    @Test
    @DisplayName("close disconnects and is idempotent; state reads degrade safely")
    void closeIsIdempotent() throws SQLException {
        DbSession s = open("sess_close", h2Profile("sess_close"));
        assertThat(s.isConnected()).isTrue();
        s.close();
        assertThat(s.isConnected()).isFalse();
        s.close(); // second close must not throw
        // After close, an auto-commit read catches the SQLException and defaults true.
        assertThat(s.isAutoCommit()).isTrue();
        assertThat(s.describeServer()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("cancel delegates to the executor without throwing when idle")
    void cancelWhenIdle() throws SQLException {
        try (DbSession s = open("sess_cancel", h2Profile("sess_cancel"))) {
            s.cancel();
            assertThat(s.getExecutor().isCancelled()).isTrue();
        }
    }

    @Test
    @DisplayName("null connection or profile is rejected; null settings uses defaults")
    void nullArguments() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:h2:mem:sess_null;DB_CLOSE_DELAY=-1", "sa", "");
        ConnectionProfile p = h2Profile("sess_null");
        assertThatThrownBy(() -> new DbSession(null, p, new AppSettings()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DbSession(c, null, new AppSettings()))
                .isInstanceOf(NullPointerException.class);
        DbSession s = new DbSession(c, p, null);
        assertThat(s.getSettings()).isNotNull();
        s.close();
    }
}
