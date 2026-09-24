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

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;
import org.jdesktop.lg3d.dbmanager.model.AppSettings;
import org.jdesktop.lg3d.dbmanager.model.ConnectionProfile;
import org.jdesktop.lg3d.dbmanager.model.DbDriver;
import org.jdesktop.lg3d.dbmanager.model.DriverRegistry;

/**
 * Opens, tests and closes JDBC {@link Connection}s from a
 * {@link ConnectionProfile}, independent of any particular database.
 *
 * <p>Drivers already on the classpath (the bundled SQLite/H2/PostgreSQL/MariaDB)
 * are used directly. A driver that is not on the classpath but has an external
 * jar (a user-added {@link DbDriver}) is loaded through a cached
 * {@link URLClassLoader} and registered with {@link DriverManager} via a shim,
 * so the "add custom driver" path works without relaunching the desktop.</p>
 *
 * <p>All calls are blocking and must be made off the Swing EDT.</p>
 */
public final class ConnectionProvider {

    private final DriverRegistry drivers;
    private final Map<String, ClassLoader> jarLoaders = new HashMap<>();
    private final Set<String> registeredClasses = new HashSet<>();

    /** Creates a provider that can only use classpath drivers. */
    public ConnectionProvider() {
        this(null);
    }

    /**
     * Creates a provider that resolves external driver jars through a registry.
     *
     * @param drivers the driver registry (may be {@code null})
     */
    public ConnectionProvider(DriverRegistry drivers) {
        this.drivers = drivers;
    }

    /**
     * Opens a connection.
     *
     * @param profile  the connection profile; must have a JDBC URL
     * @param settings the app settings supplying the login timeout (may be {@code null})
     * @return an open connection with the profile's auto-commit mode applied
     * @throws SQLException when the URL is missing or the driver cannot connect
     */
    public Connection open(ConnectionProfile profile, AppSettings settings) throws SQLException {
        Objects.requireNonNull(profile, "profile");
        String url = profile.getJdbcUrl();
        if (url == null || url.isBlank()) {
            throw new SQLException("No JDBC URL configured for this connection");
        }
        ensureDriverLoaded(profile);

        Properties props = new Properties();
        if (profile.getUser() != null && !profile.getUser().isEmpty()) {
            props.setProperty("user", profile.getUser());
        }
        if (profile.getPassword() != null) {
            props.setProperty("password", profile.getPassword());
        }
        if (profile.getProperties() != null) {
            props.putAll(profile.getProperties());
        }

        int timeout = (settings != null) ? settings.getConnectTimeoutSeconds() : 0;
        if (timeout > 0) {
            DriverManager.setLoginTimeout(timeout);
        }

        Connection conn = DriverManager.getConnection(url, props);
        try {
            conn.setAutoCommit(profile.isAutoCommit());
        } catch (SQLException e) {
            // Some drivers reject toggling auto-commit; keep the connection.
            conn.close();
            throw e;
        }
        return conn;
    }

    /**
     * Tests a profile by opening and immediately closing a connection.
     *
     * @param profile  the profile to test
     * @param settings the app settings (may be {@code null})
     * @return the outcome, including server product info on success
     */
    public TestResult test(ConnectionProfile profile, AppSettings settings) {
        long start = System.currentTimeMillis();
        try (Connection conn = open(profile, settings)) {
            DatabaseMetaData md = conn.getMetaData();
            String product = md.getDatabaseProductName() + " " + md.getDatabaseProductVersion();
            long ms = System.currentTimeMillis() - start;
            return new TestResult(true, "Connected to " + product + " in " + ms + " ms", null);
        } catch (SQLException e) {
            String msg = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
            return new TestResult(false, msg, e);
        }
    }

    /**
     * The outcome of a {@link #test} call.
     *
     * @param success whether the connection succeeded
     * @param message a human-readable summary or the driver error
     * @param error   the caught exception, or {@code null} on success
     */
    public record TestResult(boolean success, String message, SQLException error) {
    }

    // ------------------------------------------------------------------
    // driver loading
    // ------------------------------------------------------------------

    private void ensureDriverLoaded(ConnectionProfile profile) throws SQLException {
        String className = profile.getDriverClass();
        if (className == null || className.isBlank()) {
            // Let DriverManager resolve the driver from the URL (service loader).
            return;
        }
        ClassLoader loader = loaderFor(profile);
        try {
            Class<?> clazz = Class.forName(className, true, loader);
            // A driver loaded from an external jar is invisible to DriverManager's
            // caller-classloader check, so register a shim once per class.
            if (loader != getClass().getClassLoader()
                    && loader != Thread.currentThread().getContextClassLoader()
                    && registeredClasses.add(className)) {
                Object instance = clazz.getDeclaredConstructor().newInstance();
                if (instance instanceof Driver driver) {
                    DriverManager.registerDriver(new DriverShim(driver));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new SQLException("Could not load JDBC driver class " + className
                    + ": " + e.getMessage(), e);
        }
    }

    private ClassLoader loaderFor(ConnectionProfile profile) {
        String jarPath = null;
        if (drivers != null) {
            DbDriver d = drivers.findById(profile.getDriverId()).orElse(null);
            if (d != null) {
                jarPath = d.getJarPath();
            }
        }
        if (jarPath == null || jarPath.isBlank()) {
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            return (ctx != null) ? ctx : getClass().getClassLoader();
        }
        return jarLoaders.computeIfAbsent(jarPath, p -> {
            try {
                URL url = Paths.get(p).toUri().toURL();
                return new URLClassLoader(new URL[]{url}, getClass().getClassLoader());
            } catch (Exception e) {
                return getClass().getClassLoader();
            }
        });
    }

    /**
     * Wraps a driver loaded from an external classloader so it can be registered
     * with {@link DriverManager} (which otherwise rejects foreign-classloader
     * drivers on the caller-classloader check).
     */
    private static final class DriverShim implements Driver {
        private final Driver delegate;

        DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }
}
