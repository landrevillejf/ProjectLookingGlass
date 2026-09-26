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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.jdesktop.lg3d.ftpclient.session.TransferJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Covers the read-only {@link TransferTableModel} column mapping and snapshot refresh. */
class TransferTableModelTest {

    private static TransferJob job(TransferJob.Direction dir, String fileName, String remote) {
        return new TransferJob(dir, Path.of("/tmp/" + fileName), remote);
    }

    @Test
    @DisplayName("an empty model has seven named columns and no rows")
    void emptyModel() {
        TransferTableModel m = new TransferTableModel();
        assertThat(m.getRowCount()).isZero();
        assertThat(m.getColumnCount()).isEqualTo(7);
        assertThat(m.getColumnName(0)).isEqualTo("File");
        assertThat(m.getColumnName(1)).isEqualTo("Direction");
        assertThat(m.getColumnName(2)).isEqualTo("Size");
        assertThat(m.getColumnName(3)).isEqualTo("Transferred");
        assertThat(m.getColumnName(4)).isEqualTo("Progress");
        assertThat(m.getColumnName(5)).isEqualTo("State");
        assertThat(m.getColumnName(6)).isEqualTo("Message");
        // Out-of-range column name is blank, not an exception.
        assertThat(m.getColumnName(99)).isEmpty();
        // Out-of-range row reads as an empty string.
        assertThat(m.getValueAt(0, 0)).isEqualTo("");
        assertThat(m.getJobAt(0)).isNull();
        assertThat(m.getJobAt(-1)).isNull();
        assertThat(m.isCellEditable(0, 0)).isFalse();
    }

    @Test
    @DisplayName("a running upload maps every column from the job")
    void runningUpload() {
        TransferJob j = job(TransferJob.Direction.UPLOAD, "payload.bin", "/remote/payload.bin");
        j.setTotalBytes(2048L);
        j.setTransferredBytes(1024L);
        j.setState(TransferJob.State.RUNNING);
        j.setMessage("Transferring");

        TransferTableModel m = new TransferTableModel();
        m.refresh(List.of(j));
        assertThat(m.getRowCount()).isEqualTo(1);
        assertThat(m.getJobAt(0)).isSameAs(j);
        assertThat(m.getValueAt(0, 0)).isEqualTo("payload.bin");
        assertThat(m.getValueAt(0, 1)).isEqualTo("Upload");
        assertThat(m.getValueAt(0, 2)).isEqualTo("2.0 KB");
        assertThat(m.getValueAt(0, 3)).isEqualTo("1.0 KB");
        assertThat(m.getValueAt(0, 4)).isEqualTo("50%");
        assertThat(m.getValueAt(0, 5)).isEqualTo("RUNNING");
        assertThat(m.getValueAt(0, 6)).isEqualTo("Transferring");
        // An unknown column index is blank.
        assertThat(m.getValueAt(0, 42)).isEqualTo("");
    }

    @Test
    @DisplayName("a completed download reads Download / 100% / COMPLETED")
    void completedDownload() {
        TransferJob j = job(TransferJob.Direction.DOWNLOAD, "report.pdf", "/remote/report.pdf");
        j.setTotalBytes(10L);
        j.setTransferredBytes(10L);
        j.setState(TransferJob.State.COMPLETED);
        j.setMessage("Done");

        TransferTableModel m = new TransferTableModel();
        m.refresh(List.of(j));
        assertThat(m.getValueAt(0, 1)).isEqualTo("Download");
        assertThat(m.getValueAt(0, 4)).isEqualTo("100%");
        assertThat(m.getValueAt(0, 5)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("refresh replaces the snapshot and refresh(null) clears it")
    void refreshReplacesAndClears() {
        TransferTableModel m = new TransferTableModel();
        m.refresh(List.of(job(TransferJob.Direction.UPLOAD, "a", "/a"),
                job(TransferJob.Direction.UPLOAD, "b", "/b")));
        assertThat(m.getRowCount()).isEqualTo(2);

        m.refresh(List.of(job(TransferJob.Direction.DOWNLOAD, "c", "/c")));
        assertThat(m.getRowCount()).isEqualTo(1);
        assertThat(m.getValueAt(0, 0)).isEqualTo("c");

        m.refresh(null);
        assertThat(m.getRowCount()).isZero();
    }
}
