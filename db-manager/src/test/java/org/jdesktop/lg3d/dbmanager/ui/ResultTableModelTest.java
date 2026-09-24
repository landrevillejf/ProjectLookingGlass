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
package org.jdesktop.lg3d.dbmanager.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jdesktop.lg3d.dbmanager.jdbc.ColumnMeta;
import org.jdesktop.lg3d.dbmanager.jdbc.QueryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ResultTableModel}: in-memory paging over a {@link QueryResult},
 * the empty/null states, NULL rendering as the configured null text, page
 * navigation clamping, the page label and the read-only cell contract.
 */
class ResultTableModelTest {

    private static QueryResult result(int columnCount, int rowCount) {
        List<ColumnMeta> cols = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            cols.add(new ColumnMeta("c" + i, "INTEGER", Types.INTEGER, true,
                    "java.lang.Integer", i));
        }
        List<List<Object>> rows = new ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            List<Object> row = new ArrayList<>();
            for (int c = 0; c < columnCount; c++) {
                row.add(r * columnCount + c);
            }
            rows.add(row);
        }
        return QueryResult.ofResultSet("SELECT", cols, rows, false, 0L);
    }

    @Test
    @DisplayName("an empty model reports no rows/columns and a single page")
    void emptyModel() {
        ResultTableModel m = new ResultTableModel();
        assertThat(m.getRowCount()).isZero();
        assertThat(m.getColumnCount()).isZero();
        assertThat(m.getPageCount()).isEqualTo(1);
        assertThat(m.getCurrentPage()).isEqualTo(1);
        assertThat(m.getPageLabel()).isEqualTo("0 rows");
        assertThat(m.getQueryResult()).isNull();
    }

    @Test
    @DisplayName("a loaded result exposes its columns and rows")
    void loadedResult() {
        ResultTableModel m = new ResultTableModel();
        m.setQueryResult(result(2, 5));
        assertThat(m.getQueryResult()).isNotNull();
        assertThat(m.getColumnCount()).isEqualTo(2);
        assertThat(m.getRowCount()).isEqualTo(5);
        assertThat(m.getColumnName(0)).isEqualTo("c1");
        assertThat(m.getColumnName(1)).isEqualTo("c2");
        assertThat(m.getColumnName(99)).isEmpty();
        assertThat(m.getValueAt(0, 0)).isEqualTo(0);
        assertThat(m.getValueAt(1, 1)).isEqualTo(3);
    }

    @Test
    @DisplayName("out-of-range cells render the null text")
    void outOfRangeCells() {
        ResultTableModel m = new ResultTableModel();
        m.setQueryResult(result(2, 3));
        m.setNullText("<none>");
        assertThat(m.getValueAt(99, 0)).isEqualTo("<none>");
        assertThat(m.getValueAt(0, 99)).isEqualTo("<none>");
    }

    @Test
    @DisplayName("a SQL NULL value renders as the null text")
    void nullValueRendersNullText() {
        List<ColumnMeta> cols = List.of(
                new ColumnMeta("c1", "INTEGER", Types.INTEGER, true, "java.lang.Integer", 1));
        List<List<Object>> rows = List.of(Arrays.asList((Object) null));
        ResultTableModel m = new ResultTableModel();
        m.setQueryResult(QueryResult.ofResultSet("SELECT", cols, rows, false, 0L));
        assertThat(m.getValueAt(0, 0)).isEqualTo("(null)");
        m.setNullText("N/A");
        assertThat(m.getValueAt(0, 0)).isEqualTo("N/A");
        m.setNullText(null);
        assertThat(m.getValueAt(0, 0)).isEqualTo("");
    }

    @Test
    @DisplayName("paging walks first/next/prev/last with clamping and a page label")
    void paging() {
        ResultTableModel m = new ResultTableModel();
        m.setPageSize(2);
        m.setQueryResult(result(1, 5)); // 5 rows / 2 per page = 3 pages
        assertThat(m.getPageCount()).isEqualTo(3);
        assertThat(m.getCurrentPage()).isEqualTo(1);
        assertThat(m.getPageLabel()).isEqualTo("Rows 1-2 of 5");

        m.nextPage();
        assertThat(m.getCurrentPage()).isEqualTo(2);
        assertThat(m.getPageLabel()).isEqualTo("Rows 3-4 of 5");

        m.nextPage();
        assertThat(m.getCurrentPage()).isEqualTo(3);
        assertThat(m.getRowCount()).isEqualTo(1); // last page has one row
        assertThat(m.getPageLabel()).isEqualTo("Rows 5-5 of 5");

        m.nextPage(); // clamped at the last page
        assertThat(m.getCurrentPage()).isEqualTo(3);

        m.previousPage();
        assertThat(m.getCurrentPage()).isEqualTo(2);
        m.firstPage();
        assertThat(m.getCurrentPage()).isEqualTo(1);
        m.previousPage(); // clamped at the first page
        assertThat(m.getCurrentPage()).isEqualTo(1);
        m.lastPage();
        assertThat(m.getCurrentPage()).isEqualTo(3);
    }

    @Test
    @DisplayName("an invalid page size falls back to 200 and rewinds")
    void invalidPageSize() {
        ResultTableModel m = new ResultTableModel();
        m.setQueryResult(result(1, 5));
        m.setPageSize(0);
        assertThat(m.getPageSize()).isEqualTo(200);
        m.setPageSize(-3);
        assertThat(m.getPageSize()).isEqualTo(200);
    }

    @Test
    @DisplayName("an update-count result (no result set) shows no grid rows")
    void updateResultHasNoRows() {
        ResultTableModel m = new ResultTableModel();
        m.setQueryResult(QueryResult.ofUpdate("UPDATE t SET a=1", 3, 0L));
        assertThat(m.getRowCount()).isZero();
        assertThat(m.getColumnCount()).isZero();
        assertThat(m.getPageLabel()).isEqualTo("0 rows");
    }

    @Test
    @DisplayName("every cell is read-only")
    void cellsAreReadOnly() {
        ResultTableModel m = new ResultTableModel();
        m.setQueryResult(result(2, 2));
        assertThat(m.isCellEditable(0, 0)).isFalse();
    }
}
