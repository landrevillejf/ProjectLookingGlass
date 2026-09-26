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
package org.jdesktop.lg3d.ftpclient.net;

/**
 * A low-level byte-progress callback for a single {@link RemoteClient} upload or
 * download. The transfer loop calls {@link #onProgress(long)} after each buffered
 * chunk and polls {@link #isCancelled()} so a cooperative cancel can stop a long
 * transfer without closing the socket underneath it.
 *
 * <p>Implementations are invoked on the worker thread that runs the transfer, so
 * a UI listener must marshal any view update back onto the EDT (the SwingWorker's
 * {@code publish}/{@code process} path does this).</p>
 */
public interface ProgressListener {

    /**
     * Reports cumulative progress.
     *
     * @param bytesTransferred the total bytes copied so far for this transfer
     */
    void onProgress(long bytesTransferred);

    /**
     * Polls whether the transfer should abort.
     *
     * @return {@code true} to stop the copy loop at the next chunk boundary
     */
    default boolean isCancelled() {
        return false;
    }
}
