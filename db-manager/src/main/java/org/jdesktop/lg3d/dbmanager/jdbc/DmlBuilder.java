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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds parameterized INSERT / UPDATE / DELETE statements from grid edits, so
 * committing a data change never string-concatenates user values (which would
 * risk both SQL injection and quoting bugs). Values are returned as an ordered
 * bind list alongside the {@code ?}-placeholder SQL.
 *
 * <p>Identifiers are emitted bare when they are simple
 * ({@code [A-Za-z_][A-Za-z0-9_]*}) and double-quoted otherwise, which is correct
 * for the SQL-standard databases (H2, PostgreSQL, SQLite). UPDATE and DELETE
 * require a primary key; when a table has none the caller must fall back to a
 * read-only grid.</p>
 */
public final class DmlBuilder {

    /**
     * A table reference.
     *
     * @param catalog the catalog (may be {@code null})
     * @param schema  the schema (may be {@code null})
     * @param name    the table name
     */
    public record TableRef(String catalog, String schema, String name) {
    }

    /**
     * A statement plus its ordered bind values.
     *
     * @param sql    the {@code ?}-placeholder SQL
     * @param params the values to bind, in placeholder order
     */
    public record PreparedSql(String sql, List<Object> params) {
    }

    /**
     * Builds an INSERT for one new row.
     *
     * @param table   the target table
     * @param columns the columns to insert (order defines the value order)
     * @param values  column-name to value; a missing/absent column is skipped
     * @return the prepared statement
     */
    public PreparedSql insert(TableRef table, List<MetadataReader.ColumnInfo> columns,
                              Map<String, Object> values) {
        List<String> cols = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (MetadataReader.ColumnInfo c : columns) {
            if (values.containsKey(c.name())) {
                cols.add(quote(c.name()));
                params.add(values.get(c.name()));
            }
        }
        String placeholders = String.join(", ", cols.stream().map(x -> "?").toList());
        String sql = "INSERT INTO " + qualify(table) + " ("
                + String.join(", ", cols) + ") VALUES (" + placeholders + ")";
        return new PreparedSql(sql, params);
    }

    /**
     * Builds an UPDATE for the changed columns of one row, keyed by primary key.
     *
     * @param table      the target table
     * @param pkColumns  the primary-key column names (non-empty)
     * @param pkValues   the original primary-key values
     * @param newValues  changed column-name to new value (PK columns ignored here)
     * @return the prepared statement
     * @throws IllegalArgumentException when there is no primary key or nothing to change
     */
    public PreparedSql update(TableRef table, Set<String> pkColumns,
                              Map<String, Object> pkValues, Map<String, Object> newValues) {
        requirePk(pkColumns);
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> e : newValues.entrySet()) {
            if (pkColumns.contains(e.getKey())) {
                continue;
            }
            sets.add(quote(e.getKey()) + " = ?");
            params.add(e.getValue());
        }
        if (sets.isEmpty()) {
            throw new IllegalArgumentException("No columns to update");
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(qualify(table))
                .append(" SET ").append(String.join(", ", sets));
        appendWhere(sql, pkColumns, pkValues, params);
        return new PreparedSql(sql.toString(), params);
    }

    /**
     * Builds a DELETE for one row, keyed by primary key.
     *
     * @param table     the target table
     * @param pkColumns the primary-key column names (non-empty)
     * @param pkValues  the primary-key values identifying the row
     * @return the prepared statement
     * @throws IllegalArgumentException when there is no primary key
     */
    public PreparedSql delete(TableRef table, Set<String> pkColumns, Map<String, Object> pkValues) {
        requirePk(pkColumns);
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(qualify(table));
        appendWhere(sql, pkColumns, pkValues, params);
        return new PreparedSql(sql.toString(), params);
    }

    private void appendWhere(StringBuilder sql, Set<String> pkColumns,
                             Map<String, Object> pkValues, List<Object> params) {
        List<String> conds = new ArrayList<>();
        for (String pk : pkColumns) {
            Object v = pkValues.get(pk);
            if (v == null) {
                conds.add(quote(pk) + " IS NULL");
            } else {
                conds.add(quote(pk) + " = ?");
                params.add(v);
            }
        }
        sql.append(" WHERE ").append(String.join(" AND ", conds));
    }

    private static void requirePk(Set<String> pkColumns) {
        if (pkColumns == null || pkColumns.isEmpty()) {
            throw new IllegalArgumentException(
                    "This table has no primary key, so rows cannot be edited in place");
        }
    }

    /** @return the schema-qualified, optionally quoted table name. */
    static String qualify(TableRef table) {
        StringBuilder sb = new StringBuilder();
        if (table.schema() != null && !table.schema().isBlank()) {
            sb.append(quote(table.schema())).append('.');
        }
        sb.append(quote(table.name()));
        return sb.toString();
    }

    /** Quotes an identifier only when it is not a simple name. */
    static String quote(String identifier) {
        if (identifier == null) {
            return "";
        }
        if (identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return identifier;
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
