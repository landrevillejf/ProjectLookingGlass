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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdesktop.lg3d.dbmanager.jdbc.DmlBuilder.PreparedSql;
import org.jdesktop.lg3d.dbmanager.jdbc.DmlBuilder.TableRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link DmlBuilder}: parameterized INSERT/UPDATE/DELETE generation,
 * identifier quoting rules, primary-key WHERE assembly (including a NULL key)
 * and the guard that rejects editing a keyless table.
 */
class DmlBuilderTest {

    private static MetadataReader.ColumnInfo col(String name, int ordinal) {
        return new MetadataReader.ColumnInfo(name, "VARCHAR", 64, 0, true, false, null, ordinal);
    }

    @Test
    @DisplayName("quote leaves simple identifiers bare and double-quotes the rest")
    void quoteRules() {
        assertThat(DmlBuilder.quote("users")).isEqualTo("users");
        assertThat(DmlBuilder.quote("_t1")).isEqualTo("_t1");
        assertThat(DmlBuilder.quote("order")).isEqualTo("order");
        assertThat(DmlBuilder.quote("my table")).isEqualTo("\"my table\"");
        assertThat(DmlBuilder.quote("we\"ird")).isEqualTo("\"we\"\"ird\"");
        assertThat(DmlBuilder.quote(null)).isEmpty();
    }

    @Test
    @DisplayName("qualify prefixes the schema when present")
    void qualifyRules() {
        assertThat(DmlBuilder.qualify(new TableRef(null, null, "t"))).isEqualTo("t");
        assertThat(DmlBuilder.qualify(new TableRef(null, "public", "t")))
                .isEqualTo("public.t");
        assertThat(DmlBuilder.qualify(new TableRef(null, "my schema", "my table")))
                .isEqualTo("\"my schema\".\"my table\"");
    }

    @Test
    @DisplayName("insert lists only the columns present in the value map, in column order")
    void insert() {
        DmlBuilder b = new DmlBuilder();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", 1);
        values.put("name", "Ada");
        // 'extra' is not among the declared columns and must be skipped.
        values.put("extra", "ignored");

        PreparedSql ps = b.insert(new TableRef(null, null, "people"),
                List.of(col("id", 1), col("name", 2)), values);
        assertThat(ps.sql()).isEqualTo("INSERT INTO people (id, name) VALUES (?, ?)");
        assertThat(ps.params()).containsExactly(1, "Ada");
    }

    @Test
    @DisplayName("update sets the changed columns and keys on the primary key")
    void update() {
        DmlBuilder b = new DmlBuilder();
        Set<String> pk = new LinkedHashSet<>(List.of("id"));
        Map<String, Object> pkValues = new LinkedHashMap<>();
        pkValues.put("id", 7);
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("name", "Grace");
        newValues.put("id", 999); // PK in the new values is ignored

        PreparedSql ps = b.update(new TableRef(null, "public", "people"),
                pk, pkValues, newValues);
        assertThat(ps.sql()).isEqualTo("UPDATE public.people SET name = ? WHERE id = ?");
        assertThat(ps.params()).containsExactly("Grace", 7);
    }

    @Test
    @DisplayName("update with no non-PK change throws")
    void updateNothingToChange() {
        DmlBuilder b = new DmlBuilder();
        Set<String> pk = new LinkedHashSet<>(List.of("id"));
        Map<String, Object> pkValues = Map.of("id", 1);
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("id", 2); // only the PK -> nothing to set
        assertThatThrownBy(() -> b.update(new TableRef(null, null, "t"), pk, pkValues, newValues))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No columns to update");
    }

    @Test
    @DisplayName("delete keys on a composite primary key")
    void deleteCompositeKey() {
        DmlBuilder b = new DmlBuilder();
        Set<String> pk = new LinkedHashSet<>(List.of("a", "b"));
        Map<String, Object> pkValues = new LinkedHashMap<>();
        pkValues.put("a", 1);
        pkValues.put("b", 2);

        PreparedSql ps = b.delete(new TableRef(null, null, "t"), pk, pkValues);
        assertThat(ps.sql()).isEqualTo("DELETE FROM t WHERE a = ? AND b = ?");
        assertThat(ps.params()).containsExactly(1, 2);
    }

    @Test
    @DisplayName("a NULL primary-key value renders IS NULL with no bind parameter")
    void deleteNullKey() {
        DmlBuilder b = new DmlBuilder();
        Set<String> pk = new LinkedHashSet<>(List.of("id"));
        Map<String, Object> pkValues = new LinkedHashMap<>();
        pkValues.put("id", null);

        PreparedSql ps = b.delete(new TableRef(null, null, "t"), pk, pkValues);
        assertThat(ps.sql()).isEqualTo("DELETE FROM t WHERE id IS NULL");
        assertThat(ps.params()).isEmpty();
    }

    @Test
    @DisplayName("update and delete reject a table with no primary key")
    void requirePrimaryKey() {
        DmlBuilder b = new DmlBuilder();
        TableRef t = new TableRef(null, null, "t");
        assertThatThrownBy(() -> b.delete(t, Set.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no primary key");
        assertThatThrownBy(() -> b.update(t, null, Map.of(), Map.of("a", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no primary key");
    }
}
