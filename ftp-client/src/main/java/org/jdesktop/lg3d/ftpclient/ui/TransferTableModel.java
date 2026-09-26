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
package org.jdesktop.lg3d.ftpclient.ui;

import java.util.ArrayList;
import java.util.List;
import javax.swing.table.AbstractTableModel;
import org.jdesktop.lg3d.ftpclient.session.TransferJob;

/**
 * A read-only {@link javax.swing.table.TableModel} over the transfer queue. Each
 * row is one {@link TransferJob}; the columns show the file name, direction,
 * total size, how far it has got, its state and the latest status message.
 *
 * <p>The model holds a snapshot of the queue. Because {@link TransferJob}'s
 * counters are {@code volatile} and mutated on a worker thread, the panel calls
 * {@link #refresh(List)} on the EDT (from a {@code TransferListener}) to pull a
 * fresh snapshot and repaint, rather than the model polling.</p>
 */
public final class TransferTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {
        "File", "Direction", "Size", "Transferred", "Progress", "State", "Message"
    };

    private final List<TransferJob> jobs = new ArrayList<>();

    /**
     * Replaces the snapshot with the given queue and repaints.
     *
     * @param queue the current jobs; {@code null} clears the table
     */
    public void refresh(List<TransferJob> queue) {
        jobs.clear();
        if (queue != null) {
            jobs.addAll(queue);
        }
        fireTableDataChanged();
    }

    /** @return the job on a row, or {@code null} when out of range. */
    public TransferJob getJobAt(int row) {
        return (row >= 0 && row < jobs.size()) ? jobs.get(row) : null;
    }

    @Override
    public int getRowCount() {
        return jobs.size();
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
    public Object getValueAt(int rowIndex, int columnIndex) {
        TransferJob job = getJobAt(rowIndex);
        if (job == null) {
            return "";
        }
        switch (columnIndex) {
            case 0:
                return job.getDisplayName();
            case 1:
                return job.getDirection() == TransferJob.Direction.UPLOAD ? "Upload" : "Download";
            case 2:
                return UiFormats.bytes(job.getTotalBytes());
            case 3:
                return UiFormats.bytes(job.getTransferredBytes());
            case 4:
                return UiFormats.percent(job.getProgressFraction());
            case 5:
                return job.getState().name();
            case 6:
                return job.getMessage();
            default:
                return "";
        }
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }
}
