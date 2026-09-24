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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.jdesktop.lg3d.dbmanager.model.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs SQL against a live connection and captures the outcome into an immutable
 * {@link QueryResult}, applying a fetch size, a query timeout and a hard row cap.
 *
 * <p>One executor instance is bound to one logical query session: it remembers
 * the currently running {@link Statement} so {@link #cancel()} can interrupt it
 * from another thread (the UI's Stop button). All execute methods are blocking
 * and must run off the Swing EDT.</p>
 */
public final class QueryExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(QueryExecutor.class);

    private final AppSettings settings;
    private volatile Statement current;
    private volatile boolean cancelled;

    /**
     * Creates an executor.
     *
     * @param settings the app settings (fetch size, timeouts, row cap); may be {@code null}
     */
    public QueryExecutor(AppSettings settings) {
        this.settings = (settings != null) ? settings : new AppSettings();
    }

    /**
     * Executes a single statement.
     *
     * @param conn      an open connection
     * @param sql       the statement text
     * @param rowLimit  a per-query row cap; {@code <=0} falls back to the settings cap
     * @return the result (never {@code null}); a driver error is captured, not thrown
     */
    public QueryResult execute(Connection conn, String sql, int rowLimit) {
        long start = System.currentTimeMillis();
        int limit = resolveLimit(rowLimit);
        try (Statement st = conn.createStatement()) {
            current = st;
            configure(st, limit);
            boolean hasResultSet = st.execute(sql);
            long elapsed = System.currentTimeMillis() - start;
            if (hasResultSet) {
                try (ResultSet rs = st.getResultSet()) {
                    return readResultSet(sql, rs, limit, elapsed);
                }
            }
            return QueryResult.ofUpdate(sql, st.getUpdateCount(), elapsed);
        } catch (SQLException e) {
            long elapsed = System.currentTimeMillis() - start;
            String msg = cancelled ? "Query cancelled" : describe(e);
            return QueryResult.ofError(sql, msg, elapsed);
        } finally {
            current = null;
        }
    }

    /**
     * Splits a script and executes each statement in order.
     *
     * @param conn     an open connection
     * @param script   one or more semicolon-separated statements
     * @param rowLimit the per-query row cap ({@code <=0} uses the settings cap)
     * @return one result per statement; execution stops early if cancelled
     */
    public List<QueryResult> executeScript(Connection conn, String script, int rowLimit) {
        List<QueryResult> results = new ArrayList<>();
        cancelled = false;
        for (String stmt : SqlStatementSplitter.split(script)) {
            if (cancelled) {
                break;
            }
            results.add(execute(conn, stmt, rowLimit));
            // A failing statement aborts the rest of the script, matching the
            // behaviour users expect from a SQL console.
            if (!results.get(results.size() - 1).isSuccess()) {
                break;
            }
        }
        return results;
    }

    /** Requests cancellation of the statement running on another thread. */
    public void cancel() {
        cancelled = true;
        Statement st = current;
        if (st != null) {
            try {
                st.cancel();
            } catch (SQLException e) {
                LOG.debug("Statement cancel reported an error", e);
            }
        }
    }

    /** Clears the cancelled flag before starting a new run. */
    public void reset() {
        cancelled = false;
    }

    /** @return {@code true} once {@link #cancel()} has been called. */
    public boolean isCancelled() {
        return cancelled;
    }

    private int resolveLimit(int rowLimit) {
        if (rowLimit > 0) {
            return rowLimit;
        }
        return Math.max(settings.getMaxRows(), 0);
    }

    private void configure(Statement st, int limit) {
        int fetchSize = settings.getFetchSize();
        if (fetchSize > 0) {
            try {
                st.setFetchSize(fetchSize);
            } catch (SQLException e) {
                LOG.debug("Driver rejected fetchSize={}", fetchSize, e);
            }
        }
        int timeout = settings.getQueryTimeoutSeconds();
        if (timeout > 0) {
            try {
                st.setQueryTimeout(timeout);
            } catch (SQLException e) {
                LOG.debug("Driver rejected queryTimeout={}", timeout, e);
            }
        }
        if (limit > 0) {
            try {
                // Fetch one extra row to detect that the cap truncated the result.
                st.setMaxRows(limit + 1);
            } catch (SQLException e) {
                LOG.debug("Driver rejected maxRows={}", limit + 1, e);
            }
        }
    }

    private QueryResult readResultSet(String sql, ResultSet rs, int limit, long elapsed)
            throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int colCount = md.getColumnCount();
        List<ColumnMeta> columns = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            columns.add(new ColumnMeta(
                    md.getColumnLabel(i),
                    md.getColumnTypeName(i),
                    md.getColumnType(i),
                    md.isNullable(i) != ResultSetMetaData.columnNoNulls,
                    md.getColumnClassName(i),
                    i));
        }
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (limit > 0 && rows.size() >= limit) {
                truncated = true;
                break;
            }
            List<Object> row = new ArrayList<>(colCount);
            for (int i = 1; i <= colCount; i++) {
                row.add(rs.getObject(i));
            }
            rows.add(row);
        }
        return QueryResult.ofResultSet(sql, columns, rows, truncated, elapsed);
    }

    private static String describe(SQLException e) {
        StringBuilder sb = new StringBuilder();
        if (e.getSQLState() != null) {
            sb.append('[').append(e.getSQLState()).append("] ");
        }
        sb.append(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        return sb.toString();
    }
}
