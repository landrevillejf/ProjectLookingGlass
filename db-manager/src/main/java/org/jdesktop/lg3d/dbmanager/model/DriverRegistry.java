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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The catalogue of JDBC drivers the Database Manager knows about: the built-in
 * drivers whose jars ship on the runtime classpath, plus any user-added custom
 * drivers.
 *
 * <p>The built-in set is fixed and always present. Custom drivers are mutable
 * and persisted by {@link ProfileStore} (loaded back on startup via
 * {@link #setCustomDrivers(List)}), so a user can reach a database whose driver
 * is not bundled by pointing at an external jar.</p>
 */
public final class DriverRegistry {

    /** Driver id for a hand-specified JDBC URL / class (no bundling). */
    public static final String GENERIC_ID = "generic";

    private final List<DbDriver> builtIn;
    private final List<DbDriver> custom = new ArrayList<>();

    /** Creates a registry seeded with the built-in drivers. */
    public DriverRegistry() {
        List<DbDriver> list = new ArrayList<>();
        list.add(DbDriver.builtin("sqlite", "SQLite",
                "org.sqlite.JDBC", "jdbc:sqlite:/path/to/database.db", true));
        list.add(DbDriver.builtin("h2", "H2 (embedded)",
                "org.h2.Driver", "jdbc:h2:~/test", true));
        list.add(DbDriver.builtin("postgresql", "PostgreSQL",
                "org.postgresql.Driver", "jdbc:postgresql://localhost:5432/database", false));
        list.add(DbDriver.builtin("mariadb", "MariaDB / MySQL",
                "org.mariadb.jdbc.Driver", "jdbc:mariadb://localhost:3306/database", false));
        list.add(DbDriver.builtin(GENERIC_ID, "Generic JDBC",
                "", "", false));
        this.builtIn = Collections.unmodifiableList(list);
    }

    /** @return built-in drivers followed by custom drivers (never {@code null}). */
    public List<DbDriver> getDrivers() {
        List<DbDriver> all = new ArrayList<>(builtIn);
        all.addAll(custom);
        return all;
    }

    /** @return the immutable built-in driver list. */
    public List<DbDriver> getBuiltInDrivers() {
        return builtIn;
    }

    /** @return a defensive copy of the custom (user-added) drivers. */
    public List<DbDriver> getCustomDrivers() {
        return new ArrayList<>(custom);
    }

    /**
     * Finds a driver by id.
     *
     * @param id the driver id
     * @return the matching driver, or empty when unknown
     */
    public Optional<DbDriver> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return getDrivers().stream()
                .filter(d -> d.getId().equals(id))
                .findFirst();
    }

    /**
     * Adds or replaces a custom driver (matched by id).
     *
     * @param driver the driver to register; ignored when {@code null} or built-in id
     */
    public void addCustomDriver(DbDriver driver) {
        if (driver == null || findByIdBuiltIn(driver.getId()).isPresent()) {
            return;
        }
        custom.removeIf(d -> d.getId().equals(driver.getId()));
        custom.add(driver);
    }

    /**
     * Removes a custom driver by id.
     *
     * @param id the driver id
     * @return {@code true} when a custom driver was removed
     */
    public boolean removeCustomDriver(String id) {
        return custom.removeIf(d -> d.getId().equals(id));
    }

    /**
     * Replaces the whole custom set (used when loading persisted drivers).
     *
     * @param drivers the custom drivers to install; {@code null} clears the set
     */
    public void setCustomDrivers(List<DbDriver> drivers) {
        custom.clear();
        if (drivers != null) {
            for (DbDriver d : drivers) {
                addCustomDriver(d);
            }
        }
    }

    private Optional<DbDriver> findByIdBuiltIn(String id) {
        return builtIn.stream().filter(d -> d.getId().equals(id)).findFirst();
    }
}
