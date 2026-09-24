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

import java.util.List;
import javax.swing.table.AbstractTableModel;
import org.jdesktop.lg3d.dbmanager.jdbc.ColumnMeta;
import org.jdesktop.lg3d.dbmanager.jdbc.QueryResult;

/**
 * A read-only {@link javax.swing.table.TableModel} that pages over the rows of a
 * {@link QueryResult} held in memory. Only one page (at most {@code pageSize}
 * rows) is exposed to the {@code JTable} at a time, so a result capped at the
 * configured maximum renders quickly and the user pages through it with the
 * grid's First/Prev/Next/Last controls.
 *
 * <p>SQL NULLs are rendered as the configured null text. Editing happens through
 * the SQL editor (which produces the DML), not in-place, so every cell is
 * non-editable here.</p>
 */
public final class ResultTableModel extends AbstractTableModel {

    private QueryResult result;
    private int pageSize = 200;
    private int offset;
    private String nullText = "(null)";

    /** Loads a new result and rewinds to the first page. */
    public void setQueryResult(QueryResult result) {
        this.result = result;
        this.offset = 0;
        fireTableStructureChanged();
    }

    /** @return the current result, or {@code null} when nothing has run. */
    public QueryResult getQueryResult() {
        return result;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = (pageSize > 0) ? pageSize : 200;
        this.offset = 0;
        fireTableDataChanged();
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setNullText(String nullText) {
        this.nullText = (nullText == null) ? "" : nullText;
        fireTableDataChanged();
    }

    private int totalRows() {
        return (result != null && result.hasResultSet()) ? result.getRowCount() : 0;
    }

    /** @return the number of pages, at least 1. */
    public int getPageCount() {
        int total = totalRows();
        if (total == 0) {
            return 1;
        }
        return Math.max(1, (total + pageSize - 1) / pageSize);
    }

    /** @return the 1-based current page number. */
    public int getCurrentPage() {
        return (offset / pageSize) + 1;
    }

    public void firstPage() {
        setPageOffset(0);
    }

    public void previousPage() {
        setPageOffset(offset - pageSize);
    }

    public void nextPage() {
        setPageOffset(offset + pageSize);
    }

    public void lastPage() {
        setPageOffset((getPageCount() - 1) * pageSize);
    }

    private void setPageOffset(int newOffset) {
        int total = totalRows();
        int max = Math.max(0, ((total + pageSize - 1) / pageSize) - 1) * pageSize;
        int clamped = Math.max(0, Math.min(newOffset, max));
        if (clamped != offset) {
            this.offset = clamped;
            fireTableDataChanged();
        }
    }

    /** @return a status-bar label such as {@code Rows 1-200 of 1350}. */
    public String getPageLabel() {
        int total = totalRows();
        if (total == 0) {
            return "0 rows";
        }
        int from = offset + 1;
        int to = Math.min(total, offset + getRowCount());
        return "Rows " + from + "-" + to + " of " + total;
    }

    @Override
    public int getRowCount() {
        int total = totalRows();
        int remaining = total - offset;
        if (remaining <= 0) {
            return 0;
        }
        return Math.min(pageSize, remaining);
    }

    @Override
    public int getColumnCount() {
        return (result != null && result.hasResultSet()) ? result.getColumns().size() : 0;
    }

    @Override
    public String getColumnName(int column) {
        List<ColumnMeta> cols = result.getColumns();
        return (column >= 0 && column < cols.size()) ? cols.get(column).name() : "";
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        int absolute = offset + rowIndex;
        List<List<Object>> rows = result.getRows();
        if (absolute < 0 || absolute >= rows.size()) {
            return nullText;
        }
        List<Object> row = rows.get(absolute);
        if (columnIndex < 0 || columnIndex >= row.size()) {
            return nullText;
        }
        Object v = row.get(columnIndex);
        return (v == null) ? nullText : v;
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }
}
