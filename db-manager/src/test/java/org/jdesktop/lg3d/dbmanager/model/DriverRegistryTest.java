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
package org.jdesktop.lg3d.dbmanager.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link DriverRegistry}: the fixed built-in set, custom-driver
 * add/replace/remove, the guard against shadowing a built-in id and lookup.
 */
class DriverRegistryTest {

    @Test
    @DisplayName("the built-in set ships SQLite, H2, PostgreSQL, MariaDB and Generic")
    void builtInDrivers() {
        DriverRegistry r = new DriverRegistry();
        List<String> ids = r.getBuiltInDrivers().stream().map(DbDriver::getId).toList();
        assertThat(ids).containsExactly("sqlite", "h2", "postgresql", "mariadb",
                DriverRegistry.GENERIC_ID);
        assertThat(r.getCustomDrivers()).isEmpty();
        assertThat(r.getDrivers()).hasSize(5);
    }

    @Test
    @DisplayName("findById locates built-in and returns empty for unknown/null")
    void findById() {
        DriverRegistry r = new DriverRegistry();
        assertThat(r.findById("h2")).isPresent().get()
                .extracting(DbDriver::getDriverClass).isEqualTo("org.h2.Driver");
        assertThat(r.findById("nope")).isEmpty();
        assertThat(r.findById(null)).isEmpty();
    }

    @Test
    @DisplayName("a custom driver is added, listed after built-ins and findable")
    void addCustomDriver() {
        DriverRegistry r = new DriverRegistry();
        DbDriver custom = new DbDriver("oracle", "Oracle", "oracle.jdbc.OracleDriver",
                "jdbc:oracle:thin:@//h:1521/svc", false, true, "/tmp/ojdbc.jar");
        r.addCustomDriver(custom);

        assertThat(r.getCustomDrivers()).containsExactly(custom);
        assertThat(r.getDrivers()).hasSize(6);
        assertThat(r.getDrivers().get(r.getDrivers().size() - 1).getId()).isEqualTo("oracle");
        assertThat(r.findById("oracle")).contains(custom);
    }

    @Test
    @DisplayName("add replaces an existing custom id and ignores null / built-in ids")
    void addReplacesAndGuards() {
        DriverRegistry r = new DriverRegistry();
        r.addCustomDriver(new DbDriver("x", "X1", "c1", "", false, true, null));
        r.addCustomDriver(new DbDriver("x", "X2", "c2", "", false, true, null));
        assertThat(r.getCustomDrivers()).hasSize(1);
        assertThat(r.findById("x")).get().extracting(DbDriver::getLabel).isEqualTo("X2");

        r.addCustomDriver(null);
        // A built-in id must not be shadowed by a custom entry.
        r.addCustomDriver(new DbDriver("h2", "Fake H2", "evil", "", false, true, null));
        assertThat(r.getCustomDrivers()).hasSize(1);
        assertThat(r.findById("h2")).get().extracting(DbDriver::getDriverClass)
                .isEqualTo("org.h2.Driver");
    }

    @Test
    @DisplayName("removeCustomDriver reports whether anything was removed")
    void removeCustomDriver() {
        DriverRegistry r = new DriverRegistry();
        r.addCustomDriver(new DbDriver("x", "X", "c", "", false, true, null));
        assertThat(r.removeCustomDriver("x")).isTrue();
        assertThat(r.removeCustomDriver("x")).isFalse();
        assertThat(r.removeCustomDriver("h2")).isFalse();
    }

    @Test
    @DisplayName("setCustomDrivers replaces the whole set and tolerates null")
    void setCustomDrivers() {
        DriverRegistry r = new DriverRegistry();
        r.setCustomDrivers(List.of(
                new DbDriver("a", "A", "ca", "", false, true, null),
                new DbDriver("b", "B", "cb", "", false, true, null)));
        assertThat(r.getCustomDrivers()).hasSize(2);

        r.setCustomDrivers(null);
        assertThat(r.getCustomDrivers()).isEmpty();
        assertThat(r.getDrivers()).hasSize(5);
    }

    @Test
    @DisplayName("getCustomDrivers returns a defensive copy")
    void customDriversAreCopied() {
        DriverRegistry r = new DriverRegistry();
        r.addCustomDriver(new DbDriver("x", "X", "c", "", false, true, null));
        List<DbDriver> copy = r.getCustomDrivers();
        copy.clear();
        assertThat(r.getCustomDrivers()).hasSize(1);
    }
}
