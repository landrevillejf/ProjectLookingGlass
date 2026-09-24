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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import org.jdesktop.lg3d.dbmanager.jdbc.MetadataReader.ColumnInfo;
import org.jdesktop.lg3d.dbmanager.jdbc.MetadataReader.ForeignKeyInfo;
import org.jdesktop.lg3d.dbmanager.jdbc.MetadataReader.TableInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link MetadataReader} against a real H2 in-memory schema (two
 * tables with a foreign key plus a view), verifying that the standard JDBC
 * {@code DatabaseMetaData} reads the structure back correctly.
 */
class MetadataReaderTest {

    private static final String URL = "jdbc:h2:mem:meta;DB_CLOSE_DELAY=-1";

    private final MetadataReader reader = new MetadataReader();
    private Connection conn;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection(URL, "sa", "");
        try (Statement st = conn.createStatement()) {
            st.execute("DROP VIEW IF EXISTS people_v");
            st.execute("DROP TABLE IF EXISTS orders");
            st.execute("DROP TABLE IF EXISTS people");
            st.execute("CREATE TABLE people (id INT PRIMARY KEY, name VARCHAR(64), age INT)");
            st.execute("CREATE TABLE orders (id INT PRIMARY KEY, person_id INT, "
                    + "CONSTRAINT fk_person FOREIGN KEY (person_id) REFERENCES people(id))");
            st.execute("CREATE VIEW people_v AS SELECT id, name FROM people");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    @DisplayName("tables lists the base tables and the view, sorted, with types")
    void listsTablesAndViews() throws SQLException {
        List<TableInfo> tables = reader.tables(conn, null, "PUBLIC");
        List<String> names = tables.stream().map(TableInfo::name).toList();
        assertThat(names).contains("PEOPLE", "ORDERS", "PEOPLE_V");
        // sorted case-insensitively by name
        assertThat(names).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);

        TableInfo view = tables.stream().filter(t -> t.name().equals("PEOPLE_V"))
                .findFirst().orElseThrow();
        assertThat(view.isView()).isTrue();
        assertThat(view.type()).isEqualTo("VIEW");
        TableInfo base = tables.stream().filter(t -> t.name().equals("PEOPLE"))
                .findFirst().orElseThrow();
        assertThat(base.isView()).isFalse();
        assertThat(base.schema()).isEqualTo("PUBLIC");
    }

    @Test
    @DisplayName("columns returns ordered columns and flags the primary key")
    void readsColumns() throws SQLException {
        List<ColumnInfo> cols = reader.columns(conn, null, "PUBLIC", "PEOPLE");
        assertThat(cols).hasSize(3);
        assertThat(cols).extracting(ColumnInfo::name)
                .containsExactly("ID", "NAME", "AGE");
        assertThat(cols.get(0).ordinal()).isEqualTo(1);
        assertThat(cols.get(0).primaryKey()).isTrue();
        assertThat(cols.get(1).primaryKey()).isFalse();
        // H2 2.x reports VARCHAR as the SQL-standard "CHARACTER VARYING", so
        // assert on the driver-agnostic "CHAR" substring rather than a literal.
        String nameType = cols.get(1).typeName();
        assertThat(nameType).containsIgnoringCase("CHAR");
        assertThat(cols.get(1).typeLabel()).startsWith(nameType).contains("64");
    }

    @Test
    @DisplayName("primaryKeyColumns returns the key members in order")
    void readsPrimaryKeys() throws SQLException {
        Set<String> pk = reader.primaryKeyColumns(conn, null, "PUBLIC", "PEOPLE");
        assertThat(pk).containsExactly("ID");
        // A table with no primary key yields an empty set.
        assertThat(reader.primaryKeyColumns(conn, null, "PUBLIC", "PEOPLE_V")).isEmpty();
    }

    @Test
    @DisplayName("foreignKeys reads the imported key relationship")
    void readsForeignKeys() throws SQLException {
        List<ForeignKeyInfo> fks = reader.foreignKeys(conn, null, "PUBLIC", "ORDERS");
        assertThat(fks).hasSize(1);
        ForeignKeyInfo fk = fks.get(0);
        assertThat(fk.fkColumn()).isEqualTo("PERSON_ID");
        assertThat(fk.pkTable()).isEqualTo("PEOPLE");
        assertThat(fk.pkColumn()).isEqualTo("ID");
    }

    @Test
    @DisplayName("schemas and catalogs are enumerated")
    void readsSchemasAndCatalogs() throws SQLException {
        assertThat(reader.schemas(conn, null)).contains("PUBLIC");
        // H2 exposes at least one catalog; the exact name is the in-memory db.
        assertThat(reader.catalogs(conn)).isNotNull();
    }
}
