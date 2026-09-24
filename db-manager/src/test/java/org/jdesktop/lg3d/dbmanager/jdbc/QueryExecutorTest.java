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
package org.jdesktop.lg3d.dbmanager.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.jdesktop.lg3d.dbmanager.model.AppSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link QueryExecutor} against a real H2 in-memory database: result
 * sets, update counts, error capture, the hard row cap (with truncation
 * detection), multi-statement scripts that abort on the first failure, and the
 * cancel/reset flag bookkeeping.
 */
class QueryExecutorTest {

    private static final String URL = "jdbc:h2:mem:exec;DB_CLOSE_DELAY=-1";

    private Connection conn;
    private QueryExecutor executor;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection(URL, "sa", "");
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS nums");
            st.execute("DROP TABLE IF EXISTS tmp");
            st.execute("CREATE TABLE nums (n INT)");
            for (int i = 1; i <= 5; i++) {
                st.execute("INSERT INTO nums VALUES (" + i + ")");
            }
        }
        AppSettings settings = new AppSettings();
        settings.setMaxRows(0); // no global cap; each test passes its own limit
        settings.setQueryTimeoutSeconds(0);
        executor = new QueryExecutor(settings);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    @DisplayName("a SELECT yields a result set with columns and rows")
    void executesSelect() {
        QueryResult r = executor.execute(conn, "SELECT n FROM nums ORDER BY n", 0);
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.hasResultSet()).isTrue();
        assertThat(r.getColumns()).hasSize(1);
        assertThat(r.getColumns().get(0).name()).isEqualTo("N");
        assertThat(r.getRowCount()).isEqualTo(5);
        assertThat(r.isTruncated()).isFalse();
    }

    @Test
    @DisplayName("a row limit caps the result and flags truncation")
    void rowLimitTruncates() {
        QueryResult r = executor.execute(conn, "SELECT n FROM nums ORDER BY n", 3);
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getRowCount()).isEqualTo(3);
        assertThat(r.isTruncated()).isTrue();
    }

    @Test
    @DisplayName("a row limit larger than the table does not flag truncation")
    void rowLimitAboveRowCount() {
        QueryResult r = executor.execute(conn, "SELECT n FROM nums", 100);
        assertThat(r.getRowCount()).isEqualTo(5);
        assertThat(r.isTruncated()).isFalse();
    }

    @Test
    @DisplayName("DML yields an update count, not a result set")
    void executesUpdate() {
        QueryResult r = executor.execute(conn, "UPDATE nums SET n = n + 10", 0);
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.hasResultSet()).isFalse();
        assertThat(r.getUpdateCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("a SQL error is captured, not thrown")
    void capturesError() {
        QueryResult r = executor.execute(conn, "SELECT * FROM no_such_table", 0);
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getErrorMessage()).isNotBlank();
    }

    @Test
    @DisplayName("executeScript runs each statement in order")
    void executesScript() {
        List<QueryResult> results = executor.executeScript(conn,
                "CREATE TABLE tmp (a INT); INSERT INTO tmp VALUES (1); SELECT a FROM tmp;", 0);
        assertThat(results).hasSize(3);
        assertThat(results).allMatch(QueryResult::isSuccess);
        assertThat(results.get(2).hasResultSet()).isTrue();
        assertThat(results.get(2).getRowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("executeScript aborts at the first failing statement")
    void scriptAbortsOnError() {
        List<QueryResult> results = executor.executeScript(conn,
                "SELECT 1; SELECT * FROM missing_table; SELECT 2;", 0);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).isSuccess()).isTrue();
        assertThat(results.get(1).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("an empty script yields no results")
    void emptyScript() {
        assertThat(executor.executeScript(conn, "   ", 0)).isEmpty();
    }

    @Test
    @DisplayName("cancel/reset manage the cancelled flag")
    void cancelAndResetFlags() {
        assertThat(executor.isCancelled()).isFalse();
        executor.cancel();
        assertThat(executor.isCancelled()).isTrue();
        executor.reset();
        assertThat(executor.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("a null settings object falls back to defaults")
    void nullSettingsUsesDefaults() {
        QueryExecutor e = new QueryExecutor(null);
        QueryResult r = e.execute(conn, "SELECT n FROM nums", 2);
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getRowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("execute against a closed connection captures the failure")
    void closedConnectionCaptured() throws SQLException {
        Connection closed = DriverManager.getConnection("jdbc:h2:mem:closed;DB_CLOSE_DELAY=-1", "sa", "");
        closed.close();
        QueryResult r = executor.execute(closed, "SELECT 1", 0);
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getErrorMessage()).isNotBlank();
    }
}
