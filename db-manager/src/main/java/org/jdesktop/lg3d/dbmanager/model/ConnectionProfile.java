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
package org.jdesktop.lg3d.dbmanager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A saved JDBC connection profile: everything needed to reach one database.
 *
 * <p>This is a plain Jackson-serializable bean (no-arg constructor plus
 * getters/setters) persisted by {@link ProfileStore} as JSON. It deliberately
 * carries no live {@link java.sql.Connection}; opening one is the job of
 * {@code ConnectionProvider}, so a profile is cheap to create, copy, edit and
 * store.</p>
 *
 * <p>The {@code password} is optional and, by default, not persisted: a profile
 * keeps it only when {@link #isSavePassword()} is {@code true}, in which case
 * {@link ProfileStore} stores an obfuscated (not strongly encrypted) form. The
 * {@code id} is a stable identifier assigned on construction so profiles can be
 * renamed without losing their identity.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectionProfile {

    /** Default maximum number of rows fetched for a query result. */
    public static final int DEFAULT_ROW_LIMIT = 1000;

    private String id;
    private String name = "";
    private String driverId = "";
    private String jdbcUrl = "";
    private String driverClass = "";
    private String user = "";
    private String password;
    private boolean savePassword;
    private boolean autoCommit = true;
    private int rowLimit = DEFAULT_ROW_LIMIT;
    private Map<String, String> properties = new LinkedHashMap<>();

    /** Creates a profile with a fresh random id. */
    public ConnectionProfile() {
        this.id = UUID.randomUUID().toString();
    }

    /**
     * Creates a profile with the given display name and JDBC URL.
     *
     * @param name     human-readable label shown in the navigator
     * @param jdbcUrl  the JDBC connection URL
     */
    public ConnectionProfile(String name, String jdbcUrl) {
        this();
        this.name = Objects.requireNonNullElse(name, "");
        this.jdbcUrl = Objects.requireNonNullElse(jdbcUrl, "");
    }

    /** @return the stable identifier, never {@code null}. */
    public String getId() {
        return id;
    }

    /** Restores the id (used by Jackson); regenerates one when absent. */
    public void setId(String id) {
        this.id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = Objects.requireNonNullElse(name, "");
    }

    /** @return the {@link DbDriver#getId()} this profile targets. */
    public String getDriverId() {
        return driverId;
    }

    public void setDriverId(String driverId) {
        this.driverId = Objects.requireNonNullElse(driverId, "");
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = Objects.requireNonNullElse(jdbcUrl, "");
    }

    /**
     * @return the fully-qualified JDBC driver class, or empty to let
     *         {@code DriverManager} resolve it from the URL.
     */
    public String getDriverClass() {
        return driverClass;
    }

    public void setDriverClass(String driverClass) {
        this.driverClass = Objects.requireNonNullElse(driverClass, "");
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = Objects.requireNonNullElse(user, "");
    }

    /** @return the password, or {@code null} when it is not stored. */
    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /** @return {@code true} to persist the (obfuscated) password. */
    public boolean isSavePassword() {
        return savePassword;
    }

    public void setSavePassword(boolean savePassword) {
        this.savePassword = savePassword;
    }

    /** @return {@code true} to open the connection in auto-commit mode. */
    public boolean isAutoCommit() {
        return autoCommit;
    }

    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    /** @return the maximum number of rows fetched per query; {@code <=0} = no cap. */
    public int getRowLimit() {
        return rowLimit;
    }

    public void setRowLimit(int rowLimit) {
        this.rowLimit = rowLimit;
    }

    /** @return extra JDBC connection properties (never {@code null}). */
    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = (properties == null) ? new LinkedHashMap<>() : properties;
    }

    /** @return a defensive copy of this profile. */
    public ConnectionProfile copy() {
        ConnectionProfile c = new ConnectionProfile();
        c.id = this.id;
        c.name = this.name;
        c.driverId = this.driverId;
        c.jdbcUrl = this.jdbcUrl;
        c.driverClass = this.driverClass;
        c.user = this.user;
        c.password = this.password;
        c.savePassword = this.savePassword;
        c.autoCommit = this.autoCommit;
        c.rowLimit = this.rowLimit;
        c.properties = new LinkedHashMap<>(this.properties);
        return c;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConnectionProfile other)) {
            return false;
        }
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "ConnectionProfile[" + name + " -> " + jdbcUrl + "]";
    }
}
