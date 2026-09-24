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
import java.util.Collections;
import java.util.List;

/**
 * The outcome of executing one SQL statement: either a row set, an update count,
 * or an error. Values are copied out of the JDBC result set so the connection's
 * cursor can be closed immediately and the grid can page over the rows in
 * memory.
 *
 * <p>Instances are immutable and created through the {@code of...} factories.</p>
 */
public final class QueryResult {

    private final String sql;
    private final boolean success;
    private final boolean hasResultSet;
    private final List<ColumnMeta> columns;
    private final List<List<Object>> rows;
    private final int updateCount;
    private final boolean truncated;
    private final long elapsedMillis;
    private final String errorMessage;

    private QueryResult(String sql, boolean success, boolean hasResultSet,
                        List<ColumnMeta> columns, List<List<Object>> rows,
                        int updateCount, boolean truncated, long elapsedMillis,
                        String errorMessage) {
        this.sql = sql;
        this.success = success;
        this.hasResultSet = hasResultSet;
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
        this.updateCount = updateCount;
        this.truncated = truncated;
        this.elapsedMillis = elapsedMillis;
        this.errorMessage = errorMessage;
    }

    /**
     * Builds a result-set outcome.
     *
     * @param sql           the executed statement
     * @param columns       column metadata
     * @param rows          the fetched rows (already capped)
     * @param truncated     {@code true} when the row limit cut the result short
     * @param elapsedMillis execution time
     * @return the result
     */
    public static QueryResult ofResultSet(String sql, List<ColumnMeta> columns,
                                          List<List<Object>> rows, boolean truncated,
                                          long elapsedMillis) {
        return new QueryResult(sql, true, true, columns, rows, -1, truncated,
                elapsedMillis, null);
    }

    /**
     * Builds a DML/DDL update-count outcome.
     *
     * @param sql           the executed statement
     * @param updateCount   rows affected (may be {@code 0} or a driver's SUCCESS_NO_INFO)
     * @param elapsedMillis execution time
     * @return the result
     */
    public static QueryResult ofUpdate(String sql, int updateCount, long elapsedMillis) {
        return new QueryResult(sql, true, false, List.of(), List.of(), updateCount,
                false, elapsedMillis, null);
    }

    /**
     * Builds a failure outcome.
     *
     * @param sql           the statement that failed
     * @param errorMessage  the driver's message
     * @param elapsedMillis time spent before the failure
     * @return the result
     */
    public static QueryResult ofError(String sql, String errorMessage, long elapsedMillis) {
        return new QueryResult(sql, false, false, List.of(), List.of(), -1, false,
                elapsedMillis, errorMessage);
    }

    public String getSql() {
        return sql;
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean hasResultSet() {
        return hasResultSet;
    }

    public List<ColumnMeta> getColumns() {
        return columns;
    }

    public List<List<Object>> getRows() {
        return rows;
    }

    public int getRowCount() {
        return rows.size();
    }

    public int getUpdateCount() {
        return updateCount;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    /** @return a short human-readable summary for the status bar. */
    public String summarize() {
        if (!success) {
            return "Error: " + errorMessage;
        }
        if (hasResultSet) {
            String base = getRowCount() + " row" + (getRowCount() == 1 ? "" : "s")
                    + " in " + elapsedMillis + " ms";
            return truncated ? base + " (limited)" : base;
        }
        return updateCount + " row" + (updateCount == 1 ? "" : "s")
                + " updated in " + elapsedMillis + " ms";
    }
}
