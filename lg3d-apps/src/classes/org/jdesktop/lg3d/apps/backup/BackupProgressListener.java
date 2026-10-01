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
package org.jdesktop.lg3d.apps.backup;

/**
 * Progress callback for a {@link BackupEngine} backup or restore run. The engine
 * invokes {@link #onProgress} once per archive entry, always from the worker
 * thread running the job; a Swing caller must marshal any UI update onto the EDT
 * (the {@link BackupPanel} does this via a {@code SwingWorker}).
 *
 * <p>Counts may be estimates: {@code entriesTotal}/{@code bytesTotal} are
 * computed from a pre-pass over the sources (backup) or the archive directory
 * (restore) and can be {@code -1} when unknown.</p>
 */
public interface BackupProgressListener {

    /** A listener that ignores every update. */
    BackupProgressListener NULL =
            (currentEntry, entriesDone, entriesTotal, bytesDone, bytesTotal) -> { };

    /**
     * Reports progress as entries are processed.
     *
     * @param currentEntry the archive-relative entry just processed
     * @param entriesDone  entries completed so far (including the current one)
     * @param entriesTotal total entries expected, or {@code -1} if unknown
     * @param bytesDone    uncompressed bytes processed so far
     * @param bytesTotal   total uncompressed bytes expected, or {@code -1} if unknown
     */
    void onProgress(String currentEntry, long entriesDone, long entriesTotal,
                    long bytesDone, long bytesTotal);
}
