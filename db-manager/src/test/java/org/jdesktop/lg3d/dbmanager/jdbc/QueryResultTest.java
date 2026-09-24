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

import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the three {@link QueryResult} factory outcomes (result set, update
 * count, error), their accessors, defensive immutability and the
 * {@link QueryResult#summarize()} status text.
 */
class QueryResultTest {

    private static ColumnMeta col(String name, int index) {
        return new ColumnMeta(name, "VARCHAR", Types.VARCHAR, true, "java.lang.String", index);
    }

    @Test
    @DisplayName("a result-set outcome exposes columns, rows and truncation")
    void resultSetOutcome() {
        List<ColumnMeta> cols = List.of(col("a", 1), col("b", 2));
        List<List<Object>> rows = List.of(List.of("1", "x"), List.of("2", "y"));
        QueryResult r = QueryResult.ofResultSet("SELECT *", cols, rows, false, 12L);

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.hasResultSet()).isTrue();
        assertThat(r.getSql()).isEqualTo("SELECT *");
        assertThat(r.getColumns()).hasSize(2);
        assertThat(r.getRowCount()).isEqualTo(2);
        assertThat(r.getRows()).containsExactly(List.of("1", "x"), List.of("2", "y"));
        assertThat(r.isTruncated()).isFalse();
        assertThat(r.getElapsedMillis()).isEqualTo(12L);
        assertThat(r.getErrorMessage()).isNull();
        assertThat(r.getUpdateCount()).isEqualTo(-1);
        assertThat(r.summarize()).isEqualTo("2 rows in 12 ms");
    }

    @Test
    @DisplayName("a truncated single-row summary reads correctly")
    void truncatedSummary() {
        QueryResult r = QueryResult.ofResultSet("SELECT", List.of(col("a", 1)),
                List.of(List.of("only")), true, 5L);
        assertThat(r.isTruncated()).isTrue();
        assertThat(r.summarize()).isEqualTo("1 row in 5 ms (limited)");
    }

    @Test
    @DisplayName("an update-count outcome reports rows affected")
    void updateOutcome() {
        QueryResult r = QueryResult.ofUpdate("UPDATE t SET a=1", 3, 7L);
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.hasResultSet()).isFalse();
        assertThat(r.getUpdateCount()).isEqualTo(3);
        assertThat(r.getRowCount()).isZero();
        assertThat(r.summarize()).isEqualTo("3 rows updated in 7 ms");

        QueryResult one = QueryResult.ofUpdate("DELETE", 1, 1L);
        assertThat(one.summarize()).isEqualTo("1 row updated in 1 ms");
    }

    @Test
    @DisplayName("an error outcome carries the message and is not successful")
    void errorOutcome() {
        QueryResult r = QueryResult.ofError("SELECT bogus", "[42S02] table not found", 2L);
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.hasResultSet()).isFalse();
        assertThat(r.getErrorMessage()).contains("table not found");
        assertThat(r.summarize()).isEqualTo("Error: [42S02] table not found");
    }

    @Test
    @DisplayName("column and row lists are immutable snapshots")
    void immutableSnapshots() {
        QueryResult r = QueryResult.ofResultSet("SELECT", List.of(col("a", 1)),
                List.of(List.of("1")), false, 0L);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> r.getColumns().add(col("b", 2)));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> r.getRows().add(List.of("2")));
    }
}
