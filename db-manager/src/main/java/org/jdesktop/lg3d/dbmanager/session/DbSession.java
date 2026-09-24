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
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import org.jdesktop.lg3d.dbmanager.jdbc.MetadataReader;
import org.jdesktop.lg3d.dbmanager.jdbc.QueryExecutor;
import org.jdesktop.lg3d.dbmanager.jdbc.QueryResult;
import org.jdesktop.lg3d.dbmanager.model.AppSettings;
import org.jdesktop.lg3d.dbmanager.model.ConnectionProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One live connection to one database plus the helpers that operate on it: the
 * query executor (with cancellation), the metadata reader, and transaction
 * control. The UI holds a {@code DbSession} per connected profile and calls its
 * methods from a background worker, never the EDT.
 *
 * <p>Closing the session closes the underlying {@link Connection}; failures while
 * closing are logged, not thrown, so a disconnect from the UI never surfaces a
 * spurious error.</p>
 */
public final class DbSession implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DbSession.class);

    private final ConnectionProfile profile;
    private final AppSettings settings;
    private final Connection connection;
    private final QueryExecutor executor;
    private final MetadataReader metadata = new MetadataReader();

    /**
     * Wraps an open connection.
     *
     * @param connection the live JDBC connection
     * @param profile    the profile it was opened from
     * @param settings   the app settings driving fetch/timeout/row caps
     */
    public DbSession(Connection connection, ConnectionProfile profile, AppSettings settings) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.settings = (settings != null) ? settings : new AppSettings();
        this.executor = new QueryExecutor(this.settings);
    }

    public Connection getConnection() {
        return connection;
    }

    public ConnectionProfile getProfile() {
        return profile;
    }

    public AppSettings getSettings() {
        return settings;
    }

    public QueryExecutor getExecutor() {
        return executor;
    }

    public MetadataReader getMetadata() {
        return metadata;
    }

    /** @return {@code true} while the connection is open. */
    public boolean isConnected() {
        try {
            return !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Executes a single statement against this connection.
     *
     * @param sql the statement
     * @return the result (never {@code null})
     */
    public QueryResult execute(String sql) {
        executor.reset();
        return executor.execute(connection, sql, profile.getRowLimit());
    }

    /**
     * Splits and executes a multi-statement script.
     *
     * @param script the SQL script
     * @return one result per statement
     */
    public List<QueryResult> executeScript(String script) {
        executor.reset();
        return executor.executeScript(connection, script, profile.getRowLimit());
    }

    /** Requests cancellation of the running statement. */
    public void cancel() {
        executor.cancel();
    }

    /** @return {@code true} when the connection is in auto-commit mode. */
    public boolean isAutoCommit() {
        try {
            return connection.getAutoCommit();
        } catch (SQLException e) {
            return true;
        }
    }

    /**
     * Sets the connection's auto-commit mode.
     *
     * @param autoCommit the new mode
     * @throws SQLException when the driver rejects the change
     */
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        connection.setAutoCommit(autoCommit);
    }

    /**
     * Commits the current transaction.
     *
     * @throws SQLException when not in manual mode or the commit fails
     */
    public void commit() throws SQLException {
        connection.commit();
    }

    /**
     * Rolls back the current transaction.
     *
     * @throws SQLException when not in manual mode or the rollback fails
     */
    public void rollback() throws SQLException {
        connection.rollback();
    }

    /**
     * Describes the connected server (product name and version).
     *
     * @return a short label, or {@code "unknown"} on error
     */
    public String describeServer() {
        try {
            DatabaseMetaData md = connection.getMetaData();
            return md.getDatabaseProductName() + " " + md.getDatabaseProductVersion();
        } catch (SQLException e) {
            return "unknown";
        }
    }

    @Override
    public void close() {
        try {
            if (!connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            LOG.debug("Error closing connection to {}", profile.getName(), e);
        }
    }
}
