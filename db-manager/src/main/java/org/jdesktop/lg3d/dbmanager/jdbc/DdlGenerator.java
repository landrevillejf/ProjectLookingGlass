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

import java.util.List;
import java.util.Set;

/**
 * Generates a best-effort {@code CREATE TABLE} statement from JDBC column
 * metadata, for the navigator's "view DDL" action.
 *
 * <p>This reconstructs a portable approximation of the schema (columns, types,
 * nullability, defaults, primary key). It is intentionally not a faithful
 * dump of vendor-specific clauses (check constraints, storage options, comments),
 * which {@link java.sql.DatabaseMetaData} does not expose uniformly; its purpose is to
 * let a user read and copy a table's shape, not to reproduce it byte-for-byte.</p>
 */
public final class DdlGenerator {

    /**
     * Renders a CREATE TABLE statement.
     *
     * @param table     the table reference
     * @param columns   the columns in ordinal order
     * @param pkColumns the primary-key column names (may be empty)
     * @return the DDL text
     */
    public String createTable(DmlBuilder.TableRef table,
                              List<MetadataReader.ColumnInfo> columns,
                              Set<String> pkColumns) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(DmlBuilder.qualify(table)).append(" (\n");
        for (int i = 0; i < columns.size(); i++) {
            MetadataReader.ColumnInfo c = columns.get(i);
            sb.append("  ").append(DmlBuilder.quote(c.name())).append(' ').append(c.typeLabel());
            if (!c.nullable()) {
                sb.append(" NOT NULL");
            }
            if (c.defaultValue() != null && !c.defaultValue().isBlank()) {
                sb.append(" DEFAULT ").append(c.defaultValue());
            }
            boolean moreColumns = i < columns.size() - 1;
            boolean hasPk = pkColumns != null && !pkColumns.isEmpty();
            if (moreColumns || hasPk) {
                sb.append(',');
            }
            sb.append('\n');
        }
        if (pkColumns != null && !pkColumns.isEmpty()) {
            List<String> quoted = pkColumns.stream().map(DmlBuilder::quote).toList();
            sb.append("  PRIMARY KEY (").append(String.join(", ", quoted)).append(")\n");
        }
        sb.append(");");
        return sb.toString();
    }
}
