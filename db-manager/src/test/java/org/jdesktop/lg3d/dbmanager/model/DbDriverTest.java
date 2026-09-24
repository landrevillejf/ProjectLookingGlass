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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the immutable {@link DbDriver} value object: the {@code builtin}
 * factory, null-coalescing defaults, id-based equality and the required id.
 */
class DbDriverTest {

    @Test
    @DisplayName("builtin factory yields a non-custom classpath driver")
    void builtinFactory() {
        DbDriver d = DbDriver.builtin("sqlite", "SQLite",
                "org.sqlite.JDBC", "jdbc:sqlite:/x.db", true);
        assertThat(d.getId()).isEqualTo("sqlite");
        assertThat(d.getLabel()).isEqualTo("SQLite");
        assertThat(d.getDriverClass()).isEqualTo("org.sqlite.JDBC");
        assertThat(d.getUrlTemplate()).isEqualTo("jdbc:sqlite:/x.db");
        assertThat(d.isEmbedded()).isTrue();
        assertThat(d.isCustom()).isFalse();
        assertThat(d.getJarPath()).isNull();
        assertThat(d.toString()).isEqualTo("SQLite");
    }

    @Test
    @DisplayName("nulls coalesce: label->id, class/url->empty")
    void nullsCoalesce() {
        DbDriver d = new DbDriver("myid", null, null, null, false, true, "/tmp/d.jar");
        assertThat(d.getLabel()).isEqualTo("myid");
        assertThat(d.getDriverClass()).isEmpty();
        assertThat(d.getUrlTemplate()).isEmpty();
        assertThat(d.isCustom()).isTrue();
        assertThat(d.getJarPath()).isEqualTo("/tmp/d.jar");
    }

    @Test
    @DisplayName("id is required")
    void idIsRequired() {
        assertThatThrownBy(() -> new DbDriver(null, "x", "c", "u", false, false, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("equality and hashCode are by id")
    void equalityById() {
        DbDriver a = DbDriver.builtin("h2", "H2", "org.h2.Driver", "jdbc:h2:~/t", true);
        DbDriver b = new DbDriver("h2", "Different label", "other.Class", "", false, true, "/j.jar");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(DbDriver.builtin("sqlite", "SQLite", "", "", true));
        assertThat(a).isNotEqualTo("h2");
        assertThat(a).isEqualTo(a);
    }
}
