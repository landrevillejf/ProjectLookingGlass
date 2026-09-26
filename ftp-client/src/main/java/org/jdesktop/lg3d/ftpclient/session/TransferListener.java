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
package org.jdesktop.lg3d.ftpclient.session;

/**
 * Observes {@link TransferJob} lifecycle and progress events fired by the
 * {@link TransferManager}. The Swing transfer-queue table registers a listener
 * and repaints the affected row; because events are fired from the worker thread
 * that runs a job, an implementation that touches Swing must hop onto the EDT
 * (for example with {@code SwingUtilities.invokeLater}).
 */
public interface TransferListener {

    /**
     * Called when a job changes state (queued, running, completed, failed,
     * cancelled) or its message is updated.
     *
     * @param job the job whose state changed
     */
    void jobStateChanged(TransferJob job);

    /**
     * Called periodically as bytes are transferred.
     *
     * @param job              the job making progress
     * @param transferredBytes the running total of bytes moved
     * @param totalBytes       the expected total, or {@code -1} when unknown
     */
    void jobProgress(TransferJob job, long transferredBytes, long totalBytes);
}
