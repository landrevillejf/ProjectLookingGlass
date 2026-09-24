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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads database structure (catalogs, schemas, tables, columns, keys) through
 * the standard JDBC {@link DatabaseMetaData} API, so the navigator works against
 * any driver without database-specific SQL.
 *
 * <p>Every method is blocking and returns immutable snapshots; the navigator
 * calls them lazily as the user expands a node, so a large schema is never fully
 * walked up front. All methods throw {@link SQLException} for the caller to
 * surface.</p>
 */
public final class MetadataReader {

    /** The table types shown in the navigator. */
    private static final String[] TABLE_TYPES = {"TABLE", "VIEW"};

    /**
     * A table or view.
     *
     * @param catalog the catalog (may be {@code null})
     * @param schema  the schema (may be {@code null})
     * @param name    the table name
     * @param type    {@code TABLE} or {@code VIEW}
     * @param remarks driver-provided comment (may be empty)
     */
    public record TableInfo(String catalog, String schema, String name, String type, String remarks) {
        /** @return {@code true} for a view. */
        public boolean isView() {
            return "VIEW".equalsIgnoreCase(type);
        }
    }

    /**
     * A column of a table.
     *
     * @param name          the column name
     * @param typeName      the database type name
     * @param size          the declared size / precision
     * @param decimalDigits the scale, or {@code 0}
     * @param nullable      whether NULL is allowed
     * @param primaryKey    whether the column is part of the primary key
     * @param defaultValue  the column default (may be {@code null})
     * @param ordinal       1-based position
     */
    public record ColumnInfo(String name, String typeName, int size, int decimalDigits,
                             boolean nullable, boolean primaryKey, String defaultValue, int ordinal) {
        /** @return a compact type label such as {@code VARCHAR(64)}. */
        public String typeLabel() {
            if (size > 0 && isSized(typeName)) {
                return decimalDigits > 0
                        ? typeName + "(" + size + "," + decimalDigits + ")"
                        : typeName + "(" + size + ")";
            }
            return typeName;
        }

        private static boolean isSized(String typeName) {
            String t = typeName.toUpperCase();
            return t.contains("CHAR") || t.contains("BINARY")
                    || t.equals("DECIMAL") || t.equals("NUMERIC");
        }
    }

    /**
     * A foreign-key relationship from a table to a parent table.
     *
     * @param fkColumn       the referencing column
     * @param pkTableSchema  the referenced schema (may be {@code null})
     * @param pkTable        the referenced table
     * @param pkColumn       the referenced column
     */
    public record ForeignKeyInfo(String fkColumn, String pkTableSchema, String pkTable, String pkColumn) {
    }

    /** @return the catalogs, or an empty list when the driver has none. */
    public List<String> catalogs(Connection conn) throws SQLException {
        List<String> out = new ArrayList<>();
        DatabaseMetaData md = conn.getMetaData();
        try (ResultSet rs = md.getCatalogs()) {
            while (rs.next()) {
                out.add(rs.getString("TABLE_CAT"));
            }
        }
        return out;
    }

    /**
     * Returns the schemas for a catalog.
     *
     * @param conn    an open connection
     * @param catalog the catalog filter, or {@code null} for all
     * @return the schema names, possibly empty
     * @throws SQLException on a metadata error
     */
    public List<String> schemas(Connection conn, String catalog) throws SQLException {
        List<String> out = new ArrayList<>();
        DatabaseMetaData md = conn.getMetaData();
        try (ResultSet rs = md.getSchemas(catalog, null)) {
            while (rs.next()) {
                out.add(rs.getString("TABLE_SCHEM"));
            }
        }
        return out;
    }

    /**
     * Lists tables and views.
     *
     * @param conn    an open connection
     * @param catalog the catalog filter, or {@code null}
     * @param schema  the schema filter, or {@code null}
     * @return the tables/views, sorted by name
     * @throws SQLException on a metadata error
     */
    public List<TableInfo> tables(Connection conn, String catalog, String schema) throws SQLException {
        List<TableInfo> out = new ArrayList<>();
        DatabaseMetaData md = conn.getMetaData();
        try (ResultSet rs = md.getTables(catalog, schema, "%", TABLE_TYPES)) {
            while (rs.next()) {
                out.add(new TableInfo(
                        rs.getString("TABLE_CAT"),
                        rs.getString("TABLE_SCHEM"),
                        rs.getString("TABLE_NAME"),
                        rs.getString("TABLE_TYPE"),
                        rs.getString("REMARKS")));
            }
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    /**
     * Lists a table's columns, marking primary-key members.
     *
     * @param conn    an open connection
     * @param catalog the catalog filter, or {@code null}
     * @param schema  the schema filter, or {@code null}
     * @param table   the table name
     * @return the columns in ordinal order
     * @throws SQLException on a metadata error
     */
    public List<ColumnInfo> columns(Connection conn, String catalog, String schema, String table)
            throws SQLException {
        Set<String> pks = primaryKeyColumns(conn, catalog, schema, table);
        List<ColumnInfo> out = new ArrayList<>();
        DatabaseMetaData md = conn.getMetaData();
        try (ResultSet rs = md.getColumns(catalog, schema, table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                out.add(new ColumnInfo(
                        name,
                        rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"),
                        rs.getInt("DECIMAL_DIGITS"),
                        rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        pks.contains(name),
                        rs.getString("COLUMN_DEF"),
                        rs.getInt("ORDINAL_POSITION")));
            }
        }
        out.sort((a, b) -> Integer.compare(a.ordinal(), b.ordinal()));
        return out;
    }

    /**
     * Returns the ordered primary-key column names of a table.
     *
     * @param conn    an open connection
     * @param catalog the catalog filter, or {@code null}
     * @param schema  the schema filter, or {@code null}
     * @param table   the table name
     * @return the PK column names (empty when there is no primary key)
     * @throws SQLException on a metadata error
     */
    public Set<String> primaryKeyColumns(Connection conn, String catalog, String schema, String table)
            throws SQLException {
        Set<String> keys = new LinkedHashSet<>();
        DatabaseMetaData md = conn.getMetaData();
        List<String[]> ordered = new ArrayList<>();
        try (ResultSet rs = md.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                ordered.add(new String[]{rs.getString("COLUMN_NAME"), rs.getString("KEY_SEQ")});
            }
        }
        ordered.sort((a, b) -> {
            try {
                return Integer.compare(Integer.parseInt(a[1]), Integer.parseInt(b[1]));
            } catch (RuntimeException e) {
                return 0;
            }
        });
        for (String[] pair : ordered) {
            keys.add(pair[0]);
        }
        return keys;
    }

    /**
     * Lists a table's incoming foreign keys (the tables/columns it references).
     *
     * @param conn    an open connection
     * @param catalog the catalog filter, or {@code null}
     * @param schema  the schema filter, or {@code null}
     * @param table   the table name
     * @return the foreign keys, possibly empty
     * @throws SQLException on a metadata error
     */
    public List<ForeignKeyInfo> foreignKeys(Connection conn, String catalog, String schema, String table)
            throws SQLException {
        List<ForeignKeyInfo> out = new ArrayList<>();
        DatabaseMetaData md = conn.getMetaData();
        try (ResultSet rs = md.getImportedKeys(catalog, schema, table)) {
            while (rs.next()) {
                out.add(new ForeignKeyInfo(
                        rs.getString("FKCOLUMN_NAME"),
                        rs.getString("PKTABLE_SCHEM"),
                        rs.getString("PKTABLE_NAME"),
                        rs.getString("PKCOLUMN_NAME")));
            }
        }
        return out;
    }
}
