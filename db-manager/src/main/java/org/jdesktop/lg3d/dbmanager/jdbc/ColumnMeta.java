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

/**
 * Metadata for one column of a query result, captured from
 * {@link java.sql.ResultSetMetaData} so the grid can render and edit values
 * without touching the (now closed) live result set.
 *
 * @param name       the column label
 * @param typeName   the database type name (e.g. {@code VARCHAR})
 * @param sqlType    the {@link java.sql.Types} constant
 * @param nullable   whether the column can hold NULL
 * @param className  the Java class name the driver maps values to
 * @param index      1-based column position in the result
 */
public record ColumnMeta(String name, String typeName, int sqlType, boolean nullable,
                         String className, int index) {
}
