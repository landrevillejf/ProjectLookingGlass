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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link ConnectionProfile} bean: default values, null-safe setters,
 * id stability/regeneration, defensive copy, id-based equality and Jackson
 * round-trip (the shape {@link ProfileStore} persists).
 */
class ConnectionProfileTest {

    @Test
    @DisplayName("a fresh profile has a stable id and sane defaults")
    void defaults() {
        ConnectionProfile p = new ConnectionProfile();
        assertThat(p.getId()).isNotBlank();
        assertThat(p.getName()).isEmpty();
        assertThat(p.getJdbcUrl()).isEmpty();
        assertThat(p.getDriverId()).isEmpty();
        assertThat(p.getDriverClass()).isEmpty();
        assertThat(p.getUser()).isEmpty();
        assertThat(p.getPassword()).isNull();
        assertThat(p.isSavePassword()).isFalse();
        assertThat(p.isAutoCommit()).isTrue();
        assertThat(p.getRowLimit()).isEqualTo(ConnectionProfile.DEFAULT_ROW_LIMIT);
        assertThat(p.getProperties()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("the two-arg constructor tolerates nulls")
    void namedConstructorToleratesNulls() {
        ConnectionProfile p = new ConnectionProfile(null, null);
        assertThat(p.getName()).isEmpty();
        assertThat(p.getJdbcUrl()).isEmpty();
        assertThat(p.getId()).isNotBlank();
    }

    @Test
    @DisplayName("null setters fall back to empty, not null")
    void nullSettersFallBackToEmpty() {
        ConnectionProfile p = new ConnectionProfile();
        p.setName(null);
        p.setJdbcUrl(null);
        p.setDriverId(null);
        p.setDriverClass(null);
        p.setUser(null);
        p.setProperties(null);
        assertThat(p.getName()).isEmpty();
        assertThat(p.getJdbcUrl()).isEmpty();
        assertThat(p.getDriverId()).isEmpty();
        assertThat(p.getDriverClass()).isEmpty();
        assertThat(p.getUser()).isEmpty();
        assertThat(p.getProperties()).isEmpty();
    }

    @Test
    @DisplayName("setId regenerates when null/blank, keeps a real id")
    void setIdBehaviour() {
        ConnectionProfile p = new ConnectionProfile();
        String original = p.getId();
        p.setId(null);
        assertThat(p.getId()).isNotBlank().isNotEqualTo(original);
        p.setId("   ");
        assertThat(p.getId()).isNotBlank();
        p.setId("fixed-id");
        assertThat(p.getId()).isEqualTo("fixed-id");
    }

    @Test
    @DisplayName("copy is deep on properties and equal by id")
    void copyIsDeepAndEqualById() {
        ConnectionProfile p = new ConnectionProfile("db", "jdbc:h2:mem:x");
        Map<String, String> props = new LinkedHashMap<>();
        props.put("k", "v");
        p.setProperties(props);
        p.setUser("sa");
        p.setRowLimit(42);

        ConnectionProfile c = p.copy();
        assertThat(c).isEqualTo(p).hasSameHashCodeAs(p);
        assertThat(c.getName()).isEqualTo("db");
        assertThat(c.getUser()).isEqualTo("sa");
        assertThat(c.getRowLimit()).isEqualTo(42);
        assertThat(c.getProperties()).containsEntry("k", "v");

        // Mutating the copy's map must not leak back into the original.
        c.getProperties().put("k", "changed");
        assertThat(p.getProperties()).containsEntry("k", "v");
    }

    @Test
    @DisplayName("equality is by id only")
    void equalityIsByIdOnly() {
        ConnectionProfile a = new ConnectionProfile("a", "jdbc:one");
        ConnectionProfile b = a.copy();
        b.setName("renamed");
        b.setJdbcUrl("jdbc:two");
        assertThat(b).isEqualTo(a);
        assertThat(a).isNotEqualTo(new ConnectionProfile("a", "jdbc:one"));
        assertThat(a).isNotEqualTo("not-a-profile");
        assertThat(a).isEqualTo(a);
    }

    @Test
    @DisplayName("toString mentions the name and url but not the password")
    void toStringIsSafe() {
        ConnectionProfile p = new ConnectionProfile("sales", "jdbc:h2:mem:sales");
        p.setPassword("hunter2");
        String s = p.toString();
        assertThat(s).contains("sales").contains("jdbc:h2:mem:sales");
        assertThat(s).doesNotContain("hunter2");
    }

    @Test
    @DisplayName("unknown JSON properties are ignored on deserialize")
    void unknownJsonPropertiesIgnored() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = "{\"id\":\"x1\",\"name\":\"n\",\"jdbcUrl\":\"jdbc:u\","
                + "\"totallyUnknownField\":123}";
        ConnectionProfile p = mapper.readValue(json, ConnectionProfile.class);
        assertThat(p.getId()).isEqualTo("x1");
        assertThat(p.getName()).isEqualTo("n");
        assertThat(p.getJdbcUrl()).isEqualTo("jdbc:u");
    }
}
