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
package org.jdesktop.lg3d.apps.taskmanager;

import java.util.ArrayList;
import java.util.List;
import javax.swing.table.AbstractTableModel;
import org.jdesktop.lg3d.utils.system.ProcessService;

/**
 * Table model binding {@link ProcessService} snapshots to the task manager's
 * process table. Columns are PID / Name / User / CPU% / Memory / State; the
 * numeric columns report their raw types so the table's row sorter orders them
 * numerically, and renderers format them for display.
 */
public class ProcessTableModel extends AbstractTableModel {

    private static final String[] COLUMNS =
            {"PID", "Name", "User", "CPU %", "Memory", "State"};

    private final List<ProcessService.ProcessInfo> rows = new ArrayList<>();

    /** Replaces the rows with a fresh snapshot. */
    public void setRows(List<ProcessService.ProcessInfo> procs) {
        rows.clear();
        if (procs != null) {
            rows.addAll(procs);
        }
        fireTableDataChanged();
    }

    /** The snapshot entry at a model row, or null if out of range. */
    public ProcessService.ProcessInfo getProcessAt(int modelRow) {
        return (modelRow >= 0 && modelRow < rows.size()) ? rows.get(modelRow) : null;
    }

    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return (column >= 0 && column < COLUMNS.length) ? COLUMNS[column] : "";
    }

    @Override
    public Class<?> getColumnClass(int column) {
        switch (column) {
            case 0: return Long.class;
            case 3: return Double.class;
            case 4: return Long.class;
            default: return String.class;
        }
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        ProcessService.ProcessInfo p = getProcessAt(rowIndex);
        if (p == null) {
            return "";
        }
        switch (columnIndex) {
            case 0: return Long.valueOf(p.getPid());
            case 1: return p.getName();
            case 2: return p.getUser();
            case 3: return Double.valueOf(p.getCpuPercent());
            case 4: return Long.valueOf(p.getRssBytes());
            case 5: return p.getStateLabel();
            default: return "";
        }
    }
}
