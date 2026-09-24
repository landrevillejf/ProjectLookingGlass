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

import java.util.List;
import java.util.Set;
import org.jdesktop.lg3d.dbmanager.jdbc.DmlBuilder.TableRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link DdlGenerator} and the {@link MetadataReader.ColumnInfo}
 * {@code typeLabel} formatting: NOT NULL / DEFAULT clauses, the trailing
 * PRIMARY KEY constraint, sized vs unsized type labels and the no-PK case.
 */
class DdlGeneratorTest {

    private static MetadataReader.ColumnInfo col(String name, String type, int size, int digits,
                                                 boolean nullable, String def, int ordinal) {
        return new MetadataReader.ColumnInfo(name, type, size, digits, nullable, false, def, ordinal);
    }

    @Test
    @DisplayName("typeLabel adds a size for CHAR/DECIMAL types only")
    void typeLabelFormatting() {
        assertThat(col("a", "VARCHAR", 64, 0, true, null, 1).typeLabel()).isEqualTo("VARCHAR(64)");
        assertThat(col("a", "CHAR", 1, 0, true, null, 1).typeLabel()).isEqualTo("CHAR(1)");
        assertThat(col("a", "DECIMAL", 10, 2, true, null, 1).typeLabel())
                .isEqualTo("DECIMAL(10,2)");
        // INTEGER is not sized even though a size is reported.
        assertThat(col("a", "INTEGER", 10, 0, true, null, 1).typeLabel()).isEqualTo("INTEGER");
        // A zero size is never rendered.
        assertThat(col("a", "VARCHAR", 0, 0, true, null, 1).typeLabel()).isEqualTo("VARCHAR");
    }

    @Test
    @DisplayName("createTable renders columns, NOT NULL, DEFAULT and PRIMARY KEY")
    void createTableWithPk() {
        DdlGenerator gen = new DdlGenerator();
        List<MetadataReader.ColumnInfo> cols = List.of(
                col("id", "INTEGER", 10, 0, false, null, 1),
                col("name", "VARCHAR", 64, 0, true, "'anon'", 2));

        String ddl = gen.createTable(new TableRef(null, "public", "people"), cols, Set.of("id"));
        assertThat(ddl).contains("CREATE TABLE public.people (");
        assertThat(ddl).contains("id INTEGER NOT NULL,");
        assertThat(ddl).contains("name VARCHAR(64) DEFAULT 'anon',");
        assertThat(ddl).contains("PRIMARY KEY (id)");
        assertThat(ddl).endsWith(");");
    }

    @Test
    @DisplayName("createTable without a primary key omits the constraint and trailing comma")
    void createTableNoPk() {
        DdlGenerator gen = new DdlGenerator();
        List<MetadataReader.ColumnInfo> cols = List.of(
                col("a", "INTEGER", 10, 0, true, null, 1),
                col("b", "INTEGER", 10, 0, true, null, 2));

        String ddl = gen.createTable(new TableRef(null, null, "t"), cols, Set.of());
        assertThat(ddl).doesNotContain("PRIMARY KEY");
        // The last column line must not carry a trailing comma.
        assertThat(ddl).contains("b INTEGER\n);");
    }

    @Test
    @DisplayName("a composite primary key lists both columns")
    void compositePk() {
        DdlGenerator gen = new DdlGenerator();
        List<MetadataReader.ColumnInfo> cols = List.of(
                col("a", "INTEGER", 10, 0, false, null, 1),
                col("b", "INTEGER", 10, 0, false, null, 2));
        String ddl = gen.createTable(new TableRef(null, null, "t"), cols,
                new java.util.LinkedHashSet<>(List.of("a", "b")));
        assertThat(ddl).contains("PRIMARY KEY (a, b)");
    }
}
